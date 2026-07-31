use uuid::Uuid;

use crate::{
    config::AppConfig,
    errors::MediaError,
    models::{UploadSlotRequest, UploadSlotResponse},
};

const UPLOAD_URL_TTL_SECONDS: u64 = 900;

pub fn create_upload_slot(
    config: &AppConfig,
    request: &UploadSlotRequest,
) -> Result<UploadSlotResponse, MediaError> {
    validate_content_type(&request.content_type)?;

    let upload_id = Uuid::new_v4();
    let object_key = format!(
        "rooms/{}/users/{}/uploads/{}",
        request.room_id, request.user_id, upload_id
    );
    let presigned_put_url = format!(
        "{}/{}?signature=dev-placeholder",
        config.object_storage_base_url.trim_end_matches('/'),
        object_key
    );

    Ok(UploadSlotResponse {
        upload_id,
        object_key,
        presigned_put_url,
        expires_in_seconds: UPLOAD_URL_TTL_SECONDS,
    })
}

fn validate_content_type(content_type: &str) -> Result<(), MediaError> {
    match content_type {
        "image/jpeg" | "image/png" | "image/webp" | "video/mp4" | "video/quicktime" => Ok(()),
        other => Err(MediaError::UnsupportedContentType(other.to_owned())),
    }
}
