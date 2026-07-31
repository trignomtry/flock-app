use std::{
    collections::{HashMap, HashSet},
    path::{Path, PathBuf},
    sync::Arc,
};

use crate::{
    config::AppConfig,
    errors::{DatabaseError, IdentityError},
    models::{
        AliasType, ChannelSummary, ChatMessage, ChatMessageWithReceipts, ContactHash, ContactMatch,
        CreateChannelRequest, CreateRoomRequest, FriendActionResponse, FriendRequestStatus,
        FriendRequestSummary, MessageKind, MessageReceipt, ReceiptKind, RegisterUserRequest,
        RoomSummary, RoomType, UpdateChannelMembershipRequest, UpdateRoomRequest, UserProfile,
    },
};
use chrono::{DateTime, Utc};
use libsql::{Builder, Row, params};
use sha2::{Digest, Sha256};
use tokio::sync::RwLock;
use uuid::Uuid;

pub const GENERAL_CHANNEL_ID: Uuid = Uuid::nil();

#[derive(Clone, Debug)]
pub struct MessageStoreConfig {
    pub database_path: PathBuf,
}

#[derive(Clone)]
pub struct MessageStore {
    config: Arc<MessageStoreConfig>,
    database: Arc<libsql::Database>,
}

impl std::fmt::Debug for MessageStore {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("MessageStore")
            .field("config", &self.config)
            .finish_non_exhaustive()
    }
}

impl MessageStore {
    pub async fn connect(config: &AppConfig) -> Result<Self, DatabaseError> {
        ensure_database_parent_exists(&config.database_path)?;

        let database = Builder::new_local(&config.database_path)
            .build()
            .await
            .map_err(|error| DatabaseError::OpenFailed(error.to_string()))?;
        let store = Self {
            config: Arc::new(MessageStoreConfig {
                database_path: config.database_path.clone(),
            }),
            database: Arc::new(database),
        };

        store.migrate().await?;
        Ok(store)
    }

    async fn migrate(&self) -> Result<(), DatabaseError> {
        let connection = self
            .database
            .connect()
            .map_err(|error| DatabaseError::OpenFailed(error.to_string()))?;

        connection
            .execute(
                "CREATE TABLE IF NOT EXISTS messages (
                    room_id TEXT NOT NULL,
                    channel_id TEXT NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
                    message_id TEXT PRIMARY KEY NOT NULL,
                    sender_user_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    body BLOB NOT NULL,
                    client_message_id TEXT,
                    created_at TEXT NOT NULL,
                    media_upload_id TEXT
                )",
                (),
            )
            .await
            .map_err(|error| DatabaseError::MigrationFailed(error.to_string()))?;

        if let Err(error) = connection
            .execute(
                "ALTER TABLE messages ADD COLUMN channel_id TEXT NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'",
                (),
            )
            .await
        {
            let message = error.to_string();
            if !message.to_lowercase().contains("duplicate column") {
                return Err(DatabaseError::MigrationFailed(message));
            }
        }

        connection
            .execute(
                "CREATE INDEX IF NOT EXISTS idx_messages_room_channel_created_at
                    ON messages (room_id, channel_id, created_at)",
                (),
            )
            .await
            .map_err(|error| DatabaseError::MigrationFailed(error.to_string()))?;

        connection
            .execute(
                "DROP INDEX IF EXISTS idx_messages_room_client_message_id",
                (),
            )
            .await
            .map_err(|error| DatabaseError::MigrationFailed(error.to_string()))?;

        connection
            .execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_room_channel_client_message_id
                    ON messages (room_id, channel_id, client_message_id)
                    WHERE client_message_id IS NOT NULL",
                (),
            )
            .await
            .map_err(|error| DatabaseError::MigrationFailed(error.to_string()))?;

        connection
            .execute(
                "CREATE TABLE IF NOT EXISTS message_receipts (
                    room_id TEXT NOT NULL,
                    message_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    receipt_kind TEXT NOT NULL,
                    recorded_at TEXT NOT NULL,
                    PRIMARY KEY (message_id, user_id, receipt_kind),
                    FOREIGN KEY (message_id) REFERENCES messages(message_id)
                )",
                (),
            )
            .await
            .map_err(|error| DatabaseError::MigrationFailed(error.to_string()))?;

        connection
            .execute(
                "CREATE INDEX IF NOT EXISTS idx_message_receipts_message_id
                    ON message_receipts (message_id)",
                (),
            )
            .await
            .map_err(|error| DatabaseError::MigrationFailed(error.to_string()))?;

        Ok(())
    }

    pub async fn append_message(&self, message: &ChatMessage) -> Result<(), DatabaseError> {
        let connection = self
            .database
            .connect()
            .map_err(|error| DatabaseError::WriteFailed(error.to_string()))?;

        connection
            .execute(
                "INSERT INTO messages (
                    room_id,
                    channel_id,
                    message_id,
                    sender_user_id,
                    kind,
                    body,
                    client_message_id,
                    created_at,
                    media_upload_id
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
                params![
                    message.room_id.to_string(),
                    message.channel_id.to_string(),
                    message.message_id.to_string(),
                    message.sender_user_id.to_string(),
                    message_kind_as_str(&message.kind),
                    message.body.clone(),
                    message.client_message_id.map(|id| id.to_string()),
                    message.created_at.to_rfc3339(),
                    message.media_upload_id.map(|id| id.to_string()),
                ],
            )
            .await
            .map_err(|error| DatabaseError::WriteFailed(error.to_string()))?;

        Ok(())
    }

    pub async fn message_by_client_id(
        &self,
        room_id: Uuid,
        channel_id: Uuid,
        client_message_id: Uuid,
    ) -> Result<Option<ChatMessage>, DatabaseError> {
        let connection = self
            .database
            .connect()
            .map_err(|error| DatabaseError::ReadFailed(error.to_string()))?;

        let mut rows = connection
            .query(
                "SELECT
                    room_id,
                    channel_id,
                    message_id,
                    sender_user_id,
                    kind,
                    body,
                    client_message_id,
                    created_at,
                    media_upload_id
                FROM messages
                WHERE room_id = ?1 AND channel_id = ?2 AND client_message_id = ?3
                LIMIT 1",
                params![
                    room_id.to_string(),
                    channel_id.to_string(),
                    client_message_id.to_string()
                ],
            )
            .await
            .map_err(|error| DatabaseError::ReadFailed(error.to_string()))?;

        rows.next()
            .await
            .map_err(|error| DatabaseError::ReadFailed(error.to_string()))?
            .map(|row| row_to_message(&row))
            .transpose()
    }

    pub async fn list_room_messages(
        &self,
        room_id: Uuid,
        channel_id: Uuid,
        after: Option<DateTime<Utc>>,
    ) -> Result<Vec<ChatMessageWithReceipts>, DatabaseError> {
        let connection = self
            .database
            .connect()
            .map_err(|error| DatabaseError::ReadFailed(error.to_string()))?;

        let after = after.map(|value| value.to_rfc3339());
        let mut rows = connection
            .query(
                "SELECT
                    room_id,
                    channel_id,
                    message_id,
                    sender_user_id,
                    kind,
                    body,
                    client_message_id,
                    created_at,
                    media_upload_id
                FROM messages
                WHERE room_id = ?1 AND channel_id = ?2 AND (?3 IS NULL OR created_at > ?3)
                ORDER BY created_at ASC, message_id ASC",
                params![room_id.to_string(), channel_id.to_string(), after],
            )
            .await
            .map_err(|error| DatabaseError::ReadFailed(error.to_string()))?;

        let mut messages = Vec::new();
        while let Some(row) = rows
            .next()
            .await
            .map_err(|error| DatabaseError::ReadFailed(error.to_string()))?
        {
            let message = row_to_message(&row)?;
            let receipts = self.receipts_for_message(message.message_id).await?;
            messages.push(ChatMessageWithReceipts::from_message(message, receipts));
        }

        Ok(messages)
    }

    pub async fn ack_message(
        &self,
        room_id: Uuid,
        message_id: Uuid,
        user_id: Uuid,
        receipt_kind: ReceiptKind,
    ) -> Result<MessageReceipt, DatabaseError> {
        let connection = self
            .database
            .connect()
            .map_err(|error| DatabaseError::WriteFailed(error.to_string()))?;

        let recorded_at = Utc::now();
        let rows_changed = connection
            .execute(
                "INSERT INTO message_receipts (
                    room_id,
                    message_id,
                    user_id,
                    receipt_kind,
                    recorded_at
                )
                SELECT ?1, message_id, ?3, ?4, ?5
                FROM messages
                WHERE room_id = ?1 AND message_id = ?2
                ON CONFLICT(message_id, user_id, receipt_kind)
                DO UPDATE SET recorded_at = excluded.recorded_at",
                params![
                    room_id.to_string(),
                    message_id.to_string(),
                    user_id.to_string(),
                    receipt_kind_as_str(&receipt_kind),
                    recorded_at.to_rfc3339(),
                ],
            )
            .await
            .map_err(|error| DatabaseError::WriteFailed(error.to_string()))?;

        if rows_changed == 0 {
            return Err(DatabaseError::NotFound(format!(
                "message {message_id} does not exist in room {room_id}"
            )));
        }

        Ok(MessageReceipt {
            message_id,
            user_id,
            receipt_kind,
            recorded_at,
        })
    }

    async fn receipts_for_message(
        &self,
        message_id: Uuid,
    ) -> Result<Vec<MessageReceipt>, DatabaseError> {
        let connection = self
            .database
            .connect()
            .map_err(|error| DatabaseError::ReadFailed(error.to_string()))?;

        let mut rows = connection
            .query(
                "SELECT message_id, user_id, receipt_kind, recorded_at
                FROM message_receipts
                WHERE message_id = ?1
                ORDER BY recorded_at ASC, user_id ASC, receipt_kind ASC",
                [message_id.to_string()],
            )
            .await
            .map_err(|error| DatabaseError::ReadFailed(error.to_string()))?;

        let mut receipts = Vec::new();
        while let Some(row) = rows
            .next()
            .await
            .map_err(|error| DatabaseError::ReadFailed(error.to_string()))?
        {
            receipts.push(row_to_receipt(&row)?);
        }

        Ok(receipts)
    }
}

fn ensure_database_parent_exists(path: &Path) -> Result<(), DatabaseError> {
    if path == Path::new(":memory:") {
        return Ok(());
    }

    if let Some(parent) = path.parent()
        && !parent.as_os_str().is_empty()
    {
        std::fs::create_dir_all(parent)
            .map_err(|error| DatabaseError::DirectoryCreateFailed(error.to_string()))?;
    }

    Ok(())
}

fn message_kind_as_str(kind: &MessageKind) -> &'static str {
    match kind {
        MessageKind::Text => "text",
        MessageKind::Image => "image",
        MessageKind::Video => "video",
        MessageKind::System => "system",
    }
}

fn message_kind_from_str(kind: &str) -> Result<MessageKind, DatabaseError> {
    match kind {
        "text" => Ok(MessageKind::Text),
        "image" => Ok(MessageKind::Image),
        "video" => Ok(MessageKind::Video),
        "system" => Ok(MessageKind::System),
        other => Err(DatabaseError::ReadFailed(format!(
            "unsupported message kind in store: {other}"
        ))),
    }
}

fn receipt_kind_as_str(kind: &ReceiptKind) -> &'static str {
    match kind {
        ReceiptKind::Delivered => "delivered",
        ReceiptKind::Read => "read",
    }
}

fn receipt_kind_from_str(kind: &str) -> Result<ReceiptKind, DatabaseError> {
    match kind {
        "delivered" => Ok(ReceiptKind::Delivered),
        "read" => Ok(ReceiptKind::Read),
        other => Err(DatabaseError::ReadFailed(format!(
            "unsupported receipt kind in store: {other}"
        ))),
    }
}

fn row_to_message(row: &Row) -> Result<ChatMessage, DatabaseError> {
    let room_id = parse_uuid(row.get::<String>(0)?, "room_id")?;
    let channel_id = parse_uuid(row.get::<String>(1)?, "channel_id")?;
    let message_id = parse_uuid(row.get::<String>(2)?, "message_id")?;
    let sender_user_id = parse_uuid(row.get::<String>(3)?, "sender_user_id")?;
    let kind = message_kind_from_str(&row.get::<String>(4)?)?;
    let body = row.get::<Vec<u8>>(5)?;
    let client_message_id = row
        .get::<Option<String>>(6)?
        .map(|value| parse_uuid(value, "client_message_id"))
        .transpose()?;
    let created_at = parse_datetime(row.get::<String>(7)?, "created_at")?;
    let media_upload_id = row
        .get::<Option<String>>(8)?
        .map(|value| parse_uuid(value, "media_upload_id"))
        .transpose()?;

    Ok(ChatMessage {
        room_id,
        channel_id,
        message_id,
        sender_user_id,
        kind,
        body,
        client_message_id,
        created_at,
        media_upload_id,
    })
}

fn row_to_receipt(row: &Row) -> Result<MessageReceipt, DatabaseError> {
    Ok(MessageReceipt {
        message_id: parse_uuid(row.get::<String>(0)?, "message_id")?,
        user_id: parse_uuid(row.get::<String>(1)?, "user_id")?,
        receipt_kind: receipt_kind_from_str(&row.get::<String>(2)?)?,
        recorded_at: parse_datetime(row.get::<String>(3)?, "recorded_at")?,
    })
}

fn parse_uuid(value: String, column: &str) -> Result<Uuid, DatabaseError> {
    Uuid::parse_str(&value)
        .map_err(|error| DatabaseError::ReadFailed(format!("{column} is invalid: {error}")))
}

fn parse_datetime(value: String, column: &str) -> Result<DateTime<Utc>, DatabaseError> {
    DateTime::parse_from_rfc3339(&value)
        .map(|value| value.with_timezone(&Utc))
        .map_err(|error| DatabaseError::ReadFailed(format!("{column} is invalid: {error}")))
}

#[derive(Clone, Debug, Default)]
pub struct IdentityStore {
    inner: Arc<RwLock<IdentityState>>,
}

#[derive(Clone, Debug, Default)]
struct IdentityState {
    users: HashMap<Uuid, StoredUser>,
    username_to_user_id: HashMap<String, Uuid>,
    friends: HashMap<Uuid, HashSet<Uuid>>,
    friend_requests: HashMap<(Uuid, Uuid), FriendRequestStatus>,
    contact_matches: HashMap<Uuid, HashSet<Uuid>>,
    rooms: HashMap<Uuid, StoredRoom>,
}

#[derive(Clone, Debug)]
struct StoredUser {
    user_id: Uuid,
    display_name: String,
    username: String,
    email: Option<String>,
    phone: Option<String>,
    discoverable_by_email: bool,
    discoverable_by_phone: bool,
}

#[derive(Clone, Debug)]
struct StoredRoom {
    room_id: Uuid,
    room_type: RoomType,
    name: Option<String>,
    member_user_ids: HashSet<Uuid>,
    channels: HashMap<Uuid, StoredChannel>,
    channel_memberships: HashMap<(Uuid, Uuid), StoredChannelMembership>,
    created_at: DateTime<Utc>,
}

#[derive(Clone, Debug)]
struct StoredChannel {
    channel_id: Uuid,
    room_id: Uuid,
    name: String,
    emoji: Option<String>,
    quiet: bool,
    is_default: bool,
    created_by_user_id: Uuid,
    created_at: DateTime<Utc>,
}

#[derive(Clone, Debug)]
struct StoredChannelMembership {
    joined: bool,
    muted: bool,
}

impl IdentityStore {
    pub async fn register_user(
        &self,
        request: RegisterUserRequest,
    ) -> Result<UserProfile, IdentityError> {
        let username = normalize_username(&request.username)?;
        let display_name = normalize_required_text("display_name", &request.display_name)?;
        let email = normalize_optional_email(request.email);
        let phone = normalize_optional_phone(request.phone);

        if email.is_none() && phone.is_none() {
            return Err(IdentityError::InvalidRequest(
                "email or phone is required".to_owned(),
            ));
        }

        let mut state = self.inner.write().await;
        if let Some(existing_user_id) = state.username_to_user_id.get(&username).copied() {
            if Some(existing_user_id) != request.user_id {
                return Err(IdentityError::Conflict(
                    "username is already taken".to_owned(),
                ));
            }
        }

        let user_id = request.user_id.unwrap_or_else(Uuid::now_v7);
        let existing = state.users.get(&user_id).cloned();
        if let Some(existing) = &existing {
            state.username_to_user_id.remove(&existing.username);
        }

        let user = StoredUser {
            user_id,
            display_name,
            username: username.clone(),
            email,
            phone,
            discoverable_by_email: existing
                .as_ref()
                .map(|user| user.discoverable_by_email)
                .unwrap_or(true),
            discoverable_by_phone: existing
                .as_ref()
                .map(|user| user.discoverable_by_phone)
                .unwrap_or(true),
        };

        state.username_to_user_id.insert(username, user_id);
        state.users.insert(user_id, user.clone());

        Ok(user.into_profile())
    }

    pub async fn update_privacy(
        &self,
        user_id: Uuid,
        discoverable_by_email: bool,
        discoverable_by_phone: bool,
    ) -> Result<UserProfile, IdentityError> {
        let mut state = self.inner.write().await;
        let user = state
            .users
            .get_mut(&user_id)
            .ok_or_else(|| IdentityError::NotFound("user does not exist".to_owned()))?;

        user.discoverable_by_email = discoverable_by_email;
        user.discoverable_by_phone = discoverable_by_phone;

        Ok(user.clone().into_profile())
    }

    pub async fn search_users(
        &self,
        viewer_user_id: Option<Uuid>,
        query: &str,
    ) -> Result<Vec<UserProfile>, IdentityError> {
        let query = query.trim();
        if query.is_empty() {
            return Err(IdentityError::InvalidRequest(
                "q must not be empty".to_owned(),
            ));
        }

        let normalized_query = query.to_lowercase();
        let normalized_username_query = normalized_query
            .strip_prefix('@')
            .unwrap_or(&normalized_query)
            .to_owned();
        let normalized_email_query = normalize_optional_email(Some(query.to_owned()));
        let normalized_phone_query = normalize_optional_phone(Some(query.to_owned()));

        let state = self.inner.read().await;
        if let Some(viewer_user_id) = viewer_user_id
            && !state.users.contains_key(&viewer_user_id)
        {
            return Err(IdentityError::NotFound(
                "viewer_user_id does not exist".to_owned(),
            ));
        }

        let mut users = state
            .users
            .values()
            .filter(|user| {
                user.username.contains(&normalized_username_query)
                    || user.display_name.to_lowercase().contains(&normalized_query)
                    || normalized_email_query.as_ref().is_some_and(|email| {
                        (viewer_user_id.is_none() || user.discoverable_by_email)
                            && user.email.as_ref() == Some(email)
                    })
                    || normalized_phone_query.as_ref().is_some_and(|phone| {
                        (viewer_user_id.is_none() || user.discoverable_by_phone)
                            && user.phone.as_ref() == Some(phone)
                    })
            })
            .cloned()
            .map(StoredUser::into_profile)
            .collect::<Vec<_>>();

        users.sort_by(|left, right| left.username.cmp(&right.username));
        Ok(users)
    }

    pub async fn add_friend(
        &self,
        user_id: Uuid,
        friend_user_id: Uuid,
    ) -> Result<FriendActionResponse, IdentityError> {
        if user_id == friend_user_id {
            return Err(IdentityError::InvalidRequest(
                "cannot add yourself as a friend".to_owned(),
            ));
        }

        let mut state = self.inner.write().await;
        if !state.users.contains_key(&user_id) {
            return Err(IdentityError::NotFound("user does not exist".to_owned()));
        }
        let friend =
            state.users.get(&friend_user_id).cloned().ok_or_else(|| {
                IdentityError::NotFound("friend_user_id does not exist".to_owned())
            })?;

        let should_accept = state
            .friend_requests
            .get(&(friend_user_id, user_id))
            .is_some_and(|status| *status == FriendRequestStatus::Pending)
            || mutual_contact_match(&state, user_id, friend_user_id)
            || already_friends(&state, user_id, friend_user_id);

        let status = if should_accept {
            add_friendship(&mut state, user_id, friend_user_id);
            FriendRequestStatus::Accepted
        } else {
            FriendRequestStatus::Pending
        };
        state
            .friend_requests
            .insert((user_id, friend_user_id), status.clone());
        if status == FriendRequestStatus::Accepted {
            state
                .friend_requests
                .insert((friend_user_id, user_id), FriendRequestStatus::Accepted);
        }

        let request = friend_request_summary(&state, user_id, friend_user_id, status)?;
        Ok(FriendActionResponse {
            friend: friend.into_profile(),
            request,
        })
    }

    pub async fn respond_to_friend_request(
        &self,
        user_id: Uuid,
        requester_user_id: Uuid,
        accept: bool,
    ) -> Result<FriendActionResponse, IdentityError> {
        if user_id == requester_user_id {
            return Err(IdentityError::InvalidRequest(
                "cannot respond to your own friend request".to_owned(),
            ));
        }

        let mut state = self.inner.write().await;
        if !state.users.contains_key(&user_id) {
            return Err(IdentityError::NotFound("user does not exist".to_owned()));
        }
        let requester = state
            .users
            .get(&requester_user_id)
            .cloned()
            .ok_or_else(|| {
                IdentityError::NotFound("requester_user_id does not exist".to_owned())
            })?;

        if !matches!(
            state.friend_requests.get(&(requester_user_id, user_id)),
            Some(FriendRequestStatus::Pending) | Some(FriendRequestStatus::Accepted)
        ) {
            return Err(IdentityError::NotFound(
                "friend request does not exist".to_owned(),
            ));
        }

        let status = if accept {
            add_friendship(&mut state, user_id, requester_user_id);
            FriendRequestStatus::Accepted
        } else {
            FriendRequestStatus::Rejected
        };
        state
            .friend_requests
            .insert((requester_user_id, user_id), status.clone());
        if accept {
            state
                .friend_requests
                .insert((user_id, requester_user_id), FriendRequestStatus::Accepted);
        }

        let request = friend_request_summary(&state, requester_user_id, user_id, status)?;
        Ok(FriendActionResponse {
            friend: requester.into_profile(),
            request,
        })
    }

    pub async fn friend_requests(
        &self,
        user_id: Uuid,
    ) -> Result<(Vec<FriendRequestSummary>, Vec<FriendRequestSummary>), IdentityError> {
        let state = self.inner.read().await;
        if !state.users.contains_key(&user_id) {
            return Err(IdentityError::NotFound("user does not exist".to_owned()));
        }

        let mut incoming = Vec::new();
        let mut outgoing = Vec::new();
        for ((requester_user_id, recipient_user_id), status) in &state.friend_requests {
            if *status != FriendRequestStatus::Pending {
                continue;
            }
            if *recipient_user_id == user_id {
                incoming.push(friend_request_summary(
                    &state,
                    *requester_user_id,
                    *recipient_user_id,
                    status.clone(),
                )?);
            } else if *requester_user_id == user_id {
                outgoing.push(friend_request_summary(
                    &state,
                    *requester_user_id,
                    *recipient_user_id,
                    status.clone(),
                )?);
            }
        }
        incoming.sort_by(|left, right| left.requester.username.cmp(&right.requester.username));
        outgoing.sort_by(|left, right| left.recipient.username.cmp(&right.recipient.username));
        Ok((incoming, outgoing))
    }

    pub async fn friends(&self, user_id: Uuid) -> Result<Vec<UserProfile>, IdentityError> {
        let state = self.inner.read().await;
        if !state.users.contains_key(&user_id) {
            return Err(IdentityError::NotFound("user does not exist".to_owned()));
        }

        let mut friends = state
            .friends
            .get(&user_id)
            .into_iter()
            .flat_map(|ids| ids.iter())
            .filter_map(|friend_id| state.users.get(friend_id))
            .cloned()
            .map(StoredUser::into_profile)
            .collect::<Vec<_>>();

        friends.sort_by(|left, right| left.username.cmp(&right.username));
        Ok(friends)
    }

    pub async fn create_room(
        &self,
        request: CreateRoomRequest,
    ) -> Result<RoomSummary, IdentityError> {
        let mut state = self.inner.write().await;
        if !state.users.contains_key(&request.creator_user_id) {
            return Err(IdentityError::NotFound(
                "creator_user_id does not exist".to_owned(),
            ));
        }

        let mut member_user_ids = request.member_user_ids.into_iter().collect::<HashSet<_>>();
        member_user_ids.insert(request.creator_user_id);

        for member_user_id in &member_user_ids {
            if !state.users.contains_key(member_user_id) {
                return Err(IdentityError::NotFound(format!(
                    "member_user_id {member_user_id} does not exist"
                )));
            }
        }

        if request.room_type == RoomType::Direct {
            if member_user_ids.len() != 2 {
                return Err(IdentityError::InvalidRequest(
                    "direct rooms require exactly one other member_user_id".to_owned(),
                ));
            }

            if let Some(existing) = state.rooms.values().find(|room| {
                room.room_type == RoomType::Direct && room.member_user_ids == member_user_ids
            }) {
                return room_summary(&state, existing);
            }
        }

        let room_id = Uuid::now_v7();
        let mut channels = HashMap::new();
        let general = StoredChannel {
            channel_id: GENERAL_CHANNEL_ID,
            room_id,
            name: "general".to_owned(),
            emoji: None,
            quiet: false,
            is_default: true,
            created_by_user_id: request.creator_user_id,
            created_at: Utc::now(),
        };
        channels.insert(general.channel_id, general);
        let mut channel_memberships = HashMap::new();
        for member_user_id in &member_user_ids {
            channel_memberships.insert(
                (GENERAL_CHANNEL_ID, *member_user_id),
                StoredChannelMembership {
                    joined: true,
                    muted: false,
                },
            );
        }

        let room = StoredRoom {
            room_id,
            room_type: request.room_type,
            name: request.name.and_then(|name| {
                let trimmed = name.trim().to_owned();
                (!trimmed.is_empty()).then_some(trimmed)
            }),
            member_user_ids,
            channels,
            channel_memberships,
            created_at: Utc::now(),
        };

        let summary = room_summary(&state, &room)?;
        state.rooms.insert(room.room_id, room);
        Ok(summary)
    }

    pub async fn rooms_for_user(&self, user_id: Uuid) -> Result<Vec<RoomSummary>, IdentityError> {
        let state = self.inner.read().await;
        if !state.users.contains_key(&user_id) {
            return Err(IdentityError::NotFound("user does not exist".to_owned()));
        }

        let mut rooms = state
            .rooms
            .values()
            .filter(|room| room.member_user_ids.contains(&user_id))
            .map(|room| room_summary(&state, room))
            .collect::<Result<Vec<_>, _>>()?;

        rooms.sort_by(|left, right| left.created_at.cmp(&right.created_at));
        Ok(rooms)
    }

    pub async fn update_room(
        &self,
        room_id: Uuid,
        request: UpdateRoomRequest,
    ) -> Result<RoomSummary, IdentityError> {
        let mut state = self.inner.write().await;
        if !state.users.contains_key(&request.requester_user_id) {
            return Err(IdentityError::NotFound(
                "requester_user_id does not exist".to_owned(),
            ));
        }

        let current_room = state
            .rooms
            .get(&room_id)
            .cloned()
            .ok_or_else(|| IdentityError::NotFound("room does not exist".to_owned()))?;

        if !current_room
            .member_user_ids
            .contains(&request.requester_user_id)
        {
            return Err(IdentityError::InvalidRequest(
                "requester_user_id is not a room member".to_owned(),
            ));
        }

        let mut member_user_ids = request.member_user_ids.into_iter().collect::<HashSet<_>>();
        member_user_ids.insert(request.requester_user_id);
        for member_user_id in &member_user_ids {
            if !state.users.contains_key(member_user_id) {
                return Err(IdentityError::NotFound(format!(
                    "member_user_id {member_user_id} does not exist"
                )));
            }
        }

        if current_room.room_type == RoomType::Direct
            && member_user_ids != current_room.member_user_ids
        {
            return Err(IdentityError::InvalidRequest(
                "direct room members cannot be changed".to_owned(),
            ));
        }

        let updated_room = StoredRoom {
            room_id,
            room_type: current_room.room_type.clone(),
            name: request.name.and_then(|name| {
                let trimmed = name.trim().to_owned();
                (!trimmed.is_empty()).then_some(trimmed)
            }),
            member_user_ids: member_user_ids.clone(),
            channels: current_room.channels.clone(),
            channel_memberships: updated_channel_memberships(&current_room, &member_user_ids),
            created_at: current_room.created_at,
        };

        let summary = room_summary(&state, &updated_room)?;
        state.rooms.insert(room_id, updated_room);
        Ok(summary)
    }

    pub async fn user_exists(&self, user_id: Uuid) -> bool {
        self.inner.read().await.users.contains_key(&user_id)
    }

    pub async fn room_membership(&self, room_id: Uuid, user_id: Uuid) -> Option<bool> {
        self.inner
            .read()
            .await
            .rooms
            .get(&room_id)
            .map(|room| room.member_user_ids.contains(&user_id))
    }

    pub async fn default_channel_id(&self, room_id: Uuid) -> Option<Uuid> {
        self.inner
            .read()
            .await
            .rooms
            .get(&room_id)
            .and_then(|room| {
                room.channels
                    .values()
                    .find(|channel| channel.is_default)
                    .map(|channel| channel.channel_id)
            })
    }

    pub async fn list_channels(
        &self,
        room_id: Uuid,
        user_id: Uuid,
    ) -> Result<Vec<ChannelSummary>, IdentityError> {
        let state = self.inner.read().await;
        let room = room_for_member(&state, room_id, user_id)?;
        let mut channels = room
            .channels
            .values()
            .map(|channel| channel_summary(room, channel, user_id))
            .collect::<Vec<_>>();
        channels.sort_by(|left, right| {
            left.is_default
                .cmp(&right.is_default)
                .reverse()
                .then_with(|| left.created_at.cmp(&right.created_at))
                .then_with(|| left.name.cmp(&right.name))
        });
        Ok(channels)
    }

    pub async fn create_channel(
        &self,
        room_id: Uuid,
        request: CreateChannelRequest,
    ) -> Result<ChannelSummary, IdentityError> {
        let mut state = self.inner.write().await;
        if !state.users.contains_key(&request.creator_user_id) {
            return Err(IdentityError::NotFound(
                "creator_user_id does not exist".to_owned(),
            ));
        }

        let room = state
            .rooms
            .get_mut(&room_id)
            .ok_or_else(|| IdentityError::NotFound("room does not exist".to_owned()))?;
        if !room.member_user_ids.contains(&request.creator_user_id) {
            return Err(IdentityError::InvalidRequest(
                "creator_user_id is not a room member".to_owned(),
            ));
        }

        let name = normalize_channel_name(&request.name)?;
        if room.channels.values().any(|channel| channel.name == name) {
            return Err(IdentityError::Conflict(
                "channel name already exists in room".to_owned(),
            ));
        }

        let channel = StoredChannel {
            channel_id: Uuid::now_v7(),
            room_id,
            name,
            emoji: normalize_channel_emoji(request.emoji),
            quiet: request.quiet,
            is_default: false,
            created_by_user_id: request.creator_user_id,
            created_at: Utc::now(),
        };

        if request.quiet {
            room.channel_memberships.insert(
                (channel.channel_id, request.creator_user_id),
                StoredChannelMembership {
                    joined: true,
                    muted: false,
                },
            );
        } else {
            for member_user_id in &room.member_user_ids {
                room.channel_memberships.insert(
                    (channel.channel_id, *member_user_id),
                    StoredChannelMembership {
                        joined: true,
                        muted: false,
                    },
                );
            }
        }

        let summary = channel_summary(room, &channel, request.creator_user_id);
        room.channels.insert(channel.channel_id, channel);
        Ok(summary)
    }

    pub async fn update_channel_membership(
        &self,
        room_id: Uuid,
        channel_id: Uuid,
        request: UpdateChannelMembershipRequest,
    ) -> Result<ChannelSummary, IdentityError> {
        let mut state = self.inner.write().await;
        let room = state
            .rooms
            .get_mut(&room_id)
            .ok_or_else(|| IdentityError::NotFound("room does not exist".to_owned()))?;
        if !room.member_user_ids.contains(&request.user_id) {
            return Err(IdentityError::InvalidRequest(
                "user_id is not a room member".to_owned(),
            ));
        }
        let channel = room
            .channels
            .get(&channel_id)
            .cloned()
            .ok_or_else(|| IdentityError::NotFound("channel does not exist".to_owned()))?;
        if channel.is_default && !request.joined {
            return Err(IdentityError::InvalidRequest(
                "default channel cannot be left".to_owned(),
            ));
        }

        room.channel_memberships.insert(
            (channel_id, request.user_id),
            StoredChannelMembership {
                joined: request.joined,
                muted: request.muted,
            },
        );

        Ok(channel_summary(room, &channel, request.user_id))
    }

    pub async fn channel_exists_for_room(&self, room_id: Uuid, channel_id: Uuid) -> Option<bool> {
        self.inner
            .read()
            .await
            .rooms
            .get(&room_id)
            .map(|room| room.channels.contains_key(&channel_id))
    }

    pub async fn joined_channel_membership(
        &self,
        room_id: Uuid,
        channel_id: Uuid,
        user_id: Uuid,
    ) -> Option<bool> {
        self.inner.read().await.rooms.get(&room_id).map(|room| {
            room.member_user_ids.contains(&user_id)
                && room
                    .channel_memberships
                    .get(&(channel_id, user_id))
                    .is_some_and(|membership| membership.joined)
        })
    }

    pub async fn channel_live_delivery_allowed(
        &self,
        room_id: Uuid,
        channel_id: Uuid,
        user_id: Uuid,
    ) -> Option<bool> {
        self.inner.read().await.rooms.get(&room_id).map(|room| {
            room.member_user_ids.contains(&user_id)
                && room
                    .channel_memberships
                    .get(&(channel_id, user_id))
                    .is_some_and(|membership| membership.joined && !membership.muted)
        })
    }

    pub async fn discover_contacts(
        &self,
        viewer_user_id: Option<Uuid>,
        contacts: &[ContactHash],
    ) -> Result<Vec<ContactMatch>, DatabaseError> {
        let mut state = self.inner.write().await;
        if let Some(viewer_user_id) = viewer_user_id {
            state.contact_matches.entry(viewer_user_id).or_default();
        }
        let mut matches_by_user_id: HashMap<Uuid, ContactMatch> = HashMap::new();

        let users = state.users.values().cloned().collect::<Vec<_>>();
        for contact in contacts {
            for user in &users {
                let matched = match contact.alias_type {
                    AliasType::Email => {
                        user.discoverable_by_email
                            && user
                                .email
                                .as_ref()
                                .is_some_and(|email| sha256_hex(email) == contact.sha256_hex)
                    }
                    AliasType::Phone => {
                        user.discoverable_by_phone
                            && user
                                .phone
                                .as_ref()
                                .is_some_and(|phone| sha256_hex(phone) == contact.sha256_hex)
                    }
                };

                if matched {
                    if let Some(viewer_user_id) = viewer_user_id
                        && viewer_user_id != user.user_id
                    {
                        state
                            .contact_matches
                            .entry(viewer_user_id)
                            .or_default()
                            .insert(user.user_id);
                    }
                    let entry =
                        matches_by_user_id
                            .entry(user.user_id)
                            .or_insert_with(|| ContactMatch {
                                user_id: user.user_id,
                                display_name: user.display_name.clone(),
                                username: Some(user.username.clone()),
                                matched_alias_types: Vec::new(),
                            });
                    entry.matched_alias_types.push(contact.alias_type.clone());
                }
            }
        }

        let mut matches = matches_by_user_id.into_values().collect::<Vec<_>>();
        matches.sort_by(|left, right| left.display_name.cmp(&right.display_name));
        Ok(matches)
    }
}

impl StoredUser {
    fn into_profile(self) -> UserProfile {
        UserProfile {
            user_id: self.user_id,
            display_name: self.display_name,
            username: self.username,
            email: self.email,
            phone: self.phone,
            discoverable_by_email: self.discoverable_by_email,
            discoverable_by_phone: self.discoverable_by_phone,
        }
    }
}

fn already_friends(state: &IdentityState, user_id: Uuid, friend_user_id: Uuid) -> bool {
    state
        .friends
        .get(&user_id)
        .is_some_and(|friends| friends.contains(&friend_user_id))
}

fn mutual_contact_match(state: &IdentityState, user_id: Uuid, friend_user_id: Uuid) -> bool {
    state
        .contact_matches
        .get(&user_id)
        .is_some_and(|matches| matches.contains(&friend_user_id))
        && state
            .contact_matches
            .get(&friend_user_id)
            .is_some_and(|matches| matches.contains(&user_id))
}

fn add_friendship(state: &mut IdentityState, user_id: Uuid, friend_user_id: Uuid) {
    state
        .friends
        .entry(user_id)
        .or_default()
        .insert(friend_user_id);
    state
        .friends
        .entry(friend_user_id)
        .or_default()
        .insert(user_id);
}

fn friend_request_summary(
    state: &IdentityState,
    requester_user_id: Uuid,
    recipient_user_id: Uuid,
    status: FriendRequestStatus,
) -> Result<FriendRequestSummary, IdentityError> {
    let requester = state
        .users
        .get(&requester_user_id)
        .cloned()
        .map(StoredUser::into_profile)
        .ok_or_else(|| IdentityError::NotFound("requester_user_id does not exist".to_owned()))?;
    let recipient = state
        .users
        .get(&recipient_user_id)
        .cloned()
        .map(StoredUser::into_profile)
        .ok_or_else(|| IdentityError::NotFound("recipient_user_id does not exist".to_owned()))?;
    Ok(FriendRequestSummary {
        requester_user_id,
        recipient_user_id,
        status,
        requester,
        recipient,
    })
}

fn room_summary(state: &IdentityState, room: &StoredRoom) -> Result<RoomSummary, IdentityError> {
    let mut members = room
        .member_user_ids
        .iter()
        .map(|user_id| {
            state
                .users
                .get(user_id)
                .cloned()
                .map(StoredUser::into_profile)
                .ok_or_else(|| IdentityError::NotFound(format!("member {user_id} does not exist")))
        })
        .collect::<Result<Vec<_>, _>>()?;

    members.sort_by(|left, right| left.username.cmp(&right.username));

    Ok(RoomSummary {
        room_id: room.room_id,
        room_type: room.room_type.clone(),
        name: room.name.clone(),
        members,
        created_at: room.created_at,
    })
}

fn room_for_member<'a>(
    state: &'a IdentityState,
    room_id: Uuid,
    user_id: Uuid,
) -> Result<&'a StoredRoom, IdentityError> {
    if !state.users.contains_key(&user_id) {
        return Err(IdentityError::NotFound("user does not exist".to_owned()));
    }
    let room = state
        .rooms
        .get(&room_id)
        .ok_or_else(|| IdentityError::NotFound("room does not exist".to_owned()))?;
    if !room.member_user_ids.contains(&user_id) {
        return Err(IdentityError::InvalidRequest(
            "user_id is not a room member".to_owned(),
        ));
    }
    Ok(room)
}

fn channel_summary(room: &StoredRoom, channel: &StoredChannel, user_id: Uuid) -> ChannelSummary {
    let membership = room
        .channel_memberships
        .get(&(channel.channel_id, user_id))
        .cloned()
        .unwrap_or(StoredChannelMembership {
            joined: false,
            muted: false,
        });

    ChannelSummary {
        channel_id: channel.channel_id,
        room_id: channel.room_id,
        name: channel.name.clone(),
        emoji: channel.emoji.clone(),
        quiet: channel.quiet,
        is_default: channel.is_default,
        created_by_user_id: channel.created_by_user_id,
        created_at: channel.created_at,
        is_member: membership.joined,
        muted: membership.muted,
    }
}

fn updated_channel_memberships(
    room: &StoredRoom,
    member_user_ids: &HashSet<Uuid>,
) -> HashMap<(Uuid, Uuid), StoredChannelMembership> {
    let mut memberships = room
        .channel_memberships
        .iter()
        .filter(|((_, user_id), _)| member_user_ids.contains(user_id))
        .map(|(key, value)| (*key, value.clone()))
        .collect::<HashMap<_, _>>();

    for channel in room.channels.values().filter(|channel| channel.is_default) {
        for member_user_id in member_user_ids {
            memberships
                .entry((channel.channel_id, *member_user_id))
                .or_insert(StoredChannelMembership {
                    joined: true,
                    muted: false,
                });
        }
    }

    memberships
}

fn normalize_channel_name(name: &str) -> Result<String, IdentityError> {
    let normalized = name
        .trim()
        .trim_start_matches('#')
        .trim()
        .to_lowercase()
        .chars()
        .map(|char| {
            if char.is_ascii_alphanumeric() || char == '-' || char == '_' {
                char
            } else if char.is_whitespace() {
                '-'
            } else {
                '\0'
            }
        })
        .filter(|char| *char != '\0')
        .collect::<String>();

    let normalized = normalized.trim_matches('-').to_owned();
    if normalized.is_empty() {
        return Err(IdentityError::InvalidRequest(
            "channel name must not be empty".to_owned(),
        ));
    }
    if normalized.len() > 64 {
        return Err(IdentityError::InvalidRequest(
            "channel name is limited to 64 characters".to_owned(),
        ));
    }
    if normalized == "general" {
        return Err(IdentityError::Conflict(
            "channel name already exists in room".to_owned(),
        ));
    }

    Ok(normalized)
}

fn normalize_channel_emoji(emoji: Option<String>) -> Option<String> {
    emoji.and_then(|emoji| {
        let trimmed = emoji.trim().to_owned();
        (!trimmed.is_empty()).then_some(trimmed)
    })
}

fn normalize_username(username: &str) -> Result<String, IdentityError> {
    let normalized = username
        .trim()
        .strip_prefix('@')
        .unwrap_or_else(|| username.trim())
        .trim()
        .to_lowercase();

    if normalized.is_empty() {
        return Err(IdentityError::InvalidRequest(
            "username must not be empty".to_owned(),
        ));
    }

    Ok(normalized)
}

fn normalize_required_text(field_name: &str, value: &str) -> Result<String, IdentityError> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return Err(IdentityError::InvalidRequest(format!(
            "{field_name} must not be empty"
        )));
    }
    Ok(trimmed.to_owned())
}

fn normalize_optional_email(email: Option<String>) -> Option<String> {
    email.and_then(|email| {
        let normalized = email.trim().to_lowercase();
        (!normalized.is_empty()).then_some(normalized)
    })
}

fn normalize_optional_phone(phone: Option<String>) -> Option<String> {
    phone.and_then(|phone| {
        let normalized = phone
            .trim()
            .chars()
            .filter(|char| char.is_ascii_digit() || *char == '+')
            .collect::<String>();
        (!normalized.is_empty()).then_some(normalized)
    })
}

fn sha256_hex(value: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(value.as_bytes());
    hex::encode(hasher.finalize())
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn test_message_store() -> MessageStore {
        let config = AppConfig {
            bind_addr: "127.0.0.1:0".to_owned(),
            cors_allowed_origins: Vec::new(),
            database_path: std::env::temp_dir().join(format!("flock-test-{}.db", Uuid::new_v4())),
            object_storage_base_url: "https://uploads.example.invalid".to_owned(),
            web_dist_path: "app/androidApp/build/dist/js/productionExecutable".into(),
        };

        MessageStore::connect(&config)
            .await
            .expect("message store should initialize")
    }

    fn chat_message(room_id: Uuid, body: &[u8], created_at: DateTime<Utc>) -> ChatMessage {
        ChatMessage {
            room_id,
            channel_id: GENERAL_CHANNEL_ID,
            message_id: Uuid::now_v7(),
            sender_user_id: Uuid::new_v4(),
            kind: MessageKind::Text,
            body: body.to_vec(),
            client_message_id: Some(Uuid::new_v4()),
            created_at,
            media_upload_id: None,
        }
    }

    fn register_request(username: &str) -> RegisterUserRequest {
        RegisterUserRequest {
            user_id: None,
            display_name: username.to_owned(),
            username: username.to_owned(),
            email: Some(format!("{username}@example.test")),
            phone: None,
        }
    }

    #[tokio::test]
    async fn message_store_appends_and_reads_ordered_history() {
        let store = test_message_store().await;
        let room_id = Uuid::new_v4();
        let first_created_at = DateTime::<Utc>::from_timestamp_millis(1_700_000_000_000).unwrap();
        let second_created_at = DateTime::<Utc>::from_timestamp_millis(1_700_000_001_000).unwrap();
        let first = chat_message(room_id, b"first", first_created_at);
        let second = chat_message(room_id, b"second", second_created_at);

        store.append_message(&second).await.unwrap();
        store.append_message(&first).await.unwrap();

        let all = store
            .list_room_messages(room_id, GENERAL_CHANNEL_ID, None)
            .await
            .unwrap();
        assert_eq!(all.len(), 2);
        assert_eq!(all[0].message_id, first.message_id);
        assert_eq!(all[1].message_id, second.message_id);

        let after_first = store
            .list_room_messages(room_id, GENERAL_CHANNEL_ID, Some(first.created_at))
            .await
            .unwrap();
        assert_eq!(after_first.len(), 1);
        assert_eq!(after_first[0].message_id, second.message_id);

        let by_client_id = store
            .message_by_client_id(
                room_id,
                GENERAL_CHANNEL_ID,
                first.client_message_id.unwrap(),
            )
            .await
            .unwrap()
            .expect("message should be found by client id");
        assert_eq!(by_client_id.message_id, first.message_id);
    }

    #[tokio::test]
    async fn message_store_records_and_returns_delivery_receipts() {
        let store = test_message_store().await;
        let room_id = Uuid::new_v4();
        let user_id = Uuid::new_v4();
        let message = chat_message(room_id, b"hello", Utc::now());
        store.append_message(&message).await.unwrap();

        let delivered = store
            .ack_message(room_id, message.message_id, user_id, ReceiptKind::Delivered)
            .await
            .unwrap();
        assert_eq!(delivered.receipt_kind, ReceiptKind::Delivered);

        let read = store
            .ack_message(room_id, message.message_id, user_id, ReceiptKind::Read)
            .await
            .unwrap();
        assert_eq!(read.receipt_kind, ReceiptKind::Read);

        let messages = store
            .list_room_messages(room_id, GENERAL_CHANNEL_ID, None)
            .await
            .unwrap();
        assert_eq!(messages.len(), 1);
        assert_eq!(messages[0].receipts.len(), 2);
        assert!(
            messages[0]
                .receipts
                .iter()
                .any(|receipt| receipt.receipt_kind == ReceiptKind::Delivered)
        );
        assert!(
            messages[0]
                .receipts
                .iter()
                .any(|receipt| receipt.receipt_kind == ReceiptKind::Read)
        );
    }

    #[tokio::test]
    async fn message_store_scopes_history_and_client_ids_by_channel() {
        let store = test_message_store().await;
        let room_id = Uuid::new_v4();
        let other_channel_id = Uuid::new_v4();
        let client_message_id = Uuid::new_v4();
        let mut general = chat_message(room_id, b"general", Utc::now());
        general.client_message_id = Some(client_message_id);
        let mut topic = chat_message(room_id, b"topic", Utc::now());
        topic.channel_id = other_channel_id;
        topic.client_message_id = Some(client_message_id);

        store.append_message(&general).await.unwrap();
        store.append_message(&topic).await.unwrap();

        let general_messages = store
            .list_room_messages(room_id, GENERAL_CHANNEL_ID, None)
            .await
            .unwrap();
        let topic_messages = store
            .list_room_messages(room_id, other_channel_id, None)
            .await
            .unwrap();

        assert_eq!(general_messages.len(), 1);
        assert_eq!(topic_messages.len(), 1);
        assert_eq!(
            store
                .message_by_client_id(room_id, GENERAL_CHANNEL_ID, client_message_id)
                .await
                .unwrap()
                .unwrap()
                .message_id,
            general.message_id
        );
        assert_eq!(
            store
                .message_by_client_id(room_id, other_channel_id, client_message_id)
                .await
                .unwrap()
                .unwrap()
                .message_id,
            topic.message_id
        );
    }

    #[tokio::test]
    async fn register_normalizes_and_enforces_unique_usernames() {
        let store = IdentityStore::default();
        let first = store
            .register_user(RegisterUserRequest {
                username: " @Ada ".to_owned(),
                ..register_request("Ada")
            })
            .await
            .expect("first user should register");

        assert_eq!(first.username, "ada");

        let error = store
            .register_user(RegisterUserRequest {
                username: "ada".to_owned(),
                ..register_request("Other")
            })
            .await
            .unwrap_err();

        assert!(matches!(error, IdentityError::Conflict(_)));
    }

    #[tokio::test]
    async fn search_does_not_create_users_and_respects_email_privacy() {
        let store = IdentityStore::default();
        let viewer = store
            .register_user(register_request("viewer"))
            .await
            .expect("viewer should register");
        let hidden = store
            .register_user(register_request("hidden"))
            .await
            .expect("hidden should register");

        store
            .update_privacy(hidden.user_id, false, true)
            .await
            .expect("privacy should update");

        let matches = store
            .search_users(Some(viewer.user_id), "hidden@example.test")
            .await
            .expect("search should succeed");

        assert!(matches.is_empty());
        assert_eq!(store.inner.read().await.users.len(), 2);
    }

    #[tokio::test]
    async fn friends_reject_nonexistent_and_self_add() {
        let store = IdentityStore::default();
        let user = store
            .register_user(register_request("user"))
            .await
            .expect("user should register");

        assert!(matches!(
            store.add_friend(user.user_id, user.user_id).await,
            Err(IdentityError::InvalidRequest(_))
        ));
        assert!(matches!(
            store.add_friend(user.user_id, Uuid::new_v4()).await,
            Err(IdentityError::NotFound(_))
        ));
    }

    #[tokio::test]
    async fn direct_rooms_require_known_single_other_and_are_reused() {
        let store = IdentityStore::default();
        let creator = store
            .register_user(register_request("creator"))
            .await
            .expect("creator should register");
        let friend = store
            .register_user(register_request("friend"))
            .await
            .expect("friend should register");

        let first = store
            .create_room(CreateRoomRequest {
                creator_user_id: creator.user_id,
                room_type: RoomType::Direct,
                name: None,
                member_user_ids: vec![friend.user_id],
            })
            .await
            .expect("direct room should create");
        let reused = store
            .create_room(CreateRoomRequest {
                creator_user_id: friend.user_id,
                room_type: RoomType::Direct,
                name: None,
                member_user_ids: vec![creator.user_id],
            })
            .await
            .expect("direct room should be reused");

        assert_eq!(first.room_id, reused.room_id);
        assert_eq!(
            store.rooms_for_user(creator.user_id).await.unwrap().len(),
            1
        );
        assert_eq!(
            store.room_membership(first.room_id, creator.user_id).await,
            Some(true)
        );
    }

    #[tokio::test]
    async fn rooms_have_general_channel_and_quiet_topics_are_opt_in() {
        let store = IdentityStore::default();
        let creator = store
            .register_user(register_request("creator"))
            .await
            .expect("creator should register");
        let friend = store
            .register_user(register_request("friend"))
            .await
            .expect("friend should register");

        let room = store
            .create_room(CreateRoomRequest {
                creator_user_id: creator.user_id,
                room_type: RoomType::Group,
                name: Some("Space".to_owned()),
                member_user_ids: vec![friend.user_id],
            })
            .await
            .expect("room should create");

        let creator_channels = store
            .list_channels(room.room_id, creator.user_id)
            .await
            .expect("channels should list");
        assert_eq!(creator_channels.len(), 1);
        assert_eq!(creator_channels[0].name, "general");
        assert!(creator_channels[0].is_default);
        assert!(creator_channels[0].is_member);

        let quiet = store
            .create_channel(
                room.room_id,
                CreateChannelRequest {
                    creator_user_id: creator.user_id,
                    name: "Wordle".to_owned(),
                    emoji: None,
                    quiet: true,
                },
            )
            .await
            .expect("quiet topic should create");
        assert_eq!(quiet.name, "wordle");
        assert!(quiet.is_member);

        let friend_channels = store
            .list_channels(room.room_id, friend.user_id)
            .await
            .expect("friend channels should list");
        let friend_quiet = friend_channels
            .iter()
            .find(|channel| channel.channel_id == quiet.channel_id)
            .expect("quiet topic should be visible");
        assert!(!friend_quiet.is_member);
        assert_eq!(
            store
                .joined_channel_membership(room.room_id, quiet.channel_id, friend.user_id)
                .await,
            Some(false)
        );

        let joined = store
            .update_channel_membership(
                room.room_id,
                quiet.channel_id,
                UpdateChannelMembershipRequest {
                    user_id: friend.user_id,
                    joined: true,
                    muted: true,
                },
            )
            .await
            .expect("friend should join and mute silently");
        assert!(joined.is_member);
        assert!(joined.muted);
    }
}
