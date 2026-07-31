use std::env;
use std::path::PathBuf;

use crate::errors::AppError;

#[derive(Clone, Debug)]
pub struct AppConfig {
    pub bind_addr: String,
    pub cors_allowed_origins: Vec<String>,
    pub database_path: PathBuf,
    pub object_storage_base_url: String,
}

impl AppConfig {
    pub fn from_env() -> Result<Self, AppError> {
        let bind_addr = match env::var("FLOCK_BIND_ADDR") {
            Ok(value) => value,
            Err(_) => "127.0.0.1:8080".to_owned(),
        };

        let database_path = match env::var("FLOCK_DATABASE_PATH") {
            Ok(value) => PathBuf::from(value),
            Err(_) => PathBuf::from("flock.db"),
        };

        let cors_allowed_origins = match env::var("FLOCK_CORS_ALLOWED_ORIGINS") {
            Ok(value) => value
                .split(',')
                .map(str::trim)
                .filter(|origin| !origin.is_empty())
                .map(str::to_owned)
                .collect(),
            Err(_) => vec![
                "http://localhost:8080".to_owned(),
                "http://127.0.0.1:8080".to_owned(),
                "http://localhost:8081".to_owned(),
                "http://127.0.0.1:8081".to_owned(),
            ],
        };

        let object_storage_base_url = match env::var("OBJECT_STORAGE_BASE_URL") {
            Ok(value) => value,
            Err(_) => "https://uploads.example.invalid".to_owned(),
        };

        if bind_addr.trim().is_empty() {
            return Err(AppError::Config("FLOCK_BIND_ADDR is empty".to_owned()));
        }
        if database_path.as_os_str().is_empty() {
            return Err(AppError::Config("FLOCK_DATABASE_PATH is empty".to_owned()));
        }

        Ok(Self {
            bind_addr,
            cors_allowed_origins,
            database_path,
            object_storage_base_url,
        })
    }
}
