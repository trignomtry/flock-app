# Flock System Design

Flock is a privacy-centered, high-performance group messaging system designed as a faster and more reliable alternative to GroupMe. The core architectural rule is that every active room has a single in-memory authority, the `RoomActor`, which orders live events, broadcasts to connected clients, and asynchronously persists durable message state.

## Goals

- Ultra-low latency message fanout over WebSockets.
- Durable, chronologically ordered chat history in a local libsql/SQLite database.
- Ephemeral presence and typing state in Redis/Valkey only.
- Direct-to-object-storage media uploads with asynchronous processing.
- Offline-first KMP clients backed by SQLDelight and reactive UI flows.
- Privacy-preserving contact discovery with explicit user-level discoverability controls.
- Production Rust paths with no `unwrap`, `expect`, or `panic!`; all errors are modeled and handled.

## Non-Goals

- The main API gateway does not proxy media bytes.
- Typing indicators, live presence, and connection state are not persisted to disk.
- Contact discovery does not store uploaded address books.
- Room ordering is not delegated directly to database write completion.

## High-Level Architecture

```text
                         +-----------------------------+
                         |      KMP Mobile Client      |
                         | Compose UI + SQLDelight DB  |
                         +---------------+-------------+
                                         |
                         HTTPS + Protobuf|WebSocket
                                         |
+------------------------+---------------v------------------------+
|                         Actix API Gateway                       |
| Auth, REST, WebSocket upgrade, upload slot API, contact lookup  |
+------------------------+---------------+------------------------+
                                         |
                         room commands   |   session events
                                         |
+----------------------------------------v------------------------+
|                         Actor Engine                            |
| RoomRegistry -> RoomActor(room_id)                              |
| - active message buffer                                         |
| - connected websocket sessions                                  |
| - ordered fanout                                                |
| - async persistence dispatch                                    |
+-------------+--------------------------+------------------------+
              |                          |
              | async durable writes     | ephemeral state
              |                          |
      +-------v--------+         +-------v---------+
      | local libsql   |         |  Redis/Valkey   |
      | message log    |         | presence/typing |
      +----------------+         +-----------------+

      +----------------+         +----------------------------+
      | Postgres       |         | Object Storage: S3/R2      |
      | identity,      |         | direct client upload       |
      | aliases, ACLs  |         +-------------+--------------+
      +----------------+                       |
                                               | object event/job
                                  +------------v-------------+
                                  | Rust Media Worker        |
                                  | compress/transcode/scan  |
                                  +------------+-------------+
                                               |
                                  media ready command
                                               |
                                  +------------v-------------+
                                  | RoomActor broadcast      |
                                  +--------------------------+
```

## Backend Architecture

### Actix API Gateway

The gateway owns stateless request handling:

- Authentication and authorization.
- REST endpoints for room metadata, identity, alias registration, privacy settings, upload-slot allocation, and contact discovery.
- WebSocket upgrades and connection lifecycle integration with the actor engine.
- Presigned upload URL generation for S3-compatible object storage.

The gateway must not become the source of truth for room state. It validates requests and forwards accepted room commands into the actor engine.

### Actor Engine

`RoomRegistry` maps `room_id` to a live `RoomActor` handle. A room actor can be implemented with native Tokio tasks and `mpsc` channels or the `actix` actor runtime. The initial implementation should prefer Tokio channels unless Actix actor integration provides a concrete operational benefit.

Each `RoomActor` owns:

- `room_id`.
- Connected WebSocket session senders.
- A localized active message buffer for recent messages.
- Room sequence/order enforcement.
- Async persistence dispatch to local libsql/SQLite.
- Media completion notifications.

Message send flow:

```text
Client
  -> WebSocket SendMessage
  -> Actix session validates auth envelope
  -> RoomRegistry resolves RoomActor
  -> RoomActor assigns/validates UUIDv7 message_id
  -> RoomActor appends to active buffer
  -> RoomActor broadcasts to connected sessions
  -> RoomActor dispatches async local libsql write
  -> Persistence result updates metrics/retry path
```

The live room path optimizes user-perceived latency by broadcasting after actor admission rather than waiting for database acknowledgment. Durability failures are handled by retry queues and explicit operational alerts.

### Error Handling

Production Rust code must follow these rules:

- No `.unwrap()`.
- No `.expect()`.
- No `panic!` in request, actor, websocket, persistence, or media-processing paths.
- Use custom `thiserror` enums for domain errors.
- Use `anyhow::Context` only at application boundaries, bootstrap paths, worker supervisors, and CLI/binary entrypoints where rich diagnostic context is needed.
- Handle `Option` with pattern matching, `.get()`, `.ok_or(...)`, or explicit fallback behavior.
- Propagate typed errors through `Result<T, E>`.

Example error domains:

```rust
#[derive(Debug, thiserror::Error)]
pub enum RoomError {
    #[error("room command channel is closed")]
    CommandChannelClosed,

    #[error("websocket session is unavailable")]
    SessionUnavailable,

    #[error("message persistence failed: {0}")]
    Persistence(#[from] MessageStoreError),

    #[error("client is not authorized for room")]
    Unauthorized,
}
```

### Memory Strategy

Use arenas only in hot localized paths where allocation pressure is measurable:

- Protobuf decode/encode scratch space.
- Batch contact-discovery normalization.
- Message fanout envelope construction.
- Media metadata parsing.

`bumpalo` is preferred for short-lived request or batch arenas. Arena-backed data must not escape the arena lifetime into async tasks or persistent actor state.

## Media Pipeline

Media bytes bypass the API gateway.

```text
Client
  -> POST /v1/uploads/slots
  <- upload_id, object_key, presigned_put_url, headers, expires_at
  -> PUT bytes directly to S3/R2
  -> POST /v1/uploads/{upload_id}/complete
  -> Worker receives queue/object event
  -> Worker validates, compresses, transcodes, stores derivatives
  -> Worker marks media object ready
  -> Worker sends MediaReady command to RoomActor
  -> RoomActor broadcasts MessageMediaReady
```

Upload slot state belongs in Postgres because it is identity and workflow metadata. Large object bytes and processed derivatives belong in S3/R2. The worker should use bounded concurrency and backpressure so image/video processing cannot starve core chat traffic.

## Data Storage

### Local libsql Message Schema

The backend uses the Rust `libsql` crate with `Builder::new_local(...)`, which opens a local SQLite-compatible libsql database file. It does not connect to Turso Cloud or any remote libsql endpoint. Message rows use UUIDv7 strings for chronological identifiers and an index on `(room_id, created_at)` for room history reads.

```sql
CREATE TABLE IF NOT EXISTS messages (
  room_id TEXT NOT NULL,
  message_id TEXT PRIMARY KEY NOT NULL,
  sender_user_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  body BLOB NOT NULL,
  client_message_id TEXT,
  created_at TEXT NOT NULL,
  media_upload_id TEXT
);

CREATE INDEX IF NOT EXISTS idx_messages_room_created_at
  ON messages (room_id, created_at);

CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_room_client_message_id
  ON messages (room_id, client_message_id)
  WHERE client_message_id IS NOT NULL;
```

The database path is configured with `FLOCK_DATABASE_PATH` and defaults to `flock.db` in the process working directory. `:memory:` is supported for tests.

### Redis/Valkey Ephemeral Keys

Redis stores transient state only:

```text
presence:user:{user_id} -> online device/session summary, TTL 45s
presence:room:{room_id} -> set of online user_ids, TTL refreshed by heartbeat
typing:room:{room_id}:{user_id} -> typing payload, TTL 5s
ws:session:{session_id} -> user_id, room subscriptions, TTL heartbeat-bound
```

No Redis key is required for correctness after restart.

### Postgres Identity Schema

Postgres owns user identity, aliases, privacy settings, membership, and media workflow metadata.

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
  user_id uuid PRIMARY KEY,
  display_name text NOT NULL,
  avatar_url text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  disabled_at timestamptz
);

CREATE TABLE user_privacy_settings (
  user_id uuid PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
  discoverable_by_phone boolean NOT NULL DEFAULT false,
  discoverable_by_email boolean NOT NULL DEFAULT false,
  show_phone_in_groups boolean NOT NULL DEFAULT false,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_aliases (
  alias_id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
  alias_type text NOT NULL CHECK (alias_type IN ('username', 'phone', 'email')),
  alias_value text NOT NULL,
  alias_hash bytea,
  verified_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (alias_type, alias_value),
  UNIQUE (alias_type, alias_hash)
);

CREATE INDEX user_aliases_user_id_idx ON user_aliases(user_id);
CREATE INDEX user_aliases_hash_idx ON user_aliases(alias_type, alias_hash);

CREATE TABLE rooms (
  room_id uuid PRIMARY KEY,
  room_type text NOT NULL CHECK (room_type IN ('direct', 'group')),
  display_name text,
  created_by_user_id uuid NOT NULL REFERENCES users(user_id),
  created_at timestamptz NOT NULL DEFAULT now(),
  archived_at timestamptz
);

CREATE TABLE room_members (
  room_id uuid NOT NULL REFERENCES rooms(room_id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
  role text NOT NULL CHECK (role IN ('owner', 'admin', 'member')),
  joined_at timestamptz NOT NULL DEFAULT now(),
  left_at timestamptz,
  PRIMARY KEY (room_id, user_id)
);

CREATE TABLE media_uploads (
  upload_id uuid PRIMARY KEY,
  room_id uuid NOT NULL REFERENCES rooms(room_id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
  object_key text NOT NULL UNIQUE,
  original_content_type text NOT NULL,
  byte_size bigint,
  status text NOT NULL CHECK (
    status IN ('allocated', 'uploaded', 'processing', 'ready', 'failed')
  ),
  public_url text,
  derivative_manifest jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
```

Alias hashes are SHA-256 over canonicalized values. Phone numbers must be normalized to E.164 before hashing. Email addresses must be normalized consistently before hashing.

## Protobuf Schema

The client/backend wire format uses Protobuf for compact, fast serialization. WebSocket frames should carry length-delimited binary Protobuf messages.

```protobuf
syntax = "proto3";

package flock.v1;

message Uuid {
  bytes value = 1; // 16 bytes
}

message TimestampMillis {
  int64 value = 1;
}

enum MessageKind {
  MESSAGE_KIND_UNSPECIFIED = 0;
  MESSAGE_KIND_TEXT = 1;
  MESSAGE_KIND_IMAGE = 2;
  MESSAGE_KIND_VIDEO = 3;
  MESSAGE_KIND_SYSTEM = 4;
}

message ClientEnvelope {
  Uuid request_id = 1;
  oneof payload {
    SendMessage send_message = 2;
    JoinRoom join_room = 3;
    LeaveRoom leave_room = 4;
    Typing typing = 5;
    Ack ack = 6;
    Ping ping = 7;
  }
}

message ServerEnvelope {
  Uuid event_id = 1;
  TimestampMillis server_time = 2;
  oneof payload {
    MessageCreated message_created = 3;
    MessageUpdated message_updated = 4;
    MessageDeleted message_deleted = 5;
    PresenceChanged presence_changed = 6;
    Typing typing = 7;
    MediaReady media_ready = 8;
    ErrorEvent error = 9;
    Pong pong = 10;
  }
}

message SendMessage {
  Uuid room_id = 1;
  Uuid client_message_id = 2;
  MessageKind kind = 3;
  bytes body = 4;
  optional Uuid media_upload_id = 5;
  optional bytes reply_to_message_id = 6; // UUIDv7/TimeUUID bytes
}

message MessageCreated {
  Uuid room_id = 1;
  bytes message_id = 2;
  Uuid sender_user_id = 3;
  MessageKind kind = 4;
  bytes body = 5;
  TimestampMillis created_at = 6;
  optional Uuid client_message_id = 7;
  optional Uuid media_upload_id = 8;
}

message MessageUpdated {
  Uuid room_id = 1;
  bytes message_id = 2;
  bytes body = 3;
  TimestampMillis edited_at = 4;
}

message MessageDeleted {
  Uuid room_id = 1;
  bytes message_id = 2;
  TimestampMillis deleted_at = 3;
}

message MediaReady {
  Uuid room_id = 1;
  Uuid upload_id = 2;
  bytes message_id = 3;
  string public_url = 4;
  string manifest_json = 5;
}

message JoinRoom {
  Uuid room_id = 1;
}

message LeaveRoom {
  Uuid room_id = 1;
}

message Typing {
  Uuid room_id = 1;
  Uuid user_id = 2;
  bool is_typing = 3;
}

message Ack {
  Uuid room_id = 1;
  bytes message_id = 2;
}

message PresenceChanged {
  Uuid room_id = 1;
  Uuid user_id = 2;
  bool online = 3;
}

message Ping {
  int64 nonce = 1;
}

message Pong {
  int64 nonce = 1;
}

message ErrorEvent {
  string code = 1;
  string message = 2;
  bool retryable = 3;
}

message ContactDiscoveryRequest {
  repeated ContactHash contacts = 1;
}

message ContactHash {
  string alias_type = 1; // phone or email
  bytes sha256 = 2;
}

message ContactDiscoveryResponse {
  repeated ContactMatch matches = 1;
}

message ContactMatch {
  Uuid user_id = 1;
  string display_name = 2;
  optional string username = 3;
  repeated string matched_alias_types = 4;
}
```

## Contact Registration Flow

```text
User enters phone/email
  -> Client canonicalizes local candidate value
  -> Client requests verification challenge
  -> Backend sends SMS/email challenge
  -> User submits verification code
  -> Backend verifies challenge
  -> Backend hashes canonical alias with SHA-256
  -> Backend inserts user_aliases row
  -> Backend applies privacy settings
  -> Alias becomes searchable only if corresponding discoverability is enabled
```

Registration rules:

- `@username` is globally unique and searchable.
- Phone and email aliases require verification before matching.
- Hashing is performed over canonical values only.
- The server never trusts client-side normalization alone; it re-normalizes before storing.

## Privacy Flow

```text
Client submits hashed contacts
  -> Backend validates batch size and payload shape
  -> Backend queries phone/email aliases by hash
  -> Backend joins user privacy settings
  -> Backend filters:
       phone matches require discoverable_by_phone = true
       email matches require discoverable_by_email = true
  -> Backend returns user_id, display_name, username if available
  -> Backend does not persist submitted contact list
```

Group visibility rules:

```text
Render group member
  -> If viewer is the same user: show user's own verified aliases in settings UI
  -> Else if show_phone_in_groups = true and phone is verified: phone may be shown
  -> Else if username exists: show @username
  -> Else show display_name
```

The discovery endpoint should log aggregate metrics only, such as request count, match count, latency, and rejected batch count. It must not log raw hashes or canonical contact values.

## Client Sync Architecture

```text
WebSocket event arrives
  -> Decode Protobuf on shared KMP networking layer
  -> Persist message/event into SQLDelight transaction
  -> Reactive query emits updated room timeline
  -> Compose UI recomposes from local state
  -> Optional delivery/read ack is sent asynchronously
```

The UI never waits for a network round trip to display already received data. Outbound messages are inserted locally with a pending state, sent over WebSocket, and reconciled when `MessageCreated` returns with the authoritative `message_id`.

## KMP Client Components

- `network`: Ktor HTTP client and WebSocket session manager.
- `protocol`: generated Protobuf models and envelope encode/decode.
- `store`: SQLDelight schema, DAOs, and reactive flows.
- `sync`: reconnect, backoff, pending outbound queue, ack processing.
- `ui`: Compose Multiplatform screens bound to local reactive flows.

SQLDelight stores the durable local cache:

```sql
CREATE TABLE message_cache (
  room_id TEXT NOT NULL,
  message_id TEXT,
  client_message_id TEXT NOT NULL,
  sender_user_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  body BLOB NOT NULL,
  created_at INTEGER NOT NULL,
  edited_at INTEGER,
  deleted_at INTEGER,
  delivery_state TEXT NOT NULL,
  media_upload_id TEXT,
  media_url TEXT,
  PRIMARY KEY (room_id, client_message_id)
);

CREATE INDEX message_cache_room_created_idx
ON message_cache(room_id, created_at DESC);
```

## Operational Reliability

### Backpressure

- Bound all actor command channels.
- Bound all websocket session send queues.
- Drop or coalesce typing updates when clients fall behind.
- Do not drop admitted chat messages silently.
- Apply upload slot rate limits by user and room.

### Retry Strategy

- libsql write failures enter a bounded retry path with exponential backoff.
- Media worker failures mark uploads as `failed` and emit a user-visible failure event.
- WebSocket reconnect uses jittered backoff.
- Idempotency is based on `client_message_id` for outbound sends and `upload_id` for media.

### Observability

Metrics:

- WebSocket connection count.
- RoomActor mailbox depth.
- Message admission latency.
- Broadcast fanout latency.
- libsql write latency and failure rate.
- Contact discovery batch size, match count, and latency.
- Media processing queue depth and processing duration.

Tracing:

- `request_id`.
- `room_id`.
- `user_id`.
- `client_message_id`.
- `message_id`.
- `upload_id`.

Do not put raw phone numbers, emails, contact hashes, message bodies, or media URLs into logs unless explicitly scrubbed and approved for a diagnostic environment.

## Initial Implementation Plan

1. Create a Rust Cargo workspace with crates for `api`, `actor`, `store`, `protocol`, and `media-worker`.
2. Add shared error types using `thiserror`.
3. Implement local libsql initialization with safe config parsing.
4. Implement a Tokio-channel-backed `RoomActor`.
5. Add Actix WebSocket route that forwards commands into `RoomRegistry`.
6. Add Protobuf schema and generation pipeline.
7. Add KMP shared module with Ktor WebSocket client and SQLDelight message cache.
8. Add Postgres identity migrations.
9. Add contact discovery endpoint with batch limits and privacy filtering.
10. Add media upload slot endpoint and worker stub.
