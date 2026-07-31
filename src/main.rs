use flock_app::api;

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    api::run().await
}
