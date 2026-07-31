fn main() {
    prost_build::compile_protos(&["proto/flock/v1/flock.proto"], &["proto/"]).unwrap();
}
