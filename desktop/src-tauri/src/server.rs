//! 手机端连接的 WebSocket 服务（ADR-0003、ADR-0004、ADR-0014、ADR-0015、docs/prd/pairing.md）。

use std::{
    collections::HashMap,
    net::IpAddr,
    sync::{
        Arc, Mutex, RwLock,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};

use axum::{
    Router,
    extract::{
        Request, State, WebSocketUpgrade,
        ws::{Message, WebSocket},
    },
    response::Response,
    routing::get,
};
use futures_util::{SinkExt, StreamExt, stream::SplitStream};
use hyper::body::Incoming;
use hyper_util::rt::TokioIo;
use prost::Message as _;
use tokio::{
    net::TcpListener,
    sync::{Notify, mpsc},
    time::timeout,
};
use tokio_rustls::{TlsAcceptor, rustls::ServerConfig};
use tower_service::Service;

use crate::{
    dictation::{DictationHandle, DictationService},
    input::{InputCommand, InputSink, MouseButton, Owner, Shortcut, SpecialKey},
    pairing::{AuthOutcome, Credential, DeviceId, PairingStore, pairing_link},
    protocol::{
        self, ClientMessage, DictationFailed, Hello, Rejected, ServerMessage, SwitchRequest, Welcome,
        button_action::Kind, client_message, hello, key_press, rejected, server_message, shortcut_action,
        zoom::Phase as ZoomPhase,
    },
    status::{OutdatedApp, PhoneStatus, StatusHub},
};

pub const PORT: u16 = 47321;
/// 电脑端的协议版本（ADR-0010）。
pub const PROTOCOL_VERSION: u32 = protocol::ProtocolVersion::Current as u32;

/// 连接建立后必须在这段时间内完成验证。
const HELLO_TIMEOUT: Duration = Duration::from_secs(5);
/// 手机端每 2 秒发一次心跳，超过这段时间收不到任何帧就认为已断线。
const IDLE_TIMEOUT: Duration = Duration::from_secs(6);

pub struct ServerContext {
    pub desktop_name: String,
    /// 配对码里的局域网地址，电脑换了网络后会更新。
    host: Mutex<IpAddr>,
    /// 电脑端证书的指纹，放进配对码（ADR-0014）。
    fingerprint: String,
    pub pairing: Mutex<PairingStore>,
    pub status: Arc<StatusHub>,
    pub input: Arc<dyn InputSink>,
    /// 缺少语音输入的配置时为 `None`；保存设置后会被替换，不用重启电脑端。
    dictation: RwLock<Option<Arc<dyn DictationService>>>,
    /// 当前的连接：取消配对或同一部手机重新连上时据此关闭连接，让手机端切换过来时据此给它发消息。
    sessions: Mutex<HashMap<Owner, Session>>,
    next_session_id: AtomicU64,
}

struct Session {
    device: DeviceId,
    outbound: mpsc::UnboundedSender<ServerMessage>,
    close: Arc<Notify>,
}

impl ServerContext {
    pub fn new(
        desktop_name: String,
        host: IpAddr,
        fingerprint: String,
        pairing: PairingStore,
        status: Arc<StatusHub>,
        input: Arc<dyn InputSink>,
    ) -> Self {
        let context = Self {
            desktop_name,
            host: Mutex::new(host),
            fingerprint,
            pairing: Mutex::new(pairing),
            status,
            input,
            dictation: RwLock::new(None),
            sessions: Mutex::new(HashMap::new()),
            next_session_id: AtomicU64::new(1),
        };
        context.status.set_pairing_link(context.pairing_link());
        context.publish_phones();
        context
    }

    pub fn pairing_link(&self) -> String {
        let pairing = self.pairing.lock().unwrap();
        pairing_link(
            *self.host.lock().unwrap(),
            PORT,
            pairing.pairing_token(),
            pairing.desktop_id(),
            &self.fingerprint,
        )
    }

    /// 电脑换了网络或 IP 后更新配对码里的地址。
    pub fn update_host(&self, host: IpAddr) {
        let changed = std::mem::replace(&mut *self.host.lock().unwrap(), host) != host;
        if changed {
            self.status.set_pairing_link(self.pairing_link());
        }
    }

    /// 取消一部手机端的配对：作废它的设备密钥并关闭它的连接，之后它要重新扫码配对。
    pub fn unpair(&self, phone_id: &str) -> anyhow::Result<()> {
        let device = self.pairing.lock().unwrap().forget(phone_id)?;
        if let Some(device) = device {
            for session in self.sessions.lock().unwrap().values().filter(|session| session.device == device) {
                session.close.notify_one();
            }
        }
        self.publish_phones();
        Ok(())
    }

    /// 让一部在线的手机端切换过来，把这台电脑端设为它的当前电脑（ADR-0015）。
    pub fn request_switch(&self, phone_id: &str) {
        let phone = self.pairing.lock().unwrap().phones().into_iter().find(|phone| phone.id == phone_id);
        let Some(phone) = phone else {
            return;
        };
        let request = ServerMessage {
            body: Some(server_message::Body::SwitchRequest(SwitchRequest {})),
        };
        for session in self.sessions.lock().unwrap().values().filter(|session| session.device == phone.device) {
            let _ = session.outbound.send(request.clone());
        }
    }

    /// 之后开始的语音输入使用新的服务，进行中的语音输入不受影响。
    pub fn set_dictation(&self, service: Option<Arc<dyn DictationService>>) {
        self.status.set_dictation_ready(service.is_some());
        *self.dictation.write().unwrap() = service;
    }

    /// 把配过的手机端及是否在线告诉界面。
    fn publish_phones(&self) {
        let phones = self.pairing.lock().unwrap().phones();
        let phones = {
            let sessions = self.sessions.lock().unwrap();
            phones
                .into_iter()
                .map(|phone| PhoneStatus {
                    online: sessions.values().any(|session| session.device == phone.device),
                    id: phone.id,
                    name: phone.name,
                })
                .collect()
        };
        self.status.set_phones(phones);
    }
}

/// 在 TLS 上提供 WebSocket 服务（ADR-0014），每条连接各自握手，互不影响。
pub async fn serve(listener: TcpListener, context: Arc<ServerContext>, tls: Arc<ServerConfig>) {
    let router = Router::new().route("/ws", get(upgrade)).with_state(context);
    let acceptor = TlsAcceptor::from(tls);
    loop {
        let (stream, address) = match listener.accept().await {
            Ok(accepted) => accepted,
            Err(err) => {
                // 和 axum::serve 一样：接受连接失败（例如文件描述符用完）时稍等再继续。
                tracing::warn!("failed to accept a connection: {err}");
                tokio::time::sleep(Duration::from_secs(1)).await;
                continue;
            }
        };
        let acceptor = acceptor.clone();
        let router = router.clone();
        tokio::spawn(async move {
            let stream = match acceptor.accept(stream).await {
                Ok(stream) => stream,
                Err(err) => {
                    tracing::debug!(%address, "TLS handshake failed: {err}");
                    return;
                }
            };
            let service = hyper::service::service_fn(move |request: Request<Incoming>| router.clone().call(request));
            let connection = hyper::server::conn::http1::Builder::new()
                .serve_connection(TokioIo::new(stream), service)
                .with_upgrades();
            if let Err(err) = connection.await {
                tracing::debug!(%address, "connection ended with an error: {err}");
            }
        });
    }
}

async fn upgrade(socket: WebSocketUpgrade, State(context): State<Arc<ServerContext>>) -> Response {
    socket.on_upgrade(move |socket| session(socket, context))
}

async fn session(socket: WebSocket, context: Arc<ServerContext>) {
    let (mut writer, mut reader) = socket.split();

    let Ok(Some(hello)) = timeout(HELLO_TIMEOUT, read_hello(&mut reader)).await else {
        return;
    };
    // 先核对版本、再验证配对：版本不兼容时不消耗配对口令，配对仍然有效（ADR-0010）。
    if hello.protocol_version != PROTOCOL_VERSION {
        context.status.set_outdated(Some(outdated_app(hello.protocol_version)));
        let reply = server_message::Body::Rejected(Rejected {
            reason: rejected::Reason::VersionMismatch.into(),
            protocol_version: PROTOCOL_VERSION,
        });
        let _ = writer.send(encode(reply)).await;
        let _ = writer.close().await;
        return;
    }
    let (reply, device) = match authenticate(&context, &hello) {
        Some(AuthOutcome::Paired { device_key, device }) => {
            context.status.set_pairing_link(context.pairing_link());
            (welcome(&context, device_key), Some(device))
        }
        Some(AuthOutcome::Recognized(device)) => (welcome(&context, String::new()), Some(device)),
        Some(AuthOutcome::Rejected) | None => (
            server_message::Body::Rejected(Rejected {
                reason: rejected::Reason::InvalidCredential.into(),
                ..Default::default()
            }),
            None,
        ),
    };
    let sent = writer.send(encode(reply)).await.is_ok();
    let Some(device) = device.filter(|_| sent) else {
        let _ = writer.close().await;
        return;
    };
    context.status.set_outdated(None);

    let session_id = context.next_session_id.fetch_add(1, Ordering::Relaxed);
    let close = Arc::new(Notify::new());
    let (outbound, mut outbound_rx) = mpsc::unbounded_channel::<ServerMessage>();
    {
        let mut sessions = context.sessions.lock().unwrap();
        // 一部手机对这台电脑只保持一条连接（ADR-0015）。它重新连上时，旧连接已经断了，只是还没等到心跳超时：
        // 立即关掉，松开旧连接按住的鼠标键。
        for previous in sessions.values().filter(|session| session.device == device) {
            previous.close.notify_one();
        }
        sessions.insert(
            session_id,
            Session {
                device,
                outbound: outbound.clone(),
                close: close.clone(),
            },
        );
    }
    context.publish_phones();
    let device_name = hello.device_name;
    tracing::info!(device = %device_name, "mobile app connected");

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
        let received = tokio::select! {
            _ = close.notified() => {
                tracing::info!(device = %device_name, "connection closed by the desktop app");
                break;
            }
            received = timeout(IDLE_TIMEOUT, reader.next()) => received,
        };
        let frame = match received {
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
                    dispatch(&context, session_id, body, &mut dictations, &outbound);
                }
                Ok(ClientMessage { body: None }) => {}
                Err(err) => tracing::warn!("ignored malformed message: {err}"),
            },
            Message::Close(_) => break,
            _ => {}
        }
    }

    // 断线收尾：只松开这部手机按住的鼠标键，丢弃它未完成的语音输入。
    context.input.send(InputCommand::ReleaseAll { owner: session_id });
    drop(dictations);
    forward.abort();
    context.sessions.lock().unwrap().remove(&session_id);
    context.publish_phones();
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

/// 两端版本不兼容时，协议版本较小的一端需要更新。
fn outdated_app(mobile_version: u32) -> OutdatedApp {
    if mobile_version < PROTOCOL_VERSION { OutdatedApp::Mobile } else { OutdatedApp::Desktop }
}

fn authenticate(context: &ServerContext, hello: &Hello) -> Option<AuthOutcome> {
    let credential = match hello.credential.as_ref()? {
        hello::Credential::PairingToken(token) => Credential::PairingToken(token),
        hello::Credential::DeviceKey(key) => Credential::DeviceKey(key),
    };
    let mut pairing = context.pairing.lock().unwrap();
    match pairing.authenticate(&hello.phone_id, &hello.device_name, credential) {
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
        desktop_id: context.pairing.lock().unwrap().desktop_id().to_owned(),
    })
}

fn dispatch(
    context: &ServerContext,
    owner: Owner,
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
                Kind::Press => InputCommand::Press { owner, button },
                Kind::Release => InputCommand::Release { owner, button },
                Kind::Unspecified => return,
            };
            context.input.send(command);
        }
        Body::Scroll(scroll) => context.input.send(InputCommand::Scroll {
            dx: scroll.dx,
            dy: scroll.dy,
        }),
        Body::Zoom(zoom) => {
            let command = match zoom.phase() {
                ZoomPhase::Began => InputCommand::ZoomStart { owner },
                ZoomPhase::Changed => InputCommand::Zoom {
                    owner,
                    magnification: zoom.magnification,
                },
                ZoomPhase::Ended => InputCommand::ZoomEnd { owner },
                ZoomPhase::Unspecified => return,
            };
            context.input.send(command);
        }
        Body::DictationStart(start) => {
            dictations.retain(|_, handle| !handle.is_finished());
            let service = context.dictation.read().unwrap().clone();
            match service {
                Some(service) => {
                    let handle = service.start(start.session_id, context.input.clone(), outbound.clone());
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
        // 丢弃进行中的语音输入：识别和整理都停下，什么都不上屏。
        Body::DictationCancel(cancel) => {
            dictations.remove(&cancel.session_id);
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
        Key::PlayPause => SpecialKey::PlayPause,
        Key::NextTrack => SpecialKey::NextTrack,
        Key::PreviousTrack => SpecialKey::PreviousTrack,
        Key::VolumeUp => SpecialKey::VolumeUp,
        Key::VolumeDown => SpecialKey::VolumeDown,
        Key::Mute => SpecialKey::Mute,
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
    use std::{
        net::{Ipv4Addr, SocketAddr},
        time::Duration,
    };

    use tokio::net::TcpStream;
    use tokio_rustls::{
        TlsConnector,
        client::TlsStream,
        rustls::{
            ClientConfig, DigitallySignedStruct, SignatureScheme,
            client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
            crypto::{self, CryptoProvider, aws_lc_rs},
            pki_types::{CertificateDer, ServerName, UnixTime},
        },
    };
    use tokio_tungstenite::{WebSocketStream, tungstenite};

    use super::*;
    use crate::{
        certificate::{CERTIFICATE_NAME, DesktopCertificate},
        dictation::{AudioFrame, Cleaner, Outbound, RecognitionEvent, run_pipeline},
        protocol::{
            AudioChunk, ButtonAction, DictationCancel, DictationStart, DictationStop, KeyPress, PointerMove,
            ShortcutAction, TypeText, Zoom,
        },
    };

    type Client = WebSocketStream<TlsStream<TcpStream>>;

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
        async fn clean(&self, text: &str) -> anyhow::Result<String> {
            Ok(text.to_uppercase())
        }
    }

    /// 收到松开说话按钮后，把收到的音频字节数作为定稿句返回。
    struct ScriptedDictation;

    impl DictationService for ScriptedDictation {
        fn start(&self, session_id: u32, input: Arc<dyn InputSink>, outbound: Outbound) -> DictationHandle {
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
            let pipeline = tokio::spawn(run_pipeline(session_id, events_rx, Arc::new(UppercaseCleaner), input, outbound));
            DictationHandle::new(audio, vec![recognition, pipeline])
        }
    }

    /// 手机端的做法：只认配对码里指纹对应的那张证书（ADR-0014）。
    #[derive(Debug)]
    struct PinnedCertificate(CertificateDer<'static>);

    impl ServerCertVerifier for PinnedCertificate {
        fn verify_server_cert(
            &self,
            end_entity: &CertificateDer<'_>,
            _intermediates: &[CertificateDer<'_>],
            _server_name: &ServerName<'_>,
            _ocsp_response: &[u8],
            _now: UnixTime,
        ) -> Result<ServerCertVerified, tokio_rustls::rustls::Error> {
            if end_entity.as_ref() == self.0.as_ref() {
                Ok(ServerCertVerified::assertion())
            } else {
                Err(tokio_rustls::rustls::Error::General("unexpected certificate".into()))
            }
        }

        fn verify_tls12_signature(
            &self,
            message: &[u8],
            certificate: &CertificateDer<'_>,
            signature: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, tokio_rustls::rustls::Error> {
            crypto::verify_tls12_signature(message, certificate, signature, &provider().signature_verification_algorithms)
        }

        fn verify_tls13_signature(
            &self,
            message: &[u8],
            certificate: &CertificateDer<'_>,
            signature: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, tokio_rustls::rustls::Error> {
            crypto::verify_tls13_signature(message, certificate, signature, &provider().signature_verification_algorithms)
        }

        fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
            provider().signature_verification_algorithms.supported_schemes()
        }
    }

    fn provider() -> CryptoProvider {
        aws_lc_rs::default_provider()
    }

    struct Harness {
        context: Arc<ServerContext>,
        input: Arc<RecordingInput>,
        address: SocketAddr,
        certificate: CertificateDer<'static>,
    }

    async fn start_server(dictation: Option<Arc<dyn DictationService>>) -> Harness {
        let dir = std::env::temp_dir().join(format!("echopad-server-{}", uuid::Uuid::new_v4()));
        let certificate = DesktopCertificate::load_or_create(&dir).unwrap();
        let pairing = PairingStore::load(dir.join("paired-devices.json")).unwrap();
        let host = IpAddr::from(Ipv4Addr::LOCALHOST);
        let input = Arc::new(RecordingInput::default());
        let status = Arc::new(StatusHub::new(|_| {}));
        let context = Arc::new(ServerContext::new(
            "Test Mac".into(),
            host,
            certificate.fingerprint(),
            pairing,
            status,
            input.clone(),
        ));
        context.set_dictation(dictation);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(serve(listener, context.clone(), certificate.server_config().unwrap()));
        Harness {
            context,
            input,
            address,
            certificate: certificate.der().clone(),
        }
    }

    impl Harness {
        fn pairing_token(&self) -> String {
            self.context.pairing.lock().unwrap().pairing_token().to_owned()
        }

        /// 像手机端一样建立加密连接：只认这台电脑端的证书。
        async fn open(&self) -> Client {
            let config = ClientConfig::builder_with_provider(Arc::new(provider()))
                .with_safe_default_protocol_versions()
                .unwrap()
                .dangerous()
                .with_custom_certificate_verifier(Arc::new(PinnedCertificate(self.certificate.clone())))
                .with_no_client_auth();
            let tcp = TcpStream::connect(self.address).await.unwrap();
            let tls = TlsConnector::from(Arc::new(config))
                .connect(ServerName::try_from(CERTIFICATE_NAME).unwrap(), tcp)
                .await
                .unwrap();
            let (client, _) = tokio_tungstenite::client_async(format!("wss://{CERTIFICATE_NAME}/ws"), tls).await.unwrap();
            client
        }

        async fn connect(&self, phone_id: &str, credential: hello::Credential) -> (Client, server_message::Body) {
            self.connect_with_version(phone_id, PROTOCOL_VERSION, credential).await
        }

        async fn connect_with_version(
            &self,
            phone_id: &str,
            protocol_version: u32,
            credential: hello::Credential,
        ) -> (Client, server_message::Body) {
            let mut client = self.open().await;
            let hello = Hello {
                device_name: format!("Phone {phone_id}"),
                credential: Some(credential),
                phone_id: phone_id.into(),
                protocol_version,
            };
            send(&mut client, client_message::Body::Hello(hello)).await;
            let reply = receive(&mut client).await;
            (client, reply)
        }

        /// 用当前的配对码配对一部手机端，返回连接和发放的设备密钥。
        async fn pair(&self, phone_id: &str) -> (Client, String) {
            let (client, reply) = self.connect(phone_id, hello::Credential::PairingToken(self.pairing_token())).await;
            let server_message::Body::Welcome(Welcome { device_key, .. }) = reply else {
                panic!("expected pairing to succeed, got {reply:?}");
            };
            (client, device_key)
        }

        /// 等电脑端界面上的手机端列表变成预期的样子。
        async fn wait_for_phones(&self, expected: &[(&str, bool)]) {
            for _ in 0..100 {
                let phones: Vec<_> = self.context.status.snapshot().phones;
                let actual: Vec<_> = phones.iter().map(|phone| (phone.name.as_str(), phone.online)).collect();
                if actual == expected {
                    return;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            panic!("phones never became {expected:?}: {:?}", self.context.status.snapshot().phones);
        }

        /// 等电脑端处理完断线收尾。
        async fn wait_for_release_all(&self) {
            self.wait_for_input(|command| matches!(command, InputCommand::ReleaseAll { .. })).await;
        }

        /// 等电脑端发出符合 `expected` 的键鼠操作。
        async fn wait_for_input(&self, expected: impl Fn(&InputCommand) -> bool) {
            for _ in 0..100 {
                if self.input.commands().iter().any(&expected) {
                    return;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            panic!("the expected input never arrived: {:?}", self.input.commands());
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
        harness.pair("phone-1").await.0
    }

    #[tokio::test]
    async fn pairing_issues_a_device_key_that_works_for_later_connections() {
        let harness = start_server(None).await;
        let token = harness.pairing_token();
        let link = harness.context.status.snapshot().pairing_link;
        assert!(link.contains(&format!("fp={}", harness.context.fingerprint)));

        let (_, reply) = harness.connect("phone-1", hello::Credential::PairingToken(token.clone())).await;
        let server_message::Body::Welcome(Welcome { desktop_name, device_key, desktop_id }) = reply else {
            panic!("expected pairing to succeed, got {reply:?}");
        };
        assert_eq!(desktop_name, "Test Mac");
        assert!(!device_key.is_empty());
        assert_eq!(desktop_id, harness.context.pairing.lock().unwrap().desktop_id());
        assert!(!harness.context.status.snapshot().pairing_link.contains(&token));

        let (_, reply) = harness.connect("phone-1", hello::Credential::DeviceKey(device_key)).await;
        assert!(matches!(reply, server_message::Body::Welcome(Welcome { device_key, .. }) if device_key.is_empty()));

        let (_, reply) = harness.connect("phone-2", hello::Credential::PairingToken(token)).await;
        assert!(matches!(reply, server_message::Body::Rejected(_)));
    }

    #[tokio::test]
    async fn a_version_mismatch_is_rejected_without_using_the_pairing_token() {
        let harness = start_server(None).await;
        let token = harness.pairing_token();

        let (_, reply) = harness
            .connect_with_version("phone-1", PROTOCOL_VERSION + 1, hello::Credential::PairingToken(token.clone()))
            .await;
        let server_message::Body::Rejected(rejected) = reply else {
            panic!("expected a version mismatch, got {reply:?}");
        };
        assert_eq!(rejected.reason(), rejected::Reason::VersionMismatch);
        assert_eq!(rejected.protocol_version, PROTOCOL_VERSION);
        assert_eq!(harness.context.status.snapshot().outdated, Some(OutdatedApp::Desktop));

        // 手机端更新之前的版本没有协议版本，按 0 处理。
        harness.connect_with_version("phone-1", 0, hello::Credential::PairingToken(token.clone())).await;
        assert_eq!(harness.context.status.snapshot().outdated, Some(OutdatedApp::Mobile));

        let (_, reply) = harness.connect("phone-1", hello::Credential::PairingToken(token)).await;
        assert!(matches!(reply, server_message::Body::Welcome(_)));
        harness.wait_for_phones(&[("Phone phone-1", true)]).await;
        assert_eq!(harness.context.status.snapshot().outdated, None);
    }

    #[tokio::test]
    async fn several_phones_stay_connected_and_are_listed() {
        let harness = start_server(None).await;
        let (_first, _) = harness.pair("phone-1").await;
        let (second, _) = harness.pair("phone-2").await;
        harness.wait_for_phones(&[("Phone phone-1", true), ("Phone phone-2", true)]).await;

        drop(second);

        harness.wait_for_phones(&[("Phone phone-1", true), ("Phone phone-2", false)]).await;
    }

    #[tokio::test]
    async fn a_disconnecting_phone_only_cleans_up_after_itself() {
        let harness = start_server(None).await;
        let (mut first, _) = harness.pair("phone-1").await;
        let (mut second, _) = harness.pair("phone-2").await;
        let press = client_message::Body::ButtonAction(ButtonAction {
            button: protocol::MouseButton::Left.into(),
            kind: Kind::Press.into(),
        });
        send(&mut first, press.clone()).await;
        send(&mut second, press).await;
        harness.wait_for_phones(&[("Phone phone-1", true), ("Phone phone-2", true)]).await;

        drop(second);
        harness.wait_for_release_all().await;

        // 两部手机各按一次，按的是不同的连接；只有断线的那一部收尾，另一部按住的键不受影响。
        let commands = harness.input.commands();
        let pressed: Vec<_> = commands
            .iter()
            .filter_map(|command| match command {
                InputCommand::Press { owner, .. } => Some(*owner),
                _ => None,
            })
            .collect();
        let released: Vec<_> = commands
            .iter()
            .filter_map(|command| match command {
                InputCommand::ReleaseAll { owner } => Some(*owner),
                _ => None,
            })
            .collect();
        assert_eq!(pressed.len(), 2);
        assert_ne!(pressed[0], pressed[1]);
        assert_eq!(released.len(), 1);
        assert!(pressed.contains(&released[0]));
    }

    #[tokio::test]
    async fn a_reconnecting_phone_replaces_its_previous_connection() {
        let harness = start_server(None).await;
        let (mut previous, device_key) = harness.pair("phone-1").await;
        send(
            &mut previous,
            client_message::Body::ButtonAction(ButtonAction {
                button: protocol::MouseButton::Left.into(),
                kind: Kind::Press.into(),
            }),
        )
        .await;
        harness.wait_for_input(|command| matches!(command, InputCommand::Press { .. })).await;

        // 换网后手机端连上了新连接，旧连接还没等到心跳超时。
        let (_current, reply) = harness.connect("phone-1", hello::Credential::DeviceKey(device_key)).await;
        assert!(matches!(reply, server_message::Body::Welcome(_)));

        let closed = timeout(Duration::from_secs(2), previous.next()).await.unwrap();
        assert!(!matches!(closed, Some(Ok(tungstenite::Message::Binary(_)))));
        harness.wait_for_release_all().await;
        let commands = harness.input.commands();
        let InputCommand::Press { owner, .. } = commands[0] else {
            panic!("expected a press, got {commands:?}");
        };
        assert_eq!(commands[1..], [InputCommand::ReleaseAll { owner }]);
        harness.wait_for_phones(&[("Phone phone-1", true)]).await;
    }

    #[tokio::test]
    async fn unpairing_a_phone_closes_its_connection_and_revokes_its_key() {
        let harness = start_server(None).await;
        let (mut client, device_key) = harness.pair("phone-1").await;
        harness.wait_for_phones(&[("Phone phone-1", true)]).await;

        harness.context.unpair("phone-1").unwrap();

        let closed = timeout(Duration::from_secs(2), client.next()).await.unwrap();
        assert!(!matches!(closed, Some(Ok(tungstenite::Message::Binary(_)))));
        harness.wait_for_phones(&[]).await;
        let (_, reply) = harness.connect("phone-1", hello::Credential::DeviceKey(device_key)).await;
        assert!(matches!(reply, server_message::Body::Rejected(_)));
    }

    #[tokio::test]
    async fn an_online_phone_can_be_asked_to_switch_over() {
        let harness = start_server(None).await;
        let (mut client, _) = harness.pair("phone-1").await;
        harness.wait_for_phones(&[("Phone phone-1", true)]).await;

        harness.context.request_switch("phone-1");

        assert!(matches!(receive(&mut client).await, server_message::Body::SwitchRequest(_)));
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
    async fn zoom_messages_reach_the_input() {
        let harness = start_server(None).await;
        let mut client = paired_client(&harness).await;

        for (phase, magnification) in [(ZoomPhase::Began, 0.0), (ZoomPhase::Changed, 0.25), (ZoomPhase::Ended, 0.0)] {
            send(
                &mut client,
                client_message::Body::Zoom(Zoom {
                    phase: phase.into(),
                    magnification,
                }),
            )
            .await;
        }
        client.close(None).await.unwrap();
        harness.wait_for_release_all().await;

        let commands = harness.input.commands();
        let InputCommand::ZoomStart { owner } = commands[0] else {
            panic!("expected the zoom to start, got {commands:?}");
        };
        assert_eq!(
            commands[1..3],
            [
                InputCommand::Zoom {
                    owner,
                    magnification: 0.25
                },
                InputCommand::ZoomEnd { owner },
            ]
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

        let commands = harness.input.commands();
        let InputCommand::Press { owner, button: MouseButton::Left } = commands[0] else {
            panic!("expected a press, got {commands:?}");
        };
        assert_eq!(commands[1..], [InputCommand::ReleaseAll { owner }]);
    }

    #[tokio::test]
    async fn dictation_commits_the_cleaned_text() {
        let harness = start_server(Some(Arc::new(ScriptedDictation))).await;
        let mut client = paired_client(&harness).await;

        send(&mut client, client_message::Body::DictationStart(DictationStart { session_id: 1 })).await;
        send(&mut client, client_message::Body::AudioChunk(AudioChunk { session_id: 1, pcm: vec![0; 3200] })).await;
        send(&mut client, client_message::Body::DictationStop(DictationStop { session_id: 1 })).await;

        assert!(matches!(
            receive(&mut client).await,
            server_message::Body::DictationFinished(finished) if finished.cleanup_failure.is_empty()
        ));
        assert!(harness.input.commands().contains(&InputCommand::Text("GOT 3200 BYTES".into())));
    }

    #[tokio::test]
    async fn cancelled_dictation_commits_nothing() {
        let harness = start_server(Some(Arc::new(ScriptedDictation))).await;
        let mut client = paired_client(&harness).await;

        send(&mut client, client_message::Body::DictationStart(DictationStart { session_id: 1 })).await;
        send(&mut client, client_message::Body::AudioChunk(AudioChunk { session_id: 1, pcm: vec![0; 3200] })).await;
        send(&mut client, client_message::Body::DictationCancel(DictationCancel { session_id: 1 })).await;
        // 取消之后再松开也不会上屏：这次语音输入已经被丢弃了。
        send(&mut client, client_message::Body::DictationStop(DictationStop { session_id: 1 })).await;
        client.close(None).await.unwrap();
        harness.wait_for_release_all().await;
        tokio::time::sleep(Duration::from_millis(50)).await;

        assert!(!harness.input.commands().iter().any(|command| matches!(command, InputCommand::Text(_))));
    }

    #[tokio::test]
    async fn disconnecting_mid_dictation_commits_nothing() {
        let harness = start_server(Some(Arc::new(ScriptedDictation))).await;
        let mut client = paired_client(&harness).await;

        send(&mut client, client_message::Body::DictationStart(DictationStart { session_id: 1 })).await;
        send(&mut client, client_message::Body::AudioChunk(AudioChunk { session_id: 1, pcm: vec![0; 3200] })).await;
        drop(client);
        harness.wait_for_release_all().await;
        tokio::time::sleep(Duration::from_millis(50)).await;

        assert!(!harness.input.commands().iter().any(|command| matches!(command, InputCommand::Text(_))));
    }

    #[tokio::test]
    async fn typing_keys_and_shortcuts_reach_the_input() {
        let harness = start_server(None).await;
        let mut client = paired_client(&harness).await;

        send(&mut client, client_message::Body::TypeText(TypeText { text: "你好 world".into() })).await;
        send(&mut client, client_message::Body::KeyPress(KeyPress { key: key_press::Key::Enter.into() })).await;
        send(&mut client, client_message::Body::KeyPress(KeyPress { key: key_press::Key::PlayPause.into() })).await;
        send(
            &mut client,
            client_message::Body::ShortcutAction(ShortcutAction { shortcut: shortcut_action::Shortcut::Paste.into() }),
        )
        .await;
        client.close(None).await.unwrap();
        harness.wait_for_release_all().await;

        assert_eq!(
            harness.input.commands()[..4],
            [
                InputCommand::Text("你好 world".into()),
                InputCommand::Key(SpecialKey::Enter),
                InputCommand::Key(SpecialKey::PlayPause),
                InputCommand::Shortcut(Shortcut::Paste),
            ]
        );
    }

    #[tokio::test]
    async fn a_new_lan_address_updates_the_pairing_code() {
        let harness = start_server(None).await;

        harness.context.update_host(IpAddr::from([192, 168, 1, 30]));

        let link = harness.context.status.snapshot().pairing_link;
        assert!(link.starts_with("echopad://pair?host=192.168.1.30&port=47321&"));
    }

    #[tokio::test]
    async fn dictation_without_configuration_fails_with_a_message() {
        let harness = start_server(None).await;
        let mut client = paired_client(&harness).await;

        send(&mut client, client_message::Body::DictationStart(DictationStart { session_id: 9 })).await;

        assert!(matches!(
            receive(&mut client).await,
            server_message::Body::DictationFailed(DictationFailed { session_id: 9, .. })
        ));
    }
}
