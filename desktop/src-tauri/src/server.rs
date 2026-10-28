//! 手机端连接的 WebSocket 服务（ADR-0003、ADR-0004、docs/prd/pairing.md）。

use std::{
    collections::HashMap,
    net::IpAddr,
    sync::{Arc, Mutex, RwLock},
    time::Duration,
};

use axum::{
    Router,
    extract::{
        State, WebSocketUpgrade,
        ws::{Message, WebSocket},
    },
    response::Response,
    routing::get,
};
use futures_util::{SinkExt, StreamExt, stream::SplitStream};
use prost::Message as _;
use tokio::{net::TcpListener, sync::mpsc, time::timeout};

use crate::{
    dictation::{DictationHandle, DictationMode, DictationService},
    input::{InputCommand, InputSink, MouseButton, Shortcut, SpecialKey},
    pairing::{AuthOutcome, Credential, PairingStore, pairing_link},
    protocol::{
        self, ClientMessage, DictationFailed, Hello, Rejected, ServerMessage, Welcome,
        button_action::Kind, client_message, hello, key_press, rejected, server_message,
        shortcut_action,
    },
    status::StatusHub,
};

pub const PORT: u16 = 47321;

/// 连接建立后必须在这段时间内完成验证。
const HELLO_TIMEOUT: Duration = Duration::from_secs(5);
/// 手机端每 2 秒发一次心跳，超过这段时间收不到任何帧就认为已断线。
const IDLE_TIMEOUT: Duration = Duration::from_secs(6);

pub struct ServerContext {
    pub desktop_name: String,
    pub host: IpAddr,
    pub pairing: Mutex<PairingStore>,
    pub status: Arc<StatusHub>,
    pub input: Arc<dyn InputSink>,
    /// 缺少语音输入的配置时为 `None`；保存设置后会被替换，不用重启电脑端。
    dictation: RwLock<Option<Arc<dyn DictationService>>>,
}

impl ServerContext {
    pub fn new(
        desktop_name: String,
        host: IpAddr,
        pairing: PairingStore,
        status: Arc<StatusHub>,
        input: Arc<dyn InputSink>,
    ) -> Self {
        Self {
            desktop_name,
            host,
            pairing: Mutex::new(pairing),
            status,
            input,
            dictation: RwLock::new(None),
        }
    }

    pub fn pairing_link(&self) -> String {
        pairing_link(self.host, PORT, self.pairing.lock().unwrap().pairing_token())
    }

    /// 之后开始的语音输入使用新的服务，进行中的语音输入不受影响。
    pub fn set_dictation(&self, service: Option<Arc<dyn DictationService>>) {
        self.status.set_dictation_ready(service.is_some());
        *self.dictation.write().unwrap() = service;
    }
}

pub async fn serve(listener: TcpListener, context: Arc<ServerContext>) -> std::io::Result<()> {
    let router = Router::new().route("/ws", get(upgrade)).with_state(context);
    axum::serve(listener, router).await
}

async fn upgrade(socket: WebSocketUpgrade, State(context): State<Arc<ServerContext>>) -> Response {
    socket.on_upgrade(move |socket| session(socket, context))
}

async fn session(socket: WebSocket, context: Arc<ServerContext>) {
    let (mut writer, mut reader) = socket.split();

    let Ok(Some(hello)) = timeout(HELLO_TIMEOUT, read_hello(&mut reader)).await else {
        return;
    };
    let (reply, accepted) = match authenticate(&context, &hello) {
        Some(AuthOutcome::Paired { device_key }) => {
            context.status.set_pairing_link(context.pairing_link());
            (welcome(&context, device_key), true)
        }
        Some(AuthOutcome::Recognized) => (welcome(&context, String::new()), true),
        Some(AuthOutcome::Rejected) | None => (
            server_message::Body::Rejected(Rejected {
                reason: rejected::Reason::InvalidCredential.into(),
            }),
            false,
        ),
    };
    if writer.send(encode(reply)).await.is_err() || !accepted {
        let _ = writer.close().await;
        return;
    }

    let device_name = hello.device_name;
    context.status.device_connected(&device_name);
    tracing::info!(device = %device_name, "mobile app connected");

    let (outbound, mut outbound_rx) = mpsc::unbounded_channel::<ServerMessage>();
    let forward = tokio::spawn(async move {
        while let Some(message) = outbound_rx.recv().await {
            if writer.send(Message::Binary(message.encode_to_vec().into())).await.is_err() {
                break;
            }
        }
    });

    let mut dictations: HashMap<u32, DictationHandle> = HashMap::new();
    loop {
        // 任何帧（包括心跳）都会重置超时；超时或出错都按断线处理。
        let frame = match timeout(IDLE_TIMEOUT, reader.next()).await {
            Ok(Some(Ok(frame))) => frame,
            Ok(Some(Err(err))) => {
                tracing::debug!("connection failed: {err}");
                break;
            }
            Ok(None) => break,
            Err(_) => {
                tracing::debug!("heartbeat timed out");
                break;
            }
        };
        match frame {
            Message::Binary(bytes) => match ClientMessage::decode(bytes) {
                Ok(ClientMessage { body: Some(body) }) => {
                    dispatch(&context, body, &mut dictations, &outbound);
                }
                Ok(ClientMessage { body: None }) => {}
                Err(err) => tracing::warn!("ignored malformed message: {err}"),
            },
            Message::Close(_) => break,
            _ => {}
        }
    }

    // 断线收尾：松开被按住的鼠标键，丢弃未完成的语音输入。
    context.input.send(InputCommand::ReleaseAll);
    drop(dictations);
    forward.abort();
    context.status.device_disconnected(&device_name);
    tracing::info!(device = %device_name, "mobile app disconnected");
}

async fn read_hello(reader: &mut SplitStream<WebSocket>) -> Option<Hello> {
    while let Some(Ok(frame)) = reader.next().await {
        if let Message::Binary(bytes) = frame {
            return match ClientMessage::decode(bytes) {
                Ok(ClientMessage { body: Some(client_message::Body::Hello(hello)) }) => Some(hello),
                _ => None,
            };
        }
    }
    None
}

fn authenticate(context: &ServerContext, hello: &Hello) -> Option<AuthOutcome> {
    let credential = match hello.credential.as_ref()? {
        hello::Credential::PairingToken(token) => Credential::PairingToken(token),
        hello::Credential::DeviceKey(key) => Credential::DeviceKey(key),
    };
    let mut pairing = context.pairing.lock().unwrap();
    match pairing.authenticate(&hello.device_name, credential) {
        Ok(outcome) => Some(outcome),
        Err(err) => {
            tracing::error!("failed to save pairing: {err:#}");
            None
        }
    }
}

fn welcome(context: &ServerContext, device_key: String) -> server_message::Body {
    server_message::Body::Welcome(Welcome {
        desktop_name: context.desktop_name.clone(),
        device_key,
    })
}

fn dispatch(
    context: &ServerContext,
    body: client_message::Body,
    dictations: &mut HashMap<u32, DictationHandle>,
    outbound: &mpsc::UnboundedSender<ServerMessage>,
) {
    use client_message::Body;

    match body {
        Body::Hello(_) => {}
        Body::PointerMove(movement) => context.input.send(InputCommand::Move {
            dx: movement.dx,
            dy: movement.dy,
        }),
        Body::ButtonAction(action) => {
            let button = match action.button() {
                protocol::MouseButton::Left => MouseButton::Left,
                protocol::MouseButton::Right => MouseButton::Right,
                protocol::MouseButton::Unspecified => return,
            };
            let command = match action.kind() {
                Kind::Click => InputCommand::Click(button),
                Kind::DoubleClick => InputCommand::DoubleClick(button),
                Kind::Press => InputCommand::Press(button),
                Kind::Release => InputCommand::Release(button),
                Kind::Unspecified => return,
            };
            context.input.send(command);
        }
        Body::Scroll(scroll) => context.input.send(InputCommand::Scroll {
            dx: scroll.dx,
            dy: scroll.dy,
        }),
        Body::DictationStart(start) => {
            dictations.retain(|_, handle| !handle.is_finished());
            let mode = match start.mode() {
                protocol::DictationMode::Compose => DictationMode::Compose,
                protocol::DictationMode::Direct | protocol::DictationMode::Unspecified => DictationMode::Direct,
            };
            let service = context.dictation.read().unwrap().clone();
            match service {
                Some(service) => {
                    let handle = service.start(start.session_id, mode, context.input.clone(), outbound.clone());
                    dictations.insert(start.session_id, handle);
                }
                None => {
                    let _ = outbound.send(ServerMessage {
                        body: Some(server_message::Body::DictationFailed(DictationFailed {
                            session_id: start.session_id,
                            message: "电脑端还没有配置语音输入".into(),
                        })),
                    });
                }
            }
        }
        Body::AudioChunk(chunk) => {
            if let Some(handle) = dictations.get(&chunk.session_id) {
                handle.push_audio(chunk.pcm);
            }
        }
        Body::DictationStop(stop) => {
            if let Some(handle) = dictations.get(&stop.session_id) {
                handle.finish();
            }
        }
        Body::TypeText(typed) => {
            if !typed.text.is_empty() {
                context.input.send(InputCommand::Text(typed.text));
            }
        }
        Body::KeyPress(press) => {
            if let Some(key) = special_key(press.key()) {
                context.input.send(InputCommand::Key(key));
            }
        }
        Body::ShortcutAction(action) => {
            if let Some(shortcut) = shortcut(action.shortcut()) {
                context.input.send(InputCommand::Shortcut(shortcut));
            }
        }
    }
}

fn special_key(key: key_press::Key) -> Option<SpecialKey> {
    use key_press::Key;
    Some(match key {
        Key::Enter => SpecialKey::Enter,
        Key::Backspace => SpecialKey::Backspace,
        Key::Delete => SpecialKey::Delete,
        Key::Tab => SpecialKey::Tab,
        Key::Escape => SpecialKey::Escape,
        Key::Space => SpecialKey::Space,
        Key::Up => SpecialKey::Up,
        Key::Down => SpecialKey::Down,
        Key::Left => SpecialKey::Left,
        Key::Right => SpecialKey::Right,
        Key::Home => SpecialKey::Home,
        Key::End => SpecialKey::End,
        Key::PageUp => SpecialKey::PageUp,
        Key::PageDown => SpecialKey::PageDown,
        Key::Unspecified => return None,
    })
}

fn shortcut(shortcut: shortcut_action::Shortcut) -> Option<Shortcut> {
    use shortcut_action::Shortcut as Action;
    Some(match shortcut {
        Action::Copy => Shortcut::Copy,
        Action::Cut => Shortcut::Cut,
        Action::Paste => Shortcut::Paste,
        Action::Undo => Shortcut::Undo,
        Action::Redo => Shortcut::Redo,
        Action::SelectAll => Shortcut::SelectAll,
        Action::SwitchApp => Shortcut::SwitchApp,
        Action::Unspecified => return None,
    })
}

fn encode(body: server_message::Body) -> Message {
    Message::Binary(ServerMessage { body: Some(body) }.encode_to_vec().into())
}

#[cfg(test)]
mod tests {
    use std::{net::Ipv4Addr, time::Duration};

    use tokio::net::TcpStream;
    use tokio_tungstenite::{MaybeTlsStream, WebSocketStream, connect_async, tungstenite};

    use super::*;
    use crate::{
        dictation::{AudioFrame, Cleaner, Outbound, RecognitionEvent, run_pipeline},
        protocol::{
            AudioChunk, ButtonAction, DictationStart, DictationStop, KeyPress, PointerMove, ShortcutAction,
            TypeText,
        },
    };

    type Client = WebSocketStream<MaybeTlsStream<TcpStream>>;

    #[derive(Default)]
    struct RecordingInput(Mutex<Vec<InputCommand>>);

    impl InputSink for RecordingInput {
        fn send(&self, command: InputCommand) {
            self.0.lock().unwrap().push(command);
        }
    }

    impl RecordingInput {
        fn commands(&self) -> Vec<InputCommand> {
            self.0.lock().unwrap().clone()
        }
    }

    struct UppercaseCleaner;

    impl Cleaner for UppercaseCleaner {
        async fn clean(&self, sentence: &str, _preceding: &str) -> anyhow::Result<String> {
            Ok(sentence.to_uppercase())
        }
    }

    /// 收到松开说话按钮后，把收到的音频字节数作为定稿句返回。
    struct ScriptedDictation;

    impl DictationService for ScriptedDictation {
        fn start(
            &self,
            session_id: u32,
            mode: DictationMode,
            input: Arc<dyn InputSink>,
            outbound: Outbound,
        ) -> DictationHandle {
            let (audio, mut audio_rx) = mpsc::unbounded_channel();
            let (events, events_rx) = mpsc::unbounded_channel();
            let recognition = tokio::spawn(async move {
                let mut received = 0;
                while let Some(frame) = audio_rx.recv().await {
                    match frame {
                        AudioFrame::Pcm(pcm) => received += pcm.len(),
                        AudioFrame::End => break,
                    }
                }
                let _ = events.send(RecognitionEvent::Final(format!("got {received} bytes")));
                let _ = events.send(RecognitionEvent::Finished);
            });
            let pipeline =
                tokio::spawn(run_pipeline(session_id, mode, events_rx, Arc::new(UppercaseCleaner), input, outbound));
            DictationHandle::new(audio, vec![recognition, pipeline])
        }
    }

    struct Harness {
        context: Arc<ServerContext>,
        input: Arc<RecordingInput>,
        url: String,
    }

    async fn start_server(dictation: Option<Arc<dyn DictationService>>) -> Harness {
        let path = std::env::temp_dir()
            .join(format!("echopad-server-{}", uuid::Uuid::new_v4()))
            .join("paired-devices.json");
        let pairing = PairingStore::load(path).unwrap();
        let host = IpAddr::from(Ipv4Addr::LOCALHOST);
        let input = Arc::new(RecordingInput::default());
        let status = Arc::new(StatusHub::new(pairing_link(host, PORT, pairing.pairing_token()), false, |_| {}));
        let context = Arc::new(ServerContext::new("Test Mac".into(), host, pairing, status, input.clone()));
        context.set_dictation(dictation);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let url = format!("ws://{}/ws", listener.local_addr().unwrap());
        tokio::spawn(serve(listener, context.clone()));
        Harness { context, input, url }
    }

    impl Harness {
        fn pairing_token(&self) -> String {
            self.context.pairing.lock().unwrap().pairing_token().to_owned()
        }

        async fn connect(&self, credential: hello::Credential) -> (Client, server_message::Body) {
            let (mut client, _) = connect_async(&self.url).await.unwrap();
            send(&mut client, client_message::Body::Hello(Hello { device_name: "Phone".into(), credential: Some(credential) })).await;
            let reply = receive(&mut client).await;
            (client, reply)
        }

        /// 等电脑端处理完断线收尾。
        async fn wait_for_release_all(&self) {
            for _ in 0..100 {
                if self.input.commands().contains(&InputCommand::ReleaseAll) {
                    return;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            panic!("the desktop app did not clean up after the disconnect");
        }
    }

    async fn send(client: &mut Client, body: client_message::Body) {
        let bytes = ClientMessage { body: Some(body) }.encode_to_vec();
        client.send(tungstenite::Message::Binary(bytes.into())).await.unwrap();
    }

    async fn receive(client: &mut Client) -> server_message::Body {
        loop {
            let frame = timeout(Duration::from_secs(2), client.next()).await.unwrap().unwrap().unwrap();
            if let tungstenite::Message::Binary(bytes) = frame {
                return ServerMessage::decode(bytes).unwrap().body.unwrap();
            }
        }
    }

    async fn paired_client(harness: &Harness) -> Client {
        let (client, reply) = harness.connect(hello::Credential::PairingToken(harness.pairing_token())).await;
        assert!(matches!(reply, server_message::Body::Welcome(_)));
        client
    }

    #[tokio::test]
    async fn pairing_issues_a_device_key_that_works_for_later_connections() {
        let harness = start_server(None).await;
        let token = harness.pairing_token();

        let (_, reply) = harness.connect(hello::Credential::PairingToken(token.clone())).await;
        let server_message::Body::Welcome(Welcome { desktop_name, device_key }) = reply else {
            panic!("expected pairing to succeed, got {reply:?}");
        };
        assert_eq!(desktop_name, "Test Mac");
        assert!(!device_key.is_empty());
        assert!(!harness.context.status.snapshot().pairing_link.contains(&token));

        let (_, reply) = harness.connect(hello::Credential::DeviceKey(device_key)).await;
        assert!(matches!(reply, server_message::Body::Welcome(Welcome { device_key, .. }) if device_key.is_empty()));

        let (_, reply) = harness.connect(hello::Credential::PairingToken(token)).await;
        assert!(matches!(reply, server_message::Body::Rejected(_)));
    }

    #[tokio::test]
    async fn pointer_and_button_messages_reach_the_input() {
        let harness = start_server(None).await;
        let mut client = paired_client(&harness).await;

        send(&mut client, client_message::Body::PointerMove(PointerMove { dx: 3.0, dy: -2.0 })).await;
        send(
            &mut client,
            client_message::Body::ButtonAction(ButtonAction {
                button: protocol::MouseButton::Right.into(),
                kind: Kind::Click.into(),
            }),
        )
        .await;
        client.close(None).await.unwrap();
        harness.wait_for_release_all().await;

        assert_eq!(
            harness.input.commands()[..2],
            [InputCommand::Move { dx: 3.0, dy: -2.0 }, InputCommand::Click(MouseButton::Right)]
        );
    }

    #[tokio::test]
    async fn disconnecting_mid_drag_releases_the_held_button() {
        let harness = start_server(None).await;
        let mut client = paired_client(&harness).await;

        send(
            &mut client,
            client_message::Body::ButtonAction(ButtonAction {
                button: protocol::MouseButton::Left.into(),
                kind: Kind::Press.into(),
            }),
        )
        .await;
        drop(client);
        harness.wait_for_release_all().await;

        assert_eq!(
            harness.input.commands(),
            [InputCommand::Press(MouseButton::Left), InputCommand::ReleaseAll]
        );
    }

    #[tokio::test]
    async fn dictation_commits_cleaned_sentences() {
        let harness = start_server(Some(Arc::new(ScriptedDictation))).await;
        let mut client = paired_client(&harness).await;

        send(&mut client, client_message::Body::DictationStart(DictationStart { session_id: 1, mode: protocol::DictationMode::Direct.into() })).await;
        send(&mut client, client_message::Body::AudioChunk(AudioChunk { session_id: 1, pcm: vec![0; 3200] })).await;
        send(&mut client, client_message::Body::DictationStop(DictationStop { session_id: 1 })).await;

        let committed = receive(&mut client).await;
        assert!(matches!(&committed, server_message::Body::CommittedText(text) if text.text == "GOT 3200 BYTES"));
        assert!(matches!(receive(&mut client).await, server_message::Body::DictationFinished(_)));
        assert!(harness.input.commands().contains(&InputCommand::Text("GOT 3200 BYTES".into())));
    }

    #[tokio::test]
    async fn disconnecting_mid_dictation_commits_nothing() {
        let harness = start_server(Some(Arc::new(ScriptedDictation))).await;
        let mut client = paired_client(&harness).await;

        send(&mut client, client_message::Body::DictationStart(DictationStart { session_id: 1, mode: protocol::DictationMode::Direct.into() })).await;
        send(&mut client, client_message::Body::AudioChunk(AudioChunk { session_id: 1, pcm: vec![0; 3200] })).await;
        drop(client);
        harness.wait_for_release_all().await;
        tokio::time::sleep(Duration::from_millis(50)).await;

        assert!(!harness.input.commands().iter().any(|command| matches!(command, InputCommand::Text(_))));
    }

    #[tokio::test]
    async fn compose_mode_sends_sentences_back_to_the_compose_box() {
        let harness = start_server(Some(Arc::new(ScriptedDictation))).await;
        let mut client = paired_client(&harness).await;

        send(
            &mut client,
            client_message::Body::DictationStart(DictationStart {
                session_id: 2,
                mode: protocol::DictationMode::Compose.into(),
            }),
        )
        .await;
        send(&mut client, client_message::Body::AudioChunk(AudioChunk { session_id: 2, pcm: vec![0; 10] })).await;
        send(&mut client, client_message::Body::DictationStop(DictationStop { session_id: 2 })).await;

        assert!(matches!(&receive(&mut client).await, server_message::Body::ComposeText(text) if text.text == "GOT 10 BYTES"));
        assert!(matches!(receive(&mut client).await, server_message::Body::DictationFinished(_)));
        assert!(!harness.input.commands().iter().any(|command| matches!(command, InputCommand::Text(_))));
    }

    #[tokio::test]
    async fn typing_keys_and_shortcuts_reach_the_input() {
        let harness = start_server(None).await;
        let mut client = paired_client(&harness).await;

        send(&mut client, client_message::Body::TypeText(TypeText { text: "你好 world".into() })).await;
        send(&mut client, client_message::Body::KeyPress(KeyPress { key: key_press::Key::Enter.into() })).await;
        send(
            &mut client,
            client_message::Body::ShortcutAction(ShortcutAction { shortcut: shortcut_action::Shortcut::Paste.into() }),
        )
        .await;
        client.close(None).await.unwrap();
        harness.wait_for_release_all().await;

        assert_eq!(
            harness.input.commands()[..3],
            [
                InputCommand::Text("你好 world".into()),
                InputCommand::Key(SpecialKey::Enter),
                InputCommand::Shortcut(Shortcut::Paste),
            ]
        );
    }

    #[tokio::test]
    async fn dictation_without_configuration_fails_with_a_message() {
        let harness = start_server(None).await;
        let mut client = paired_client(&harness).await;

        send(&mut client, client_message::Body::DictationStart(DictationStart { session_id: 9, mode: protocol::DictationMode::Direct.into() })).await;

        assert!(matches!(
            receive(&mut client).await,
            server_message::Body::DictationFailed(DictationFailed { session_id: 9, .. })
        ));
    }
}
