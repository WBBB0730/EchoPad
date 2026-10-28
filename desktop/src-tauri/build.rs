const PROTOCOL_DIR: &str = "../../protocol";

fn main() {
    println!("cargo:rerun-if-changed={PROTOCOL_DIR}/echopad.proto");
    let descriptors = protox::compile(["echopad.proto"], [PROTOCOL_DIR])
        .expect("failed to compile protocol/echopad.proto");
    prost_build::compile_fds(descriptors).expect("failed to generate protobuf code");

    tauri_build::build()
}
