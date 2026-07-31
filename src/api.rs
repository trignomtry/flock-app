use std::{collections::HashMap, sync::Arc};

use actix_cors::Cors;
use actix_web::http::header;
use actix_web::{App, HttpRequest, HttpResponse, HttpServer, Responder, get, post, put, web};
use base64::{Engine, engine::general_purpose::STANDARD as BASE64};
use chrono::{DateTime, Utc};
use futures_util::StreamExt;
use prost::Message;
use tokio::sync::{RwLock, mpsc};
use tracing_subscriber::EnvFilter;
use uuid::Uuid;

use crate::protocol::v1::client_envelope::Payload as ClientPayload;
use crate::protocol::v1::server_envelope::Payload as ServerPayload;
use crate::{
    actor::{OutboundMessage, RoomHandle},
    config::AppConfig,
    db::{GENERAL_CHANNEL_ID, IdentityStore, MessageStore},
    errors::AppError,
    media,
    models::{
        AckMessageRequest, AckMessageResponse, ChannelsResponse, ContactDiscoveryRequest,
        ContactDiscoveryResponse, CreateChannelRequest, CreateRoomRequest, FriendRequestsResponse,
        FriendsResponse, HealthResponse, MessageHistoryResponse, MessageKind as ModelMessageKind,
        ReceiptKind, RegisterUserRequest, RoomsResponse, SendMessageRequest, SendMessageResponse,
        UpdateChannelMembershipRequest, UpdatePrivacyRequest, UpdateRoomRequest, UploadSlotRequest,
        UserSearchResponse,
    },
    protocol::v1::{
        ClientEnvelope, ErrorEvent, MediaReady, MessageCreated, RoomCreated, ServerEnvelope,
        TimestampMillis,
    },
};

#[derive(Clone)]
pub struct AppState {
    config: AppConfig,
    messages: Arc<MessageStore>,
    identity: IdentityStore,
    rooms: Arc<RwLock<HashMap<Uuid, RoomHandle>>>,
    user_room_watchers: Arc<RwLock<HashMap<Uuid, Vec<mpsc::Sender<Uuid>>>>>,
}

impl AppState {
    pub async fn new(config: AppConfig) -> Result<Self, AppError> {
        let messages = MessageStore::connect(&config).await?;

        Ok(Self {
            config,
            messages: Arc::new(messages),
            identity: IdentityStore::default(),
            rooms: Arc::new(RwLock::new(HashMap::new())),
            user_room_watchers: Arc::new(RwLock::new(HashMap::new())),
        })
    }

    async fn room_handle(&self, room_id: Uuid) -> RoomHandle {
        {
            let rooms = self.rooms.read().await;
            if let Some(handle) = rooms.get(&room_id) {
                return handle.clone();
            }
        }

        let mut rooms = self.rooms.write().await;
        if let Some(handle) = rooms.get(&room_id) {
            return handle.clone();
        }

        let handle = RoomHandle::spawn(room_id, Arc::clone(&self.messages));
        rooms.insert(room_id, handle.clone());
        handle
    }

    async fn subscribe_to_room_created(&self, user_id: Uuid) -> mpsc::Receiver<Uuid> {
        let (tx, rx) = mpsc::channel(32);
        let mut watchers = self.user_room_watchers.write().await;
        watchers.entry(user_id).or_default().push(tx);
        rx
    }

    async fn notify_room_created(&self, room: &crate::models::RoomSummary) {
        let mut watchers = self.user_room_watchers.write().await;
        for member in &room.members {
            let Some(senders) = watchers.get_mut(&member.user_id) else {
                continue;
            };
            senders.retain(|sender| !sender.is_closed());
            for sender in senders.iter() {
                let _ = sender.try_send(room.room_id);
            }
        }
    }
}

pub async fn run() -> std::io::Result<()> {
    init_tracing();

    let config = AppConfig::from_env().map_err(to_io_error)?;
    let bind_addr = config.bind_addr.clone();
    let cors_allowed_origins = config.cors_allowed_origins.clone();
    let state = AppState::new(config).await.map_err(to_io_error)?;

    tracing::info!("starting Flock API on {}", bind_addr);

    HttpServer::new(move || {
        let allowed_origins = cors_allowed_origins.clone();
        let cors = Cors::default()
            .allowed_origin_fn(move |origin, _| {
                allowed_origins
                    .iter()
                    .any(|allowed| origin.as_bytes() == allowed.as_bytes())
            })
            .allowed_methods(["GET", "POST", "PUT", "OPTIONS"])
            .allowed_headers([header::AUTHORIZATION, header::ACCEPT, header::CONTENT_TYPE])
            .max_age(3600);

        App::new()
            .wrap(cors)
            .app_data(web::Data::new(state.clone()))
            .service(health)
            .service(register_user)
            .service(update_user_privacy)
            .service(search_users)
            .service(add_friend)
            .service(list_friend_requests)
            .service(accept_friend_request)
            .service(reject_friend_request)
            .service(list_friends)
            .service(create_room)
            .service(update_room)
            .service(list_user_rooms)
            .service(list_room_channels)
            .service(create_room_channel)
            .service(update_room_channel_membership)
            .service(list_room_messages)
            .service(ack_message)
            .service(send_message)
            .service(create_upload_slot)
            .service(discover_contacts)
            .service(ws_user)
            .service(ws_room)
    })
    .bind(bind_addr)?
    .run()
    .await
}

fn init_tracing() {
    let filter = match EnvFilter::try_from_default_env() {
        Ok(value) => value,
        Err(_) => EnvFilter::new("info"),
    };
    tracing_subscriber::fmt().with_env_filter(filter).init();
}

fn to_io_error(error: AppError) -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::Other, error.to_string())
}

#[get("/healthz")]
async fn health() -> impl Responder {
    HttpResponse::Ok().json(HealthResponse { status: "ok" })
}

#[derive(Debug, serde::Deserialize)]
struct MessageHistoryQuery {
    user_id: Uuid,
    channel_id: Option<Uuid>,
    after_ms: Option<i64>,
}

#[get("/v1/rooms/{room_id}/messages")]
async fn list_room_messages(
    state: web::Data<AppState>,
    path: web::Path<Uuid>,
    query: web::Query<MessageHistoryQuery>,
) -> Result<HttpResponse, AppError> {
    let room_id = path.into_inner();
    ensure_room_membership_if_known(&state, room_id, query.user_id).await?;
    let channel_id = channel_id_or_default(&state, room_id, query.channel_id).await?;
    ensure_channel_exists_if_known(&state, room_id, channel_id).await?;

    let after = query
        .after_ms
        .map(|after_ms| {
            DateTime::<Utc>::from_timestamp_millis(after_ms).ok_or_else(|| {
                AppError::InvalidRequest(format!(
                    "after_ms is outside the supported range: {after_ms}"
                ))
            })
        })
        .transpose()?;

    let messages = state
        .messages
        .list_room_messages(room_id, channel_id, after)
        .await?;
    Ok(HttpResponse::Ok().json(MessageHistoryResponse { messages }))
}

#[post("/v1/rooms/{room_id}/messages/{message_id}/ack")]
async fn ack_message(
    state: web::Data<AppState>,
    path: web::Path<(Uuid, Uuid)>,
    request: web::Json<AckMessageRequest>,
) -> Result<HttpResponse, AppError> {
    let (room_id, message_id) = path.into_inner();
    ensure_room_membership_if_known(&state, room_id, request.user_id).await?;

    let receipt = state
        .messages
        .ack_message(
            room_id,
            message_id,
            request.user_id,
            request.receipt_kind.clone(),
        )
        .await?;

    Ok(HttpResponse::Ok().json(AckMessageResponse { receipt }))
}

#[post("/v1/messages")]
async fn send_message(
    state: web::Data<AppState>,
    request: web::Json<SendMessageRequest>,
) -> Result<HttpResponse, AppError> {
    ensure_room_membership_if_known(&state, request.room_id, request.sender_user_id).await?;
    let channel_id = channel_id_or_default(&state, request.room_id, request.channel_id).await?;
    ensure_joined_channel_membership_if_known(
        &state,
        request.room_id,
        channel_id,
        request.sender_user_id,
    )
    .await?;

    let body = BASE64
        .decode(request.body_base64.as_bytes())
        .map_err(|error| AppError::InvalidRequest(format!("body_base64 is invalid: {error}")))?;

    let handle = state.room_handle(request.room_id).await;
    let message = handle
        .send_message(OutboundMessage {
            room_id: request.room_id,
            channel_id,
            sender_user_id: request.sender_user_id,
            kind: request.kind.clone(),
            body,
            client_message_id: request.client_message_id,
            media_upload_id: request.media_upload_id,
        })
        .await?;

    Ok(HttpResponse::Accepted().json(SendMessageResponse {
        message_id: message.message_id,
        created_at: message.created_at,
    }))
}

#[post("/v1/users/register")]
async fn register_user(
    state: web::Data<AppState>,
    request: web::Json<RegisterUserRequest>,
) -> Result<HttpResponse, AppError> {
    let profile = state.identity.register_user(request.into_inner()).await?;
    Ok(HttpResponse::Ok().json(profile))
}

#[put("/v1/users/{user_id}/privacy")]
async fn update_user_privacy(
    state: web::Data<AppState>,
    path: web::Path<Uuid>,
    request: web::Json<UpdatePrivacyRequest>,
) -> Result<HttpResponse, AppError> {
    let request = request.into_inner();
    let profile = state
        .identity
        .update_privacy(
            path.into_inner(),
            request.discoverable_by_email,
            request.discoverable_by_phone,
        )
        .await?;

    Ok(HttpResponse::Ok().json(profile))
}

#[derive(Debug, serde::Deserialize)]
struct SearchUsersQuery {
    q: String,
    viewer_user_id: Option<Uuid>,
}

#[get("/v1/users/search")]
async fn search_users(
    state: web::Data<AppState>,
    query: web::Query<SearchUsersQuery>,
) -> Result<HttpResponse, AppError> {
    let users = state
        .identity
        .search_users(query.viewer_user_id, &query.q)
        .await?;

    Ok(HttpResponse::Ok().json(UserSearchResponse { users }))
}

#[post("/v1/users/{user_id}/friends/{friend_user_id}")]
async fn add_friend(
    state: web::Data<AppState>,
    path: web::Path<(Uuid, Uuid)>,
) -> Result<HttpResponse, AppError> {
    let (user_id, friend_user_id) = path.into_inner();
    let response = state.identity.add_friend(user_id, friend_user_id).await?;
    Ok(HttpResponse::Ok().json(response))
}

#[get("/v1/users/{user_id}/friend-requests")]
async fn list_friend_requests(
    state: web::Data<AppState>,
    path: web::Path<Uuid>,
) -> Result<HttpResponse, AppError> {
    let (incoming, outgoing) = state.identity.friend_requests(path.into_inner()).await?;
    Ok(HttpResponse::Ok().json(FriendRequestsResponse { incoming, outgoing }))
}

#[post("/v1/users/{user_id}/friend-requests/{requester_user_id}/accept")]
async fn accept_friend_request(
    state: web::Data<AppState>,
    path: web::Path<(Uuid, Uuid)>,
) -> Result<HttpResponse, AppError> {
    let (user_id, requester_user_id) = path.into_inner();
    let response = state
        .identity
        .respond_to_friend_request(user_id, requester_user_id, true)
        .await?;
    Ok(HttpResponse::Ok().json(response))
}

#[post("/v1/users/{user_id}/friend-requests/{requester_user_id}/reject")]
async fn reject_friend_request(
    state: web::Data<AppState>,
    path: web::Path<(Uuid, Uuid)>,
) -> Result<HttpResponse, AppError> {
    let (user_id, requester_user_id) = path.into_inner();
    let response = state
        .identity
        .respond_to_friend_request(user_id, requester_user_id, false)
        .await?;
    Ok(HttpResponse::Ok().json(response))
}

#[get("/v1/users/{user_id}/friends")]
async fn list_friends(
    state: web::Data<AppState>,
    path: web::Path<Uuid>,
) -> Result<HttpResponse, AppError> {
    let friends = state.identity.friends(path.into_inner()).await?;
    Ok(HttpResponse::Ok().json(FriendsResponse { friends }))
}

#[post("/v1/rooms")]
async fn create_room(
    state: web::Data<AppState>,
    request: web::Json<CreateRoomRequest>,
) -> Result<HttpResponse, AppError> {
    let room = state.identity.create_room(request.into_inner()).await?;
    state.notify_room_created(&room).await;
    Ok(HttpResponse::Ok().json(room))
}

#[put("/v1/rooms/{room_id}")]
async fn update_room(
    state: web::Data<AppState>,
    path: web::Path<Uuid>,
    request: web::Json<UpdateRoomRequest>,
) -> Result<HttpResponse, AppError> {
    let room = state
        .identity
        .update_room(path.into_inner(), request.into_inner())
        .await?;
    state.notify_room_created(&room).await;
    Ok(HttpResponse::Ok().json(room))
}

#[get("/v1/users/{user_id}/rooms")]
async fn list_user_rooms(
    state: web::Data<AppState>,
    path: web::Path<Uuid>,
) -> Result<HttpResponse, AppError> {
    let rooms = state.identity.rooms_for_user(path.into_inner()).await?;
    Ok(HttpResponse::Ok().json(RoomsResponse { rooms }))
}

#[derive(Debug, serde::Deserialize)]
struct ChannelsQuery {
    user_id: Uuid,
}

#[get("/v1/rooms/{room_id}/channels")]
async fn list_room_channels(
    state: web::Data<AppState>,
    path: web::Path<Uuid>,
    query: web::Query<ChannelsQuery>,
) -> Result<HttpResponse, AppError> {
    let channels = state
        .identity
        .list_channels(path.into_inner(), query.user_id)
        .await?;
    Ok(HttpResponse::Ok().json(ChannelsResponse { channels }))
}

#[post("/v1/rooms/{room_id}/channels")]
async fn create_room_channel(
    state: web::Data<AppState>,
    path: web::Path<Uuid>,
    request: web::Json<CreateChannelRequest>,
) -> Result<HttpResponse, AppError> {
    let room_id = path.into_inner();
    let request = request.into_inner();
    let creator_user_id = request.creator_user_id;
    let channel = state.identity.create_channel(room_id, request).await?;
    let room = state
        .identity
        .rooms_for_user(creator_user_id)
        .await?
        .into_iter()
        .find(|room| room.room_id == room_id);
    if let Some(room) = room.as_ref() {
        state.notify_room_created(room).await;
    }
    if !channel.quiet {
        let body = format!("created a new topic: #{}", channel.name).into_bytes();
        let handle = state.room_handle(room_id).await;
        handle
            .send_system_message(GENERAL_CHANNEL_ID, creator_user_id, body)
            .await?;
    }
    Ok(HttpResponse::Ok().json(channel))
}

#[put("/v1/rooms/{room_id}/channels/{channel_id}/membership")]
async fn update_room_channel_membership(
    state: web::Data<AppState>,
    path: web::Path<(Uuid, Uuid)>,
    request: web::Json<UpdateChannelMembershipRequest>,
) -> Result<HttpResponse, AppError> {
    let (room_id, channel_id) = path.into_inner();
    let channel = state
        .identity
        .update_channel_membership(room_id, channel_id, request.into_inner())
        .await?;
    Ok(HttpResponse::Ok().json(channel))
}

#[post("/v1/uploads/slots")]
async fn create_upload_slot(
    state: web::Data<AppState>,
    request: web::Json<UploadSlotRequest>,
) -> Result<HttpResponse, AppError> {
    let response = media::create_upload_slot(&state.config, &request)?;
    Ok(HttpResponse::Ok().json(response))
}

#[post("/v1/contacts/discover")]
async fn discover_contacts(
    state: web::Data<AppState>,
    request: web::Json<ContactDiscoveryRequest>,
) -> Result<HttpResponse, AppError> {
    if request.contacts.len() > 2_000 {
        return Err(AppError::InvalidRequest(
            "contact discovery batch is limited to 2000 items".to_owned(),
        ));
    }

    for contact in &request.contacts {
        let decoded = hex::decode(&contact.sha256_hex)
            .map_err(|error| AppError::InvalidRequest(format!("sha256_hex is invalid: {error}")))?;
        if decoded.len() != 32 {
            return Err(AppError::InvalidRequest(
                "sha256_hex must decode to 32 bytes".to_owned(),
            ));
        }
    }

    let matches = state
        .identity
        .discover_contacts(request.viewer_user_id, &request.contacts)
        .await?;
    Ok(HttpResponse::Ok().json(ContactDiscoveryResponse { matches }))
}

fn to_proto_uuid(uuid: Uuid) -> crate::protocol::v1::Uuid {
    crate::protocol::v1::Uuid {
        value: uuid.into_bytes().to_vec(),
    }
}

fn to_uuid(proto_uuid: &crate::protocol::v1::Uuid) -> Result<Uuid, AppError> {
    if proto_uuid.value.len() != 16 {
        return Err(AppError::InvalidRequest("UUID must be 16 bytes".to_owned()));
    }
    let bytes: [u8; 16] = proto_uuid
        .value
        .as_slice()
        .try_into()
        .map_err(|_| AppError::InvalidRequest("UUID length mismatch".to_owned()))?;
    Ok(Uuid::from_bytes(bytes))
}

fn bytes_to_uuid(bytes: &[u8], field_name: &str) -> Result<Uuid, AppError> {
    if bytes.len() != 16 {
        return Err(AppError::InvalidRequest(format!(
            "{field_name} must be 16 bytes"
        )));
    }
    let bytes: [u8; 16] = bytes
        .try_into()
        .map_err(|_| AppError::InvalidRequest(format!("{field_name} length mismatch")))?;
    Ok(Uuid::from_bytes(bytes))
}

fn parse_client_message_kind(kind: i32) -> Result<ModelMessageKind, AppError> {
    match kind {
        1 => Ok(ModelMessageKind::Text),
        2 => Ok(ModelMessageKind::Image),
        3 => Ok(ModelMessageKind::Video),
        4 => Ok(ModelMessageKind::System),
        0 => Err(AppError::InvalidRequest(
            "message kind is unspecified".to_owned(),
        )),
        other => Err(AppError::InvalidRequest(format!(
            "unsupported message kind: {other}"
        ))),
    }
}

async fn handle_client_envelope(
    bin: &[u8],
    room_handle: &RoomHandle,
    session: &mut actix_ws::Session,
) -> Result<(), AppError> {
    let envelope = ClientEnvelope::decode(bin)
        .map_err(|e| AppError::InvalidRequest(format!("Failed to decode client envelope: {e}")))?;

    if let Some(payload) = envelope.payload {
        match payload {
            ClientPayload::SendMessage(send_msg) => {
                let room_id =
                    to_uuid(&send_msg.room_id.ok_or_else(|| {
                        AppError::InvalidRequest("room_id is missing".to_owned())
                    })?)?;
                let client_message_id = match send_msg.client_message_id {
                    Some(ref id) => Some(to_uuid(id)?),
                    None => None,
                };
                let channel_id = match send_msg.channel_id {
                    Some(ref id) => to_uuid(id)?,
                    None => crate::db::GENERAL_CHANNEL_ID,
                };
                let media_upload_id = match send_msg.media_upload_id {
                    Some(ref id) => Some(to_uuid(id)?),
                    None => None,
                };
                let kind = parse_client_message_kind(send_msg.kind)?;

                let outbound = OutboundMessage {
                    room_id,
                    channel_id,
                    sender_user_id: Uuid::new_v4(), // Resolved from JWT context in production
                    kind,
                    body: send_msg.body,
                    client_message_id,
                    media_upload_id,
                };

                room_handle.send_message(outbound).await?;
            }
            ClientPayload::Ping(ping) => {
                let pong_env = ServerEnvelope {
                    event_id: Some(to_proto_uuid(Uuid::new_v4())),
                    server_time: Some(TimestampMillis {
                        value: chrono::Utc::now().timestamp_millis(),
                    }),
                    payload: Some(ServerPayload::Pong(crate::protocol::v1::Pong {
                        nonce: ping.nonce,
                    })),
                };
                let mut buf = Vec::new();
                pong_env.encode(&mut buf).map_err(|e| {
                    AppError::InvalidRequest(format!("Failed to encode pong envelope: {e}"))
                })?;
                session
                    .binary(buf)
                    .await
                    .map_err(|_| AppError::Room(crate::errors::RoomError::SessionUnavailable))?;
            }
            _ => {
                // JoinRoom, LeaveRoom, Typing, Ack are stubbed or handled implicitly
            }
        }
    }
    Ok(())
}

async fn send_server_event(
    session: &mut actix_ws::Session,
    event: crate::actor::ServerEvent,
) -> Result<(), AppError> {
    let payload = match event {
        crate::actor::ServerEvent::MessageCreated { message } => {
            let kind = match message.kind {
                ModelMessageKind::Text => 1,
                ModelMessageKind::Image => 2,
                ModelMessageKind::Video => 3,
                ModelMessageKind::System => 4,
            };
            ServerPayload::MessageCreated(MessageCreated {
                room_id: Some(to_proto_uuid(message.room_id)),
                message_id: message.message_id.into_bytes().to_vec(),
                sender_user_id: Some(to_proto_uuid(message.sender_user_id)),
                kind,
                body: message.body,
                created_at: Some(TimestampMillis {
                    value: message.created_at.timestamp_millis(),
                }),
                client_message_id: message.client_message_id.map(to_proto_uuid),
                media_upload_id: message.media_upload_id.map(to_proto_uuid),
                channel_id: Some(to_proto_uuid(message.channel_id)),
            })
        }
        crate::actor::ServerEvent::MediaReady {
            room_id,
            upload_id,
            url,
        } => ServerPayload::MediaReady(MediaReady {
            room_id: Some(to_proto_uuid(room_id)),
            upload_id: Some(to_proto_uuid(upload_id)),
            message_id: Vec::new(),
            public_url: url,
            manifest_json: "{}".to_owned(),
        }),
    };

    let envelope = ServerEnvelope {
        event_id: Some(to_proto_uuid(Uuid::new_v4())),
        server_time: Some(TimestampMillis {
            value: chrono::Utc::now().timestamp_millis(),
        }),
        payload: Some(payload),
    };

    let mut buf = Vec::new();
    envelope
        .encode(&mut buf)
        .map_err(|e| AppError::InvalidRequest(format!("Failed to encode server event: {e}")))?;
    session
        .binary(buf)
        .await
        .map_err(|_| AppError::Room(crate::errors::RoomError::SessionUnavailable))?;

    Ok(())
}

async fn send_error_event(
    session: &mut actix_ws::Session,
    code: &str,
    message: String,
    retryable: bool,
) {
    let envelope = ServerEnvelope {
        event_id: Some(to_proto_uuid(Uuid::new_v4())),
        server_time: Some(TimestampMillis {
            value: chrono::Utc::now().timestamp_millis(),
        }),
        payload: Some(ServerPayload::Error(ErrorEvent {
            code: code.to_owned(),
            message,
            retryable,
        })),
    };

    let mut buf = Vec::new();
    if envelope.encode(&mut buf).is_ok() {
        let _ = session.binary(buf).await;
    }
}

async fn send_room_created_event(
    session: &mut actix_ws::Session,
    room_id: Uuid,
) -> Result<(), AppError> {
    let envelope = ServerEnvelope {
        event_id: Some(to_proto_uuid(Uuid::new_v4())),
        server_time: Some(TimestampMillis {
            value: chrono::Utc::now().timestamp_millis(),
        }),
        payload: Some(ServerPayload::RoomCreated(RoomCreated {
            room_id: Some(to_proto_uuid(room_id)),
        })),
    };

    let mut buf = Vec::new();
    envelope
        .encode(&mut buf)
        .map_err(|e| AppError::InvalidRequest(format!("Failed to encode room event: {e}")))?;
    session
        .binary(buf)
        .await
        .map_err(|_| AppError::Room(crate::errors::RoomError::SessionUnavailable))?;

    Ok(())
}

async fn join_user_room(
    state: &AppState,
    joined_rooms: &mut HashMap<Uuid, (RoomHandle, Uuid)>,
    event_tx: &mpsc::Sender<crate::actor::ServerEvent>,
    user_id: Uuid,
    room_id: Uuid,
) -> Result<(), AppError> {
    if joined_rooms.contains_key(&room_id) {
        return Ok(());
    }

    ensure_room_membership_if_known(state, room_id, user_id).await?;

    let room_handle = state.room_handle(room_id).await;
    let session_id = Uuid::new_v4();
    let mut room_rx = room_handle.connect(session_id).await?;
    let forward_tx = event_tx.clone();

    actix_web::rt::spawn(async move {
        while let Some(event) = room_rx.recv().await {
            if forward_tx.send(event).await.is_err() {
                break;
            }
        }
    });

    joined_rooms.insert(room_id, (room_handle, session_id));
    tracing::info!(%room_id, "user websocket joined room");
    Ok(())
}

async fn handle_user_client_envelope(
    bin: &[u8],
    user_id: Uuid,
    state: &AppState,
    joined_rooms: &mut HashMap<Uuid, (RoomHandle, Uuid)>,
    event_tx: &mpsc::Sender<crate::actor::ServerEvent>,
    session: &mut actix_ws::Session,
) -> Result<(), AppError> {
    let envelope = ClientEnvelope::decode(bin).map_err(|error| {
        AppError::InvalidRequest(format!("Failed to decode client envelope: {error}"))
    })?;

    let Some(payload) = envelope.payload else {
        return Ok(());
    };

    match payload {
        ClientPayload::JoinRoom(join_room) => {
            let room_id = to_uuid(
                &join_room
                    .room_id
                    .ok_or_else(|| AppError::InvalidRequest("room_id is missing".to_owned()))?,
            )?;
            join_user_room(state, joined_rooms, event_tx, user_id, room_id).await?;
        }
        ClientPayload::LeaveRoom(leave_room) => {
            let room_id = to_uuid(
                &leave_room
                    .room_id
                    .ok_or_else(|| AppError::InvalidRequest("room_id is missing".to_owned()))?,
            )?;
            if let Some((room_handle, session_id)) = joined_rooms.remove(&room_id) {
                room_handle.disconnect(session_id).await?;
            }
        }
        ClientPayload::SendMessage(send_msg) => {
            let room_id = to_uuid(
                &send_msg
                    .room_id
                    .ok_or_else(|| AppError::InvalidRequest("room_id is missing".to_owned()))?,
            )?;
            let channel_id = match send_msg.channel_id {
                Some(ref id) => to_uuid(id)?,
                None => channel_id_or_default(state, room_id, None).await?,
            };
            let room_handle = match joined_rooms.get(&room_id) {
                Some((handle, _)) => handle.clone(),
                None => {
                    send_error_event(
                        session,
                        "room_not_joined",
                        format!("join room {room_id} before sending messages to it"),
                        false,
                    )
                    .await;
                    return Ok(());
                }
            };
            ensure_room_membership_if_known(state, room_id, user_id).await?;
            ensure_joined_channel_membership_if_known(state, room_id, channel_id, user_id).await?;
            let client_message_id = match send_msg.client_message_id {
                Some(ref id) => Some(to_uuid(id)?),
                None => None,
            };
            let media_upload_id = match send_msg.media_upload_id {
                Some(ref id) => Some(to_uuid(id)?),
                None => None,
            };
            let kind = parse_client_message_kind(send_msg.kind)?;

            room_handle
                .send_message(OutboundMessage {
                    room_id,
                    channel_id,
                    sender_user_id: user_id,
                    kind,
                    body: send_msg.body,
                    client_message_id,
                    media_upload_id,
                })
                .await?;
        }
        ClientPayload::Ping(ping) => {
            let pong_env = ServerEnvelope {
                event_id: Some(to_proto_uuid(Uuid::new_v4())),
                server_time: Some(TimestampMillis {
                    value: chrono::Utc::now().timestamp_millis(),
                }),
                payload: Some(ServerPayload::Pong(crate::protocol::v1::Pong {
                    nonce: ping.nonce,
                })),
            };
            let mut buf = Vec::new();
            pong_env.encode(&mut buf).map_err(|error| {
                AppError::InvalidRequest(format!("Failed to encode pong envelope: {error}"))
            })?;
            session
                .binary(buf)
                .await
                .map_err(|_| AppError::Room(crate::errors::RoomError::SessionUnavailable))?;
        }
        ClientPayload::Typing(_) => {}
        ClientPayload::Ack(ack) => {
            let room_id = to_uuid(
                &ack.room_id
                    .ok_or_else(|| AppError::InvalidRequest("room_id is missing".to_owned()))?,
            )?;
            ensure_room_membership_if_known(state, room_id, user_id).await?;
            let message_id = bytes_to_uuid(&ack.message_id, "message_id")?;
            state
                .messages
                .ack_message(room_id, message_id, user_id, ReceiptKind::Delivered)
                .await?;
        }
    }

    Ok(())
}

async fn ensure_room_membership_if_known(
    state: &AppState,
    room_id: Uuid,
    user_id: Uuid,
) -> Result<(), AppError> {
    match state.identity.room_membership(room_id, user_id).await {
        Some(true) | None => Ok(()),
        Some(false) => Err(AppError::InvalidRequest(format!(
            "user {user_id} is not a member of room {room_id}"
        ))),
    }
}

async fn channel_id_or_default(
    state: &AppState,
    room_id: Uuid,
    channel_id: Option<Uuid>,
) -> Result<Uuid, AppError> {
    if let Some(channel_id) = channel_id {
        return Ok(channel_id);
    }

    Ok(state
        .identity
        .default_channel_id(room_id)
        .await
        .unwrap_or(crate::db::GENERAL_CHANNEL_ID))
}

async fn ensure_channel_exists_if_known(
    state: &AppState,
    room_id: Uuid,
    channel_id: Uuid,
) -> Result<(), AppError> {
    match state
        .identity
        .channel_exists_for_room(room_id, channel_id)
        .await
    {
        Some(true) | None => Ok(()),
        Some(false) => Err(AppError::InvalidRequest(format!(
            "channel {channel_id} does not exist in room {room_id}"
        ))),
    }
}

async fn ensure_joined_channel_membership_if_known(
    state: &AppState,
    room_id: Uuid,
    channel_id: Uuid,
    user_id: Uuid,
) -> Result<(), AppError> {
    match state
        .identity
        .joined_channel_membership(room_id, channel_id, user_id)
        .await
    {
        Some(true) | None => Ok(()),
        Some(false) => Err(AppError::InvalidRequest(format!(
            "user {user_id} has not joined channel {channel_id} in room {room_id}"
        ))),
    }
}

#[get("/v1/ws/users/{user_id}")]
async fn ws_user(
    req: HttpRequest,
    body: web::Payload,
    state: web::Data<AppState>,
    path: web::Path<Uuid>,
) -> Result<HttpResponse, actix_web::Error> {
    let user_id = path.into_inner();
    tracing::info!(%user_id, "user websocket connection requested");
    let (response, mut session, mut msg_stream) = actix_ws::handle(&req, body)?;
    let state = state.get_ref().clone();
    let (event_tx, mut event_rx) = mpsc::channel::<crate::actor::ServerEvent>(512);

    actix_web::rt::spawn(async move {
        let mut joined_rooms: HashMap<Uuid, (RoomHandle, Uuid)> = HashMap::new();
        let mut room_created_rx = state.subscribe_to_room_created(user_id).await;

        loop {
            tokio::select! {
                ws_msg = msg_stream.next() => {
                    match ws_msg {
                        Some(Ok(actix_ws::Message::Binary(bin))) => {
                            if let Err(error) = handle_user_client_envelope(
                                &bin,
                                user_id,
                                &state,
                                &mut joined_rooms,
                                &event_tx,
                                &mut session,
                            ).await {
                                tracing::error!(%user_id, "failed to handle user websocket envelope: {:?}", error);
                                send_error_event(&mut session, "invalid_envelope", error.to_string(), false).await;
                            }
                        }
                        Some(Ok(actix_ws::Message::Ping(bytes))) => {
                            let _ = session.pong(&bytes).await;
                        }
                        Some(Ok(actix_ws::Message::Close(reason))) => {
                            tracing::info!(%user_id, "user websocket closed: {:?}", reason);
                            break;
                        }
                        Some(Err(error)) => {
                            tracing::error!(%user_id, "user websocket error: {:?}", error);
                            break;
                        }
                        None => break,
                        _ => {}
                    }
                }
                room_event = event_rx.recv() => {
                    match room_event {
                        Some(event) => {
                            if !should_deliver_user_room_event(&state, user_id, &event).await {
                                continue;
                            }
                            if let Err(error) = send_server_event(&mut session, event).await {
                                tracing::error!(%user_id, "failed to send user websocket event: {:?}", error);
                                break;
                            }
                        }
                        None => break,
                    }
                }
                room_created = room_created_rx.recv() => {
                    match room_created {
                        Some(room_id) => {
                            if let Err(error) = send_room_created_event(&mut session, room_id).await {
                                tracing::error!(%user_id, "failed to send room-created websocket event: {:?}", error);
                                break;
                            }
                        }
                        None => break,
                    }
                }
            }
        }

        for (_room_id, (room_handle, session_id)) in joined_rooms {
            let _ = room_handle.disconnect(session_id).await;
        }
    });

    Ok(response)
}

async fn should_deliver_user_room_event(
    state: &AppState,
    user_id: Uuid,
    event: &crate::actor::ServerEvent,
) -> bool {
    match event {
        crate::actor::ServerEvent::MessageCreated { message } => state
            .identity
            .channel_live_delivery_allowed(message.room_id, message.channel_id, user_id)
            .await
            .unwrap_or(true),
        crate::actor::ServerEvent::MediaReady { .. } => true,
    }
}

#[get("/v1/ws/rooms/{room_id}")]
async fn ws_room(
    req: HttpRequest,
    body: web::Payload,
    state: web::Data<AppState>,
    path: web::Path<Uuid>,
) -> Result<HttpResponse, actix_web::Error> {
    let room_id = path.into_inner();
    tracing::info!(%room_id, "websocket connection requested");
    let (response, mut session, mut msg_stream) = actix_ws::handle(&req, body)?;

    let room_handle = state.room_handle(room_id).await;
    let session_id = Uuid::new_v4();

    let mut server_rx = match room_handle.connect(session_id).await {
        Ok(rx) => rx,
        Err(e) => return Ok(HttpResponse::InternalServerError().body(e.to_string())),
    };

    actix_web::rt::spawn(async move {
        loop {
            tokio::select! {
                ws_msg = msg_stream.next() => {
                    match ws_msg {
                        Some(Ok(actix_ws::Message::Binary(bin))) => {
                            if let Err(e) = handle_client_envelope(&bin, &room_handle, &mut session).await {
                                tracing::error!("Failed to handle client envelope: {:?}", e);
                                let err_env = ServerEnvelope {
                                    event_id: Some(to_proto_uuid(Uuid::new_v4())),
                                    server_time: Some(TimestampMillis {
                                        value: chrono::Utc::now().timestamp_millis()
                                    }),
                                    payload: Some(ServerPayload::Error(ErrorEvent {
                                        code: "invalid_envelope".to_owned(),
                                        message: e.to_string(),
                                        retryable: false,
                                    })),
                                };
                                let mut buf = Vec::new();
                                if err_env.encode(&mut buf).is_ok() {
                                    let _ = session.binary(buf).await;
                                }
                            }
                        }
                        Some(Ok(actix_ws::Message::Ping(bytes))) => {
                            let _ = session.pong(&bytes).await;
                        }
                        Some(Ok(actix_ws::Message::Close(reason))) => {
                            tracing::info!("WS connection closed: {:?}", reason);
                            break;
                        }
                        Some(Err(e)) => {
                            tracing::error!("WS error: {:?}", e);
                            break;
                        }
                        None => break,
                        _ => {}
                    }
                }
                actor_msg = server_rx.recv() => {
                    match actor_msg {
                        Some(event) => {
                            if let Err(e) = send_server_event(&mut session, event).await {
                                tracing::error!("Failed to send server event: {:?}", e);
                                break;
                            }
                        }
                        None => {
                            tracing::info!("Actor command channel closed");
                            break;
                        }
                    }
                }
            }
        }
        let _ = room_handle.disconnect(session_id).await;
    });

    Ok(response)
}
