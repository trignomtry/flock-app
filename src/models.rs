use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum MessageKind {
    Text,
    Image,
    Video,
    System,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct ChatMessage {
    pub room_id: Uuid,
    pub channel_id: Uuid,
    pub message_id: Uuid,
    pub sender_user_id: Uuid,
    pub kind: MessageKind,
    pub body: Vec<u8>,
    pub client_message_id: Option<Uuid>,
    pub created_at: DateTime<Utc>,
    pub media_upload_id: Option<Uuid>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ReceiptKind {
    Delivered,
    Read,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct MessageReceipt {
    pub message_id: Uuid,
    pub user_id: Uuid,
    pub receipt_kind: ReceiptKind,
    pub recorded_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct ChatMessageWithReceipts {
    pub room_id: Uuid,
    pub channel_id: Uuid,
    pub message_id: Uuid,
    pub sender_user_id: Uuid,
    pub kind: MessageKind,
    pub body: String,
    pub client_message_id: Option<Uuid>,
    pub created_at_ms: i64,
    pub media_upload_id: Option<Uuid>,
    pub receipt_state: Option<ReceiptKind>,
    pub receipts: Vec<MessageReceipt>,
}

impl ChatMessageWithReceipts {
    pub fn from_message(message: ChatMessage, receipts: Vec<MessageReceipt>) -> Self {
        let receipt_state = if receipts
            .iter()
            .any(|receipt| receipt.receipt_kind == ReceiptKind::Read)
        {
            Some(ReceiptKind::Read)
        } else if receipts
            .iter()
            .any(|receipt| receipt.receipt_kind == ReceiptKind::Delivered)
        {
            Some(ReceiptKind::Delivered)
        } else {
            None
        };
        let body = match message.kind {
            MessageKind::Text | MessageKind::System => {
                String::from_utf8_lossy(&message.body).into_owned()
            }
            MessageKind::Image | MessageKind::Video => {
                base64::Engine::encode(&base64::engine::general_purpose::STANDARD, &message.body)
            }
        };

        Self {
            room_id: message.room_id,
            channel_id: message.channel_id,
            message_id: message.message_id,
            sender_user_id: message.sender_user_id,
            kind: message.kind,
            body,
            client_message_id: message.client_message_id,
            created_at_ms: message.created_at.timestamp_millis(),
            media_upload_id: message.media_upload_id,
            receipt_state,
            receipts,
        }
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct MessageHistoryResponse {
    pub messages: Vec<ChatMessageWithReceipts>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct AckMessageRequest {
    pub user_id: Uuid,
    pub receipt_kind: ReceiptKind,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct AckMessageResponse {
    pub receipt: MessageReceipt,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct SendMessageRequest {
    pub room_id: Uuid,
    pub channel_id: Option<Uuid>,
    pub sender_user_id: Uuid,
    pub kind: MessageKind,
    pub body_base64: String,
    pub client_message_id: Option<Uuid>,
    pub media_upload_id: Option<Uuid>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct SendMessageResponse {
    pub message_id: Uuid,
    pub created_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct ContactDiscoveryRequest {
    pub viewer_user_id: Option<Uuid>,
    pub contacts: Vec<ContactHash>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct ContactHash {
    pub alias_type: AliasType,
    pub sha256_hex: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum AliasType {
    Phone,
    Email,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct ContactDiscoveryResponse {
    pub matches: Vec<ContactMatch>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct ContactMatch {
    pub user_id: Uuid,
    pub display_name: String,
    pub username: Option<String>,
    pub matched_alias_types: Vec<AliasType>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct UserProfile {
    pub user_id: Uuid,
    pub display_name: String,
    pub username: String,
    pub email: Option<String>,
    pub phone: Option<String>,
    pub discoverable_by_email: bool,
    pub discoverable_by_phone: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct RegisterUserRequest {
    pub user_id: Option<Uuid>,
    pub display_name: String,
    pub username: String,
    pub email: Option<String>,
    pub phone: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct UpdatePrivacyRequest {
    pub discoverable_by_email: bool,
    pub discoverable_by_phone: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct UserSearchResponse {
    pub users: Vec<UserProfile>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct FriendsResponse {
    pub friends: Vec<UserProfile>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum FriendRequestStatus {
    Pending,
    Accepted,
    Rejected,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct FriendRequestSummary {
    pub requester_user_id: Uuid,
    pub recipient_user_id: Uuid,
    pub status: FriendRequestStatus,
    pub requester: UserProfile,
    pub recipient: UserProfile,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct FriendRequestsResponse {
    pub incoming: Vec<FriendRequestSummary>,
    pub outgoing: Vec<FriendRequestSummary>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct FriendActionResponse {
    pub friend: UserProfile,
    pub request: FriendRequestSummary,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RoomType {
    Direct,
    Group,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct CreateRoomRequest {
    pub creator_user_id: Uuid,
    pub room_type: RoomType,
    pub name: Option<String>,
    pub member_user_ids: Vec<Uuid>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct UpdateRoomRequest {
    pub requester_user_id: Uuid,
    pub name: Option<String>,
    pub member_user_ids: Vec<Uuid>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct RoomSummary {
    pub room_id: Uuid,
    pub room_type: RoomType,
    pub name: Option<String>,
    pub members: Vec<UserProfile>,
    pub created_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct RoomsResponse {
    pub rooms: Vec<RoomSummary>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct ChannelSummary {
    pub channel_id: Uuid,
    pub room_id: Uuid,
    pub name: String,
    pub emoji: Option<String>,
    pub quiet: bool,
    pub is_default: bool,
    pub created_by_user_id: Uuid,
    pub created_at: DateTime<Utc>,
    pub is_member: bool,
    pub muted: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct ChannelsResponse {
    pub channels: Vec<ChannelSummary>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct CreateChannelRequest {
    pub creator_user_id: Uuid,
    pub name: String,
    pub emoji: Option<String>,
    pub quiet: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct UpdateChannelMembershipRequest {
    pub user_id: Uuid,
    pub joined: bool,
    pub muted: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct UploadSlotRequest {
    pub room_id: Uuid,
    pub user_id: Uuid,
    pub content_type: String,
    pub byte_size: Option<u64>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct UploadSlotResponse {
    pub upload_id: Uuid,
    pub object_key: String,
    pub presigned_put_url: String,
    pub expires_in_seconds: u64,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct HealthResponse {
    pub status: &'static str,
}
