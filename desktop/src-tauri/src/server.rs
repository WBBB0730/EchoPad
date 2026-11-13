//! 手机端连接的 WebSocket 服务（ADR-0003、ADR-0004、ADR-0014、ADR-0015、docs/prd/pairing.md），
//! 以及在手机端之间转发剪贴板（docs/prd/clipboard-sync.md）。

use std::{
    collections::{HashMap, HashSet},
    net::IpAddr,
    sync::{
        Arc, Mutex, RwLock,
        atomic::{AtomicBool, AtomicU64, Ordering},
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
use socket2::{SockRef, TcpKeepalive};
use tokio_rustls::{TlsAcceptor, rustls::ServerConfig};
use tower_service::Service;

use crate::{
    clipboard::{ClipboardSink, MAX_TEXT_BYTES, SyncedClipboard, now_millis},
    dictation::{DictationHandle, DictationService},
    input::{InputCommand, InputSink, Modifier, MouseButton, Owner, Shortcut, SpecialKey},
    pairing::{AuthOutcome, Credential, DeviceId, PairingStore, pairing_link},
    protocol::{
        self, ClientMessage, ClipboardText, DictationFailed, Hello, ModifierKey, OperatingSystem, Rejected, ServerMessage,
        SwitchRequest, Welcome, button_action::Kind, client_message, hello, key_press, rejected, server_message, shortcut_action,
        zoom::Phase as ZoomPhase,
    },
    status::{OutdatedApp, PhoneStatus, StatusHub},
};

pub const PORT: u16 = 47321;
/// 电脑端的协议版本（ADR-0010）。
pub const PROTOCOL_VERSION: u32 = protocol::ProtocolVersion::Current as u32;

/// 连接建立后必须在这段时间内完成验证。
const HELLO_TIMEOUT: Duration = Duration::from_secs(5);
/// 手机端每 2 秒发一次心跳。它有操作进行中时，超过这段时间收不到任何帧就认为已断线（ADR-0019）。
const IDLE_TIMEOUT: Duration = Duration::from_secs(6);
/// 其余时间靠 TCP 保活发现断线：30 秒没有数据开始探测，每 10 秒一次，3 次没有回应就断开（ADR-0019，
/// 和 KDE Connect 电脑端的参数相同）。探测由手机的系统内核回应，手机休眠时连接保持。
const KEEPALIVE: TcpKeepalive = TcpKeepalive::new()
    .with_time(Duration::from_secs(30))
    .with_interval(Duration::from_secs(10))
    .with_retries(3);

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
    clipboard: Arc<dyn ClipboardSink>,
    /// 电脑端是否开着剪贴板同步，保存设置后更新。
    clipboard_enabled: AtomicBool,
    /// 电脑剪贴板上的内容和它最后更新的时间（ADR-0018）。写系统剪贴板也在这把锁里，几部手机同时发来的内容按顺序写入。
    synced_clipboard: Mutex<SyncedClipboard>,
    /// 当前的连接：取消配对或同一部手机重新连上时据此关闭连接，让手机端切换过来时据此给它发消息。
    sessions: Mutex<HashMap<Owner, Session>>,
    next_session_id: AtomicU64,
}

struct Session {
    device: DeviceId,
    outbound: mpsc::UnboundedSender<ServerMessage>,
    close: Arc<Notify>,
    /// 这部手机端是否开着剪贴板同步，它告诉电脑端之前当作关着。
    clipboard_sync: bool,
}

impl ServerContext {
    pub fn new(
        desktop_name: String,
        host: IpAddr,
        fingerprint: String,
        pairing: PairingStore,
        status: Arc<StatusHub>,
        input: Arc<dyn InputSink>,
        clipboard: Arc<dyn ClipboardSink>,
    ) -> Self {
        let context = Self {
            desktop_name,
            host: Mutex::new(host),
            fingerprint,
            pairing: Mutex::new(pairing),
            status,
            input,
            dictation: RwLock::new(None),
            clipboard,
            clipboard_enabled: AtomicBool::new(false),
            synced_clipboard: Mutex::new(SyncedClipboard::unknown_since(now_millis())),
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

    /// 打开剪贴板同步的那一刻起算，之前电脑上的复制都算旧的（ADR-0018）。
    pub fn set_clipboard_enabled(&self, enabled: bool) {
        if enabled && !self.clipboard_enabled() {
            *self.synced_clipboard.lock().unwrap() = SyncedClipboard::unknown_since(now_millis());
        }
        self.clipboard_enabled.store(enabled, Ordering::Relaxed);
    }

    pub fn clipboard_enabled(&self) -> bool {
        self.clipboard_enabled.load(Ordering::Relaxed)
    }

    /// 用户在电脑上复制了文字（电脑端关着同步时不会读到）：和现有内容不同就发给所有开着剪贴板同步的在线手机端。
    pub fn share_clipboard(&self, text: String) {
        if text.len() > MAX_TEXT_BYTES {
            return;
        }
        let copied = {
            let mut synced = self.synced_clipboard.lock().unwrap();
            if synced.text() == Some(text.as_str()) {
                return;
            }
            synced.copy(text, now_millis())
        };
        self.send_clipboard(copied, None);
    }

    /// 一部手机端发来了剪贴板：比现有的新才写进电脑剪贴板，再转给其他开着同步的在线手机端，不发回给它。
    fn receive_clipboard(&self, from: Owner, clipboard: ClipboardText) {
        if !self.clipboard_enabled() || clipboard.text.len() > MAX_TEXT_BYTES {
            return;
        }
        {
            let mut synced = self.synced_clipboard.lock().unwrap();
            if !synced.merge(&clipboard) {
                return;
            }
            self.clipboard.write(&clipboard.text);
        }
        self.send_clipboard(clipboard, Some(from));
    }

    /// 发给开着剪贴板同步的在线手机端；`except` 是发来这条内容的连接，不发回给它。
    fn send_clipboard(&self, clipboard: ClipboardText, except: Option<Owner>) {
        let message = ServerMessage {
            body: Some(server_message::Body::ClipboardText(clipboard)),
        };
        for (owner, session) in self.sessions.lock().unwrap().iter() {
            if session.clipboard_sync && Some(*owner) != except {
                let _ = session.outbound.send(message.clone());
            }
        }
    }

    fn set_clipboard_sync(&self, owner: Owner, enabled: bool) {
        if let Some(session) = self.sessions.lock().unwrap().get_mut(&owner) {
            session.clipboard_sync = enabled;
        }
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
        if let Err(err) = SockRef::from(&stream).set_tcp_keepalive(&KEEPALIVE) {
            tracing::warn!(%address, "failed to enable TCP keepalive: {err}");
        }
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
                clipboard_sync: false,
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

    let mut ongoing = Ongoing::default();
    loop {
        // 有操作进行中时，任何帧（包括心跳）都会重置超时，超时按断线处理；其余时间由 TCP 保活发现断线（ADR-0019）。
        let watched = ongoing.in_progress();
        let received = tokio::select! {
            _ = close.notified() => {
                tracing::info!(device = %device_name, "connection closed by the desktop app");
                break;
            }
            received = async {
                if watched {
                    timeout(IDLE_TIMEOUT, reader.next()).await
                } else {
                    Ok(reader.next().await)
                }
            } => received,
        };
        let frame = match received {
            Ok(Some(Ok(frame))) => frame,
            Ok(Some(Err(err))) => {
                tracing::debug!("connection failed: {err}");
                break;
            }
            Ok(None) => break,
            // 等的时候进行中的操作已经结束（例如语音输入整理完了），之后不再看心跳。
            Err(_) if !ongoing.in_progress() => continue,
            Err(_) => {
                tracing::debug!("heartbeat timed out");
                break;
            }
        };
        match frame {
            Message::Binary(bytes) => match ClientMessage::decode(bytes) {
                Ok(ClientMessage { body: Some(body) }) => {
                    dispatch(&context, session_id, body, &mut ongoing, &outbound);
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
    drop(ongoing);
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
        operating_system: OPERATING_SYSTEM.into(),
    })
}

/// 手机端按它显示修饰键的名称。
const OPERATING_SYSTEM: OperatingSystem = if cfg!(target_os = "macos") {
    OperatingSystem::Macos
} else {
    OperatingSystem::Windows
};

/// 一部手机端进行中的操作：按住的鼠标键、缩放和语音输入。有操作进行中时电脑端看心跳（ADR-0019）。
#[derive(Default)]
struct Ongoing {
    buttons: HashSet<MouseButton>,
    zooming: bool,
    dictations: HashMap<u32, DictationHandle>,
}

impl Ongoing {
    fn in_progress(&mut self) -> bool {
        self.dictations.retain(|_, handle| !handle.is_finished());
        !self.buttons.is_empty() || self.zooming || !self.dictations.is_empty()
    }
}

fn dispatch(
    context: &ServerContext,
    owner: Owner,
    body: client_message::Body,
    ongoing: &mut Ongoing,
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
            let modifiers = modifiers(action.modifiers());
            let command = match action.kind() {
                Kind::Click => InputCommand::Click { button, modifiers },
                Kind::DoubleClick => InputCommand::DoubleClick { button, modifiers },
                Kind::Press => {
                    ongoing.buttons.insert(button);
                    InputCommand::Press { owner, button, modifiers }
                }
                Kind::Release => {
                    ongoing.buttons.remove(&button);
                    InputCommand::Release { owner, button }
                }
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
                ZoomPhase::Began => {
                    ongoing.zooming = true;
                    InputCommand::ZoomStart { owner }
                }
                ZoomPhase::Changed => InputCommand::Zoom {
                    owner,
                    magnification: zoom.magnification,
                },
                ZoomPhase::Ended => {
                    ongoing.zooming = false;
                    InputCommand::ZoomEnd { owner }
                }
                ZoomPhase::Unspecified => return,
            };
            context.input.send(command);
        }
        Body::DictationStart(start) => {
            let service = context.dictation.read().unwrap().clone();
            match service {
                Some(service) => {
                    let handle = service.start(start.session_id, context.input.clone(), outbound.clone());
                    ongoing.dictations.insert(start.session_id, handle);
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
            if let Some(handle) = ongoing.dictations.get(&chunk.session_id) {
                handle.push_audio(chunk.pcm);
            }
        }
        Body::DictationStop(stop) => {
            if let Some(handle) = ongoing.dictations.get(&stop.session_id) {
                handle.finish();
            }
        }
        // 丢弃进行中的语音输入：识别和整理都停下，什么都不上屏。
        Body::DictationCancel(cancel) => {
            ongoing.dictations.remove(&cancel.session_id);
        }
        Body::TypeText(typed) => {
            if !typed.text.is_empty() {
                context.input.send(InputCommand::Text(typed.text));
            }
        }
        Body::KeyPress(press) => {
            if let Some(key) = special_key(press.key()) {
                let modifiers = modifiers(press.modifiers());
                context.input.send(InputCommand::Key { key, modifiers });
            }
        }
        Body::CharacterPress(press) => {
            let mut characters = press.character.chars();
            if let (Some(character), None) = (characters.next(), characters.next()) {
                let modifiers = modifiers(press.modifiers());
                context.input.send(InputCommand::Character { character, modifiers });
            }
        }
        Body::ShortcutAction(action) => {
            if let Some(shortcut) = shortcut(action.shortcut()) {
                context.input.send(InputCommand::Shortcut(shortcut));
            }
        }
        Body::ClipboardSyncSetting(setting) => context.set_clipboard_sync(owner, setting.enabled),
        Body::ClipboardText(clipboard) => context.receive_clipboard(owner, clipboard),
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

fn modifiers(modifiers: impl Iterator<Item = ModifierKey>) -> Vec<Modifier> {
    modifiers
        .filter_map(|modifier| match modifier {
            ModifierKey::Control => Some(Modifier::Control),
            ModifierKey::Alt => Some(Modifier::Alt),
            ModifierKey::Shift => Some(Modifier::Shift),
            ModifierKey::Meta => Some(Modifier::Meta),
            ModifierKey::Unspecified => None,
        })
        .collect()
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
            AudioChunk, ButtonAction, CharacterPress, ClipboardSyncSetting, DictationCancel, DictationStart, DictationStop,
            KeyPress, PointerMove, ShortcutAction, TypeText, Zoom,
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

    #[derive(Default)]
    struct RecordingClipboard(Mutex<Vec<String>>);

    impl ClipboardSink for RecordingClipboard {
        fn write(&self, text: &str) {
            self.0.lock().unwrap().push(text.to_owned());
        }
    }

    impl RecordingClipboard {
        fn written(&self) -> Vec<String> {
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
        clipboard: Arc<RecordingClipboard>,
        address: SocketAddr,
        certificate: CertificateDer<'static>,
    }

    async fn start_server(dictation: Option<Arc<dyn DictationService>>) -> Harness {
        let dir = std::env::temp_dir().join(format!("echopad-server-{}", uuid::Uuid::new_v4()));
        let certificate = DesktopCertificate::load_or_create(&dir).unwrap();
        let pairing = PairingStore::load(dir.join("paired-devices.json")).unwrap();
        let host = IpAddr::from(Ipv4Addr::LOCALHOST);
        let input = Arc::new(RecordingInput::default());
        let clipboard = Arc::new(RecordingClipboard::default());
        let status = Arc::new(StatusHub::new(|_| {}));
        let context = Arc::new(ServerContext::new(
            "Test Mac".into(),
            host,
            certificate.fingerprint(),
            pairing,
            status,
            input.clone(),
            clipboard.clone(),
        ));
        context.set_dictation(dictation);
        context.set_clipboard_enabled(true);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(serve(listener, context.clone(), certificate.server_config().unwrap()));
        Harness {
            context,
            input,
            clipboard,
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

        /// 等电脑端记下有几部手机端开着剪贴板同步。
        async fn wait_for_clipboard_sync(&self, expected: usize) {
            for _ in 0..100 {
                let count = self.context.sessions.lock().unwrap().values().filter(|session| session.clipboard_sync).count();
                if count == expected {
                    return;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            panic!("{expected} phones never turned on clipboard sync");
        }

        /// 等电脑端处理完这部手机端之前发来的消息：同一条连接上的消息按顺序处理。
        async fn wait_until_handled(&self, client: &mut Client) {
            send(client, client_message::Body::PointerMove(PointerMove { dx: 1.0, dy: 0.0 })).await;
            self.wait_for_input(|command| matches!(command, InputCommand::Move { .. })).await;
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

    /// 一会儿之内电脑端没有给这部手机端发任何消息。
    async fn assert_nothing_received(client: &mut Client) {
        let next = timeout(Duration::from_millis(200), client.next()).await;
        assert!(next.is_err(), "unexpected message: {next:?}");
    }

    async fn set_clipboard_sync(client: &mut Client, enabled: bool) {
        send(client, client_message::Body::ClipboardSyncSetting(ClipboardSyncSetting { enabled })).await;
    }

    /// 电脑端打开同步之后的某个时刻。
    fn later(millis: i64) -> i64 {
        now_millis() + millis
    }

    fn clipboard(text: &str, updated_at: i64) -> ClipboardText {
        ClipboardText { text: text.into(), updated_at }
    }

    async fn send_clipboard(client: &mut Client, clipboard: &ClipboardText) {
        send(client, client_message::Body::ClipboardText(clipboard.clone())).await;
    }

    #[tokio::test]
    async fn newer_clipboard_from_a_phone_reaches_the_desktop_and_other_phones_with_sync_on() {
        let harness = start_server(None).await;
        let (mut sender, _) = harness.pair("phone-1").await;
        let (mut syncing, _) = harness.pair("phone-2").await;
        let (mut not_syncing, _) = harness.pair("phone-3").await;
        set_clipboard_sync(&mut sender, true).await;
        set_clipboard_sync(&mut syncing, true).await;
        harness.wait_for_clipboard_sync(2).await;

        let copied = clipboard("hello", later(1_000));
        send_clipboard(&mut sender, &copied).await;
        assert_eq!(receive(&mut syncing).await, server_message::Body::ClipboardText(copied));
        assert_eq!(harness.clipboard.written(), ["hello"]);
        assert_nothing_received(&mut sender).await;
        assert_nothing_received(&mut not_syncing).await;
    }

    #[tokio::test]
    async fn clipboard_that_is_not_newer_stops_here() {
        let harness = start_server(None).await;
        let (mut first, _) = harness.pair("phone-1").await;
        let (mut second, _) = harness.pair("phone-2").await;
        set_clipboard_sync(&mut first, true).await;
        set_clipboard_sync(&mut second, true).await;
        harness.wait_for_clipboard_sync(2).await;

        let copied = clipboard("hello", later(1_000));
        send_clipboard(&mut first, &copied).await;
        assert!(matches!(receive(&mut second).await, server_message::Body::ClipboardText(_)));
        // 同一条内容经别的电脑转了一圈回来，以及更早的内容，都不再写入、也不再转发。
        send_clipboard(&mut second, &copied).await;
        send_clipboard(&mut second, &clipboard("older", later(500))).await;
        harness.wait_until_handled(&mut second).await;
        assert_eq!(harness.clipboard.written(), ["hello"]);
        assert_nothing_received(&mut first).await;
    }

    #[tokio::test]
    async fn clipboard_copied_on_two_phones_at_once_settles_on_the_newer_one() {
        let harness = start_server(None).await;
        let (mut first, _) = harness.pair("phone-1").await;
        let (mut second, _) = harness.pair("phone-2").await;
        set_clipboard_sync(&mut first, true).await;
        set_clipboard_sync(&mut second, true).await;
        harness.wait_for_clipboard_sync(2).await;

        let newer = clipboard("newer", later(2_000));
        send_clipboard(&mut first, &newer).await;
        send_clipboard(&mut second, &clipboard("older", later(1_000))).await;
        harness.wait_until_handled(&mut first).await;
        harness.wait_until_handled(&mut second).await;
        assert_eq!(harness.clipboard.written().last().map(String::as_str), Some("newer"));
        assert_eq!(receive(&mut second).await, server_message::Body::ClipboardText(newer));
    }

    #[tokio::test]
    async fn text_copied_on_the_desktop_reaches_phones_with_sync_on() {
        let harness = start_server(None).await;
        let (mut syncing, _) = harness.pair("phone-1").await;
        let (mut not_syncing, _) = harness.pair("phone-2").await;
        set_clipboard_sync(&mut syncing, true).await;
        harness.wait_for_clipboard_sync(1).await;

        let before = now_millis();
        harness.context.share_clipboard("copied".into());
        let server_message::Body::ClipboardText(copied) = receive(&mut syncing).await else {
            panic!("expected the clipboard");
        };
        assert_eq!(copied.text, "copied");
        assert!(copied.updated_at >= before);
        assert_nothing_received(&mut not_syncing).await;

        // 内容没变不算复制；太长的文字不同步。
        harness.context.share_clipboard("copied".into());
        harness.context.share_clipboard("x".repeat(MAX_TEXT_BYTES + 1));
        assert_nothing_received(&mut syncing).await;
    }

    #[tokio::test]
    async fn earlier_copies_are_not_sent_when_a_phone_turns_sync_on() {
        let harness = start_server(None).await;
        let mut client = paired_client(&harness).await;
        harness.context.share_clipboard("copied before the phone turned sync on".into());

        // 重连或者打开同步时都会发这条消息；之前的复制不补发，之后的照常推送。
        set_clipboard_sync(&mut client, true).await;
        harness.wait_for_clipboard_sync(1).await;
        assert_nothing_received(&mut client).await;
        harness.context.share_clipboard("copied afterwards".into());
        let server_message::Body::ClipboardText(copied) = receive(&mut client).await else {
            panic!("expected the clipboard");
        };
        assert_eq!(copied.text, "copied afterwards");
    }

    #[tokio::test]
    async fn a_phone_can_turn_clipboard_sync_off_again() {
        let harness = start_server(None).await;
        let mut client = paired_client(&harness).await;
        set_clipboard_sync(&mut client, true).await;
        harness.wait_for_clipboard_sync(1).await;
        set_clipboard_sync(&mut client, false).await;
        harness.wait_for_clipboard_sync(0).await;

        harness.context.share_clipboard("copied".into());
        assert_nothing_received(&mut client).await;
    }

    #[tokio::test]
    async fn a_desktop_with_clipboard_sync_off_ignores_clipboard_from_phones() {
        let harness = start_server(None).await;
        harness.context.set_clipboard_enabled(false);
        let mut client = paired_client(&harness).await;
        set_clipboard_sync(&mut client, true).await;
        harness.wait_for_clipboard_sync(1).await;

        send_clipboard(&mut client, &clipboard("hello", later(1_000))).await;
        harness.wait_until_handled(&mut client).await;
        assert!(harness.clipboard.written().is_empty());
        assert_nothing_received(&mut client).await;
    }

    #[tokio::test]
    async fn pairing_issues_a_device_key_that_works_for_later_connections() {
        let harness = start_server(None).await;
        let token = harness.pairing_token();
        let link = harness.context.status.snapshot().pairing_link;
        assert!(link.contains(&format!("fp={}", harness.context.fingerprint)));

        let (_, reply) = harness.connect("phone-1", hello::Credential::PairingToken(token.clone())).await;
        let server_message::Body::Welcome(welcome) = reply else {
            panic!("expected pairing to succeed, got {reply:?}");
        };
        let Welcome { desktop_name, device_key, desktop_id, .. } = welcome.clone();
        assert_eq!(desktop_name, "Test Mac");
        assert_eq!(welcome.operating_system(), OPERATING_SYSTEM);
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
            ..Default::default()
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
                ..Default::default()
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
                ..Default::default()
            }),
        )
        .await;
        client.close(None).await.unwrap();
        harness.wait_for_release_all().await;

        assert_eq!(
            harness.input.commands()[..2],
            [InputCommand::Move { dx: 3.0, dy: -2.0 }, InputCommand::Click { button: MouseButton::Right, modifiers: vec![] }]
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
                ..Default::default()
            }),
        )
        .await;
        drop(client);
        harness.wait_for_release_all().await;

        let commands = harness.input.commands();
        let InputCommand::Press { owner, button: MouseButton::Left, .. } = commands[0] else {
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
        send(&mut client, client_message::Body::KeyPress(KeyPress { key: key_press::Key::Enter.into(), ..Default::default() })).await;
        send(&mut client, client_message::Body::KeyPress(KeyPress { key: key_press::Key::PlayPause.into(), ..Default::default() })).await;
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
                InputCommand::Key { key: SpecialKey::Enter, modifiers: vec![] },
                InputCommand::Key { key: SpecialKey::PlayPause, modifiers: vec![] },
                InputCommand::Shortcut(Shortcut::Paste),
            ]
        );
    }

    #[tokio::test]
    async fn modifiers_reach_the_input_with_keys_characters_and_clicks() {
        let harness = start_server(None).await;
        let mut client = paired_client(&harness).await;
        let modifiers = vec![ModifierKey::Meta.into(), ModifierKey::Shift.into()];

        send(
            &mut client,
            client_message::Body::KeyPress(KeyPress { key: key_press::Key::Left.into(), modifiers: modifiers.clone() }),
        )
        .await;
        send(
            &mut client,
            client_message::Body::CharacterPress(CharacterPress { character: "w".into(), modifiers: modifiers.clone() }),
        )
        .await;
        // 不是一个字的不发送。
        send(
            &mut client,
            client_message::Body::CharacterPress(CharacterPress { character: "ab".into(), modifiers: modifiers.clone() }),
        )
        .await;
        send(
            &mut client,
            client_message::Body::ButtonAction(ButtonAction {
                button: protocol::MouseButton::Left.into(),
                kind: Kind::Click.into(),
                modifiers: vec![ModifierKey::Meta.into()],
            }),
        )
        .await;
        send(
            &mut client,
            client_message::Body::ButtonAction(ButtonAction {
                button: protocol::MouseButton::Left.into(),
                kind: Kind::Press.into(),
                modifiers: vec![ModifierKey::Alt.into()],
            }),
        )
        .await;
        client.close(None).await.unwrap();
        harness.wait_for_release_all().await;

        let commands = harness.input.commands();
        let both = vec![Modifier::Meta, Modifier::Shift];
        assert_eq!(
            commands[..3],
            [
                InputCommand::Key { key: SpecialKey::Left, modifiers: both.clone() },
                InputCommand::Character { character: 'w', modifiers: both },
                InputCommand::Click { button: MouseButton::Left, modifiers: vec![Modifier::Meta] },
            ]
        );
        assert!(matches!(
            &commands[3],
            InputCommand::Press { button: MouseButton::Left, modifiers, .. } if *modifiers == [Modifier::Alt]
        ));
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
