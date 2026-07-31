use std::{
    collections::{HashMap, VecDeque},
    sync::Arc,
};

use chrono::Utc;
use tokio::sync::{mpsc, oneshot};
use uuid::Uuid;

use crate::{
    db::MessageStore,
    errors::RoomError,
    models::{ChatMessage, MessageKind},
};

const ROOM_MAILBOX_CAPACITY: usize = 1_024;
const SESSION_MAILBOX_CAPACITY: usize = 256;
const ACTIVE_BUFFER_LIMIT: usize = 512;
const MAX_TEXT_BODY_BYTES: usize = 8 * 1024;
const MAX_INLINE_MEDIA_BODY_BYTES: usize = 1024 * 1024;

#[derive(Clone, Debug, serde::Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerEvent {
    MessageCreated {
        message: ChatMessage,
    },
    MediaReady {
        room_id: Uuid,
        upload_id: Uuid,
        url: String,
    },
}

#[derive(Debug)]
pub struct OutboundMessage {
    pub room_id: Uuid,
    pub channel_id: Uuid,
    pub sender_user_id: Uuid,
    pub kind: MessageKind,
    pub body: Vec<u8>,
    pub client_message_id: Option<Uuid>,
    pub media_upload_id: Option<Uuid>,
}

#[derive(Debug)]
pub enum RoomCommand {
    Connect {
        session_id: Uuid,
        sender: mpsc::Sender<ServerEvent>,
    },
    Disconnect {
        session_id: Uuid,
    },
    SendMessage {
        message: OutboundMessage,
        response: oneshot::Sender<Result<ChatMessage, RoomError>>,
    },
    SendSystemMessage {
        channel_id: Uuid,
        sender_user_id: Uuid,
        body: Vec<u8>,
        response: oneshot::Sender<Result<ChatMessage, RoomError>>,
    },
    MediaReady {
        upload_id: Uuid,
        url: String,
    },
    Shutdown,
}

#[derive(Clone)]
pub struct RoomHandle {
    room_id: Uuid,
    commands: mpsc::Sender<RoomCommand>,
}

impl RoomHandle {
    pub fn spawn(room_id: Uuid, store: Arc<MessageStore>) -> Self {
        let (commands, receiver) = mpsc::channel(ROOM_MAILBOX_CAPACITY);
        let actor = RoomActor::new(room_id, store, receiver);
        tokio::spawn(actor.run());

        Self { room_id, commands }
    }

    pub fn room_id(&self) -> Uuid {
        self.room_id
    }

    pub async fn send_message(&self, message: OutboundMessage) -> Result<ChatMessage, RoomError> {
        let (response, receiver) = oneshot::channel();
        let command = RoomCommand::SendMessage { message, response };

        self.commands
            .send(command)
            .await
            .map_err(|_| RoomError::CommandChannelClosed)?;

        receiver
            .await
            .map_err(|_| RoomError::CommandChannelClosed)?
    }

    pub async fn send_system_message(
        &self,
        channel_id: Uuid,
        sender_user_id: Uuid,
        body: Vec<u8>,
    ) -> Result<ChatMessage, RoomError> {
        let (response, receiver) = oneshot::channel();
        let command = RoomCommand::SendSystemMessage {
            channel_id,
            sender_user_id,
            body,
            response,
        };

        self.commands
            .send(command)
            .await
            .map_err(|_| RoomError::CommandChannelClosed)?;

        receiver
            .await
            .map_err(|_| RoomError::CommandChannelClosed)?
    }

    pub async fn connect(
        &self,
        session_id: Uuid,
    ) -> Result<mpsc::Receiver<ServerEvent>, RoomError> {
        let (sender, receiver) = mpsc::channel(SESSION_MAILBOX_CAPACITY);

        self.commands
            .send(RoomCommand::Connect { session_id, sender })
            .await
            .map_err(|_| RoomError::CommandChannelClosed)?;

        Ok(receiver)
    }

    pub async fn disconnect(&self, session_id: Uuid) -> Result<(), RoomError> {
        self.commands
            .send(RoomCommand::Disconnect { session_id })
            .await
            .map_err(|_| RoomError::CommandChannelClosed)
    }
}

struct RoomActor {
    room_id: Uuid,
    store: Arc<MessageStore>,
    receiver: mpsc::Receiver<RoomCommand>,
    sessions: HashMap<Uuid, mpsc::Sender<ServerEvent>>,
    active_buffer: VecDeque<ChatMessage>,
    active_client_message_ids: HashMap<Uuid, ChatMessage>,
}

impl RoomActor {
    fn new(room_id: Uuid, store: Arc<MessageStore>, receiver: mpsc::Receiver<RoomCommand>) -> Self {
        Self {
            room_id,
            store,
            receiver,
            sessions: HashMap::new(),
            active_buffer: VecDeque::with_capacity(ACTIVE_BUFFER_LIMIT),
            active_client_message_ids: HashMap::new(),
        }
    }

    async fn run(mut self) {
        while let Some(command) = self.receiver.recv().await {
            let should_continue = self.handle_command(command).await;
            if !should_continue {
                break;
            }
        }
    }

    async fn handle_command(&mut self, command: RoomCommand) -> bool {
        match command {
            RoomCommand::Connect { session_id, sender } => {
                self.sessions.insert(session_id, sender);
                true
            }
            RoomCommand::Disconnect { session_id } => {
                self.sessions.remove(&session_id);
                true
            }
            RoomCommand::SendMessage { message, response } => {
                let result = self.admit_message(message).await;
                let _response_was_dropped = response.send(result).is_err();
                true
            }
            RoomCommand::SendSystemMessage {
                channel_id,
                sender_user_id,
                body,
                response,
            } => {
                let result = self
                    .admit_system_message(channel_id, sender_user_id, body)
                    .await;
                let _response_was_dropped = response.send(result).is_err();
                true
            }
            RoomCommand::MediaReady { upload_id, url } => {
                let event = ServerEvent::MediaReady {
                    room_id: self.room_id,
                    upload_id,
                    url,
                };
                self.broadcast(event).await;
                true
            }
            RoomCommand::Shutdown => false,
        }
    }

    async fn admit_message(&mut self, outbound: OutboundMessage) -> Result<ChatMessage, RoomError> {
        if outbound.room_id != self.room_id {
            return Err(RoomError::InvalidRequest(format!(
                "message room_id {} does not match target room {}",
                outbound.room_id, self.room_id
            )));
        }

        if let Some(client_message_id) = outbound.client_message_id {
            if let Some(original) = self
                .active_client_message_ids
                .get(&client_message_id)
                .cloned()
            {
                return Ok(original);
            }

            if let Some(original) = self
                .store
                .message_by_client_id(self.room_id, outbound.channel_id, client_message_id)
                .await?
            {
                self.push_active(original.clone());
                return Ok(original);
            }
        }

        validate_outbound_message(&outbound)?;

        let message = ChatMessage {
            room_id: self.room_id,
            channel_id: outbound.channel_id,
            message_id: Uuid::now_v7(),
            sender_user_id: outbound.sender_user_id,
            kind: outbound.kind,
            body: outbound.body,
            client_message_id: outbound.client_message_id,
            created_at: Utc::now(),
            media_upload_id: outbound.media_upload_id,
        };

        self.store.append_message(&message).await?;

        self.push_active(message.clone());
        self.broadcast(ServerEvent::MessageCreated {
            message: message.clone(),
        })
        .await;

        Ok(message)
    }

    async fn admit_system_message(
        &mut self,
        channel_id: Uuid,
        sender_user_id: Uuid,
        body: Vec<u8>,
    ) -> Result<ChatMessage, RoomError> {
        validate_text_body(&body)?;

        let message = ChatMessage {
            room_id: self.room_id,
            channel_id,
            message_id: Uuid::now_v7(),
            sender_user_id,
            kind: MessageKind::System,
            body,
            client_message_id: None,
            created_at: Utc::now(),
            media_upload_id: None,
        };

        self.store.append_message(&message).await?;
        self.push_active(message.clone());
        self.broadcast(ServerEvent::MessageCreated {
            message: message.clone(),
        })
        .await;

        Ok(message)
    }

    fn push_active(&mut self, message: ChatMessage) {
        if self.active_buffer.len() >= ACTIVE_BUFFER_LIMIT {
            if let Some(dropped) = self.active_buffer.pop_front() {
                if let Some(client_message_id) = dropped.client_message_id {
                    self.active_client_message_ids.remove(&client_message_id);
                }
            }
        }
        if let Some(client_message_id) = message.client_message_id {
            self.active_client_message_ids
                .insert(client_message_id, message.clone());
        }
        self.active_buffer.push_back(message);
    }

    async fn broadcast(&mut self, event: ServerEvent) {
        let mut stale_sessions = Vec::new();

        for (session_id, sender) in &self.sessions {
            if sender.send(event.clone()).await.is_err() {
                stale_sessions.push(*session_id);
            }
        }

        for session_id in stale_sessions {
            self.sessions.remove(&session_id);
        }
    }
}

fn validate_outbound_message(outbound: &OutboundMessage) -> Result<(), RoomError> {
    match &outbound.kind {
        MessageKind::Text => validate_text_body(&outbound.body),
        MessageKind::Image | MessageKind::Video => validate_inline_media_body(&outbound.body),
        MessageKind::System => Err(RoomError::InvalidRequest(
            "system messages cannot be sent by normal clients".to_owned(),
        )),
    }
}

fn validate_text_body(body: &[u8]) -> Result<(), RoomError> {
    if body.len() > MAX_TEXT_BODY_BYTES {
        return Err(RoomError::InvalidRequest(format!(
            "text body is limited to {MAX_TEXT_BODY_BYTES} bytes"
        )));
    }

    let text = std::str::from_utf8(body)
        .map_err(|error| RoomError::InvalidRequest(format!("text body must be UTF-8: {error}")))?;
    if text.trim().is_empty() {
        return Err(RoomError::InvalidRequest(
            "text body must not be empty".to_owned(),
        ));
    }

    Ok(())
}

fn validate_inline_media_body(body: &[u8]) -> Result<(), RoomError> {
    if body.len() > MAX_INLINE_MEDIA_BODY_BYTES {
        return Err(RoomError::InvalidRequest(format!(
            "inline image/video websocket bodies are limited to {MAX_INLINE_MEDIA_BODY_BYTES} bytes; use the upload flow for larger media"
        )));
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::Utc;

    fn outbound(kind: MessageKind, body: Vec<u8>) -> OutboundMessage {
        OutboundMessage {
            room_id: Uuid::new_v4(),
            channel_id: Uuid::new_v4(),
            sender_user_id: Uuid::new_v4(),
            kind,
            body,
            client_message_id: None,
            media_upload_id: None,
        }
    }

    fn chat_message(client_message_id: Option<Uuid>) -> ChatMessage {
        ChatMessage {
            room_id: Uuid::new_v4(),
            channel_id: Uuid::new_v4(),
            message_id: Uuid::new_v4(),
            sender_user_id: Uuid::new_v4(),
            kind: MessageKind::Text,
            body: b"hello".to_vec(),
            client_message_id,
            created_at: Utc::now(),
            media_upload_id: None,
        }
    }

    #[test]
    fn validation_rejects_blank_text() {
        let message = outbound(MessageKind::Text, b" \n\t ".to_vec());

        let error = validate_outbound_message(&message).unwrap_err();

        assert!(error.to_string().contains("text body must not be empty"));
    }

    #[test]
    fn validation_rejects_oversized_text() {
        let message = outbound(MessageKind::Text, vec![b'a'; MAX_TEXT_BODY_BYTES + 1]);

        let error = validate_outbound_message(&message).unwrap_err();

        assert!(error.to_string().contains("text body is limited"));
    }

    #[test]
    fn validation_rejects_oversized_inline_media() {
        let message = outbound(
            MessageKind::Image,
            vec![0_u8; MAX_INLINE_MEDIA_BODY_BYTES + 1],
        );

        let error = validate_outbound_message(&message).unwrap_err();

        assert!(error.to_string().contains("use the upload flow"));
    }

    #[test]
    fn validation_rejects_client_system_message() {
        let message = outbound(MessageKind::System, b"server-only".to_vec());

        let error = validate_outbound_message(&message).unwrap_err();

        assert!(error.to_string().contains("system messages cannot be sent"));
    }

    #[test]
    fn active_buffer_indexes_and_evicts_client_message_ids() {
        let room_id = Uuid::new_v4();
        let (_tx, rx) = mpsc::channel(1);
        let config = crate::config::AppConfig {
            bind_addr: "127.0.0.1:0".to_owned(),
            cors_allowed_origins: Vec::new(),
            database_path: ":memory:".into(),
            object_storage_base_url: "https://uploads.example.invalid".to_owned(),
        };
        let store = Arc::new(
            tokio::runtime::Builder::new_current_thread()
                .build()
                .expect("test runtime should build")
                .block_on(MessageStore::connect(&config))
                .expect("test MessageStore should initialize"),
        );
        let mut actor = RoomActor::new(room_id, store, rx);
        let first_client_id = Uuid::new_v4();

        actor.push_active(chat_message(Some(first_client_id)));
        for _ in 0..ACTIVE_BUFFER_LIMIT {
            actor.push_active(chat_message(Some(Uuid::new_v4())));
        }

        assert!(
            !actor
                .active_client_message_ids
                .contains_key(&first_client_id)
        );
        assert_eq!(actor.active_buffer.len(), ACTIVE_BUFFER_LIMIT);
        assert_eq!(actor.active_client_message_ids.len(), ACTIVE_BUFFER_LIMIT);
    }
}
