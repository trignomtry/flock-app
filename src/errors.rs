use actix_web::{HttpResponse, ResponseError};

#[derive(Debug, thiserror::Error)]
pub enum AppError {
    #[error("configuration error: {0}")]
    Config(String),

    #[error("room actor error: {0}")]
    Room(#[from] RoomError),

    #[error("database error: {0}")]
    Database(#[from] DatabaseError),

    #[error("identity error: {0}")]
    Identity(#[from] IdentityError),

    #[error("media error: {0}")]
    Media(#[from] MediaError),

    #[error("invalid request: {0}")]
    InvalidRequest(String),
}

impl ResponseError for AppError {
    fn error_response(&self) -> HttpResponse {
        match self {
            Self::InvalidRequest(_) => HttpResponse::BadRequest().json(ErrorBody::from(self)),
            Self::Media(MediaError::UnsupportedContentType(_)) => {
                HttpResponse::BadRequest().json(ErrorBody::from(self))
            }
            Self::Room(RoomError::InvalidRequest(_)) => {
                HttpResponse::BadRequest().json(ErrorBody::from(self))
            }
            Self::Identity(IdentityError::InvalidRequest(_)) => {
                HttpResponse::BadRequest().json(ErrorBody::from(self))
            }
            Self::Identity(IdentityError::NotFound(_)) => {
                HttpResponse::NotFound().json(ErrorBody::from(self))
            }
            Self::Database(DatabaseError::NotFound(_)) => {
                HttpResponse::NotFound().json(ErrorBody::from(self))
            }
            Self::Identity(IdentityError::Conflict(_)) => {
                HttpResponse::Conflict().json(ErrorBody::from(self))
            }
            Self::Config(_) | Self::Room(_) | Self::Database(_) => {
                HttpResponse::InternalServerError().json(ErrorBody::from(self))
            }
        }
    }
}

#[derive(Debug, serde::Serialize)]
struct ErrorBody {
    code: &'static str,
    message: String,
}

impl From<&AppError> for ErrorBody {
    fn from(value: &AppError) -> Self {
        let code = match value {
            AppError::Config(_) => "config_error",
            AppError::Room(RoomError::InvalidRequest(_)) => "invalid_request",
            AppError::Room(_) => "room_error",
            AppError::Database(DatabaseError::NotFound(_)) => "not_found",
            AppError::Database(_) => "database_error",
            AppError::Identity(IdentityError::InvalidRequest(_)) => "invalid_request",
            AppError::Identity(IdentityError::NotFound(_)) => "not_found",
            AppError::Identity(IdentityError::Conflict(_)) => "conflict",
            AppError::Media(_) => "media_error",
            AppError::InvalidRequest(_) => "invalid_request",
        };
        let message = match value {
            AppError::InvalidRequest(_)
            | AppError::Room(RoomError::InvalidRequest(_))
            | AppError::Identity(_)
            | AppError::Database(DatabaseError::NotFound(_)) => value.to_string(),
            AppError::Media(MediaError::UnsupportedContentType(_)) => value.to_string(),
            AppError::Config(_) => "service configuration is unavailable".to_owned(),
            AppError::Room(_) => "chat room is temporarily unavailable".to_owned(),
            AppError::Database(_) => "message storage is temporarily unavailable".to_owned(),
        };

        Self { code, message }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum RoomError {
    #[error("invalid request: {0}")]
    InvalidRequest(String),

    #[error("room command channel is closed")]
    CommandChannelClosed,

    #[error("websocket session is unavailable")]
    SessionUnavailable,

    #[error("message persistence failed: {0}")]
    Persistence(#[from] DatabaseError),
}

#[derive(Debug, thiserror::Error)]
pub enum DatabaseError {
    #[error("database directory could not be created: {0}")]
    DirectoryCreateFailed(String),

    #[error("database open failed: {0}")]
    OpenFailed(String),

    #[error("database migration failed: {0}")]
    MigrationFailed(String),

    #[error("message write failed: {0}")]
    WriteFailed(String),

    #[error("message read failed: {0}")]
    ReadFailed(String),

    #[error("not found: {0}")]
    NotFound(String),
}

impl From<libsql::Error> for DatabaseError {
    fn from(value: libsql::Error) -> Self {
        Self::ReadFailed(value.to_string())
    }
}

#[derive(Debug, thiserror::Error)]
pub enum IdentityError {
    #[error("invalid request: {0}")]
    InvalidRequest(String),

    #[error("not found: {0}")]
    NotFound(String),

    #[error("conflict: {0}")]
    Conflict(String),
}

#[derive(Debug, thiserror::Error)]
pub enum MediaError {
    #[error("unsupported media content type: {0}")]
    UnsupportedContentType(String),
}
