//! 手机端连接的 WebSocket 服务（ADR-0003、ADR-0004、ADR-0014、ADR-0015、docs/prd/pairing.md），
//! 在手机端之间转发剪贴板（docs/prd/clipboard-sync.md），以及发送文件的上传、下载接口（docs/prd/send-files.md、ADR-0021）。

use std::{
    collections::{HashMap, HashSet},
    net::IpAddr,
    path::PathBuf,
    sync::{
        Arc, Mutex, RwLock,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::Duration,
};

use axum::{
    Router,
    extract::{
        DefaultBodyLimit, FromRequestParts, Multipart, Path, Request, State, WebSocketUpgrade,
        ws::{Message, WebSocket},
    },
    http::{StatusCode, header, request::Parts},
    response::{IntoResponse, Response},
    routing::{get, post},
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
    clipboard::{Clipboard, ClipboardContent, MAX_TEXT_BYTES, SyncResult, SyncedClipboard, now_millis},
    files::{self, OutgoingFile, PendingFiles},
    settings::ClipboardSettings,
    dictation::{DictationHandle, DictationService},
    input::{InputCommand, InputSink, Modifier, MouseButton, Owner, Shortcut, SpecialKey},
    pairing::{AuthOutcome, Credential, DeviceId, PairedPhone, PairingStore, pairing_link},
    protocol::{
        self, ClientMessage, ClipboardText, DictationFailed, FileOffer, FileOfferResult, Hello, ModifierKey, OperatingSystem,
        Rejected, ServerMessage,
        SwitchRequest, Welcome, button_action::Kind, client_message, file_offer_result, hello, key_press, rejected, server_message,
        shortcut_action,
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

/// 电脑端操作的系统功能，测试里换成假的。
pub struct System {
    pub input: Arc<dyn InputSink>,
    pub clipboard: Arc<dyn Clipboard>,
    /// 手机端发来的文件存进这个文件夹。
    pub downloads: PathBuf,
    /// 显示系统通知。
    pub notify: Box<dyn Fn(String) + Send + Sync>,
}

/// 用户在电脑端主动同步剪贴板。
pub enum ClipboardSync {
    Done(SyncResult),
    /// 剪贴板里是文件或图片，等用户确认后再发（docs/prd/send-files.md）。
    Confirm(PendingFiles),
}

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
    clipboard: Arc<dyn Clipboard>,
    downloads: PathBuf,
    notify: Box<dyn Fn(String) + Send + Sync>,
    /// 剪贴板同步的两个开关，保存设置后更新。
    clipboard_auto_send: AtomicBool,
    clipboard_receive: AtomicBool,
    /// 剪贴板同步里最新的一条（ADR-0018）。写系统剪贴板也在这把锁里，几部手机同时发来的内容按顺序写入。
    synced_clipboard: Mutex<SyncedClipboard>,
    /// 当前的连接：取消配对或同一部手机重新连上时据此关闭连接，让手机端切换过来时据此给它发消息。
    sessions: Mutex<HashMap<Owner, Session>>,
    next_session_id: AtomicU64,
}

struct Session {
    device: DeviceId,
    outbound: mpsc::UnboundedSender<ServerMessage>,
    close: Arc<Notify>,
    /// 发给这部手机端、还没下载的文件，按清单标识和序号存放（ADR-0021）。每个只能下载一次，断线时随连接作废。
    offered: HashMap<(String, u32), OutgoingFile>,
}

impl ServerContext {
    pub fn new(
        desktop_name: String,
        host: IpAddr,
        fingerprint: String,
        pairing: PairingStore,
        status: Arc<StatusHub>,
        system: System,
    ) -> Self {
        let System { input, clipboard, downloads, notify } = system;
        let context = Self {
            desktop_name,
            host: Mutex::new(host),
            fingerprint,
            pairing: Mutex::new(pairing),
            status,
            input,
            dictation: RwLock::new(None),
            clipboard,
            downloads,
            notify,
            clipboard_auto_send: AtomicBool::new(false),
            clipboard_receive: AtomicBool::new(false),
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
        if let Some(phone) = self.phone(phone_id) {
            self.send_to(&phone, server_message::Body::SwitchRequest(SwitchRequest {}));
        }
    }

    /// 之后开始的语音输入使用新的服务，进行中的语音输入不受影响。
    pub fn set_dictation(&self, service: Option<Arc<dyn DictationService>>) {
        self.status.set_dictation_ready(service.is_some());
        *self.dictation.write().unwrap() = service;
    }

    pub fn set_clipboard(&self, settings: &ClipboardSettings) {
        self.clipboard_auto_send.store(settings.auto_send, Ordering::Relaxed);
        self.clipboard_receive.store(settings.receive, Ordering::Relaxed);
    }

    /// 电脑上复制的文字是否自动发给手机端；关着时不读电脑剪贴板。
    pub fn clipboard_auto_send(&self) -> bool {
        self.clipboard_auto_send.load(Ordering::Relaxed)
    }

    /// 用户在电脑上复制了文字（关着自动发送时不会读到）：和现有内容不同就发给所有在线的手机端。
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

    /// 用户在电脑端主动同步给手机端 `phone_id`（主窗口、菜单栏菜单里这部手机的"发送剪贴板"）。剪贴板上是文字时发给它，
    /// 算一次新的复制，即使内容和现有的相同也照发，不受两个开关影响；是文件或图片时先交给用户确认（docs/prd/send-files.md）。
    pub fn sync_clipboard(&self, phone_id: &str) -> ClipboardSync {
        let Some(phone) = self.phone(phone_id).filter(|phone| self.is_online(phone)) else {
            return ClipboardSync::Done(SyncResult::PhoneOffline);
        };
        let files = match self.clipboard.read() {
            None => return ClipboardSync::Done(SyncResult::NoText),
            Some(ClipboardContent::Text(text)) => return ClipboardSync::Done(self.sync_text(text, phone)),
            Some(ClipboardContent::Image(png)) => vec![OutgoingFile::image(png)],
            Some(ClipboardContent::Files(paths)) => match OutgoingFile::from_paths(paths) {
                Ok(files) if files.is_empty() => return ClipboardSync::Done(SyncResult::OnlyFolders),
                Ok(files) => files,
                Err(err) => {
                    tracing::warn!("failed to read the copied files: {err}");
                    return ClipboardSync::Done(SyncResult::FilesUnreadable);
                }
            },
        };
        ClipboardSync::Confirm(PendingFiles { files, phone: phone.name })
    }

    /// 用户在电脑端给手机端 `phone_id` 发一段文字（主窗口、菜单栏菜单里这部手机的"发送文本"）：效果和在电脑上复制这段文字
    /// 再发送剪贴板一样，只是不动电脑自己的剪贴板（docs/prd/clipboard-sync.md）。
    pub fn send_text(&self, phone_id: &str, text: String) -> SyncResult {
        match self.phone(phone_id).filter(|phone| self.is_online(phone)) {
            Some(phone) => self.sync_text(text, phone),
            None => SyncResult::PhoneOffline,
        }
    }

    fn sync_text(&self, text: String, phone: PairedPhone) -> SyncResult {
        if text.len() > MAX_TEXT_BYTES {
            return SyncResult::TooLong;
        }
        let copied = self.synced_clipboard.lock().unwrap().copy(text, now_millis());
        if !self.send_to(&phone, server_message::Body::ClipboardText(copied)) {
            return SyncResult::PhoneOffline;
        }
        SyncResult::TextSent(phone.name)
    }

    /// 把文件发给手机端 `phone_id`：通过连接告诉它有哪些文件，它再逐个下载（ADR-0021）。
    pub fn send_files(&self, phone_id: &str, files: Vec<OutgoingFile>) -> SyncResult {
        let Some(phone) = self.phone(phone_id) else {
            return SyncResult::PhoneOffline;
        };
        let offer_id = uuid::Uuid::new_v4().simple().to_string();
        let offer = ServerMessage {
            body: Some(server_message::Body::FileOffer(FileOffer {
                offer_id: offer_id.clone(),
                files: files.iter().map(|file| protocol::OfferedFile { name: file.name.clone(), size: file.size }).collect(),
            })),
        };
        let mut sent = false;
        for session in self.sessions.lock().unwrap().values_mut().filter(|session| session.device == phone.device) {
            session.offered.extend((0..).zip(&files).map(|(index, file)| ((offer_id.clone(), index), file.clone())));
            sent |= session.outbound.send(offer.clone()).is_ok();
        }
        if !sent {
            return SyncResult::PhoneOffline;
        }
        SyncResult::FilesSending(phone.name)
    }

    /// 手机端来下载清单里的一个文件：只给收到这份清单的手机端，每个文件只能下载一次。
    fn take_offered(&self, device: &DeviceId, offer_id: String, index: u32) -> Option<OutgoingFile> {
        let key = (offer_id, index);
        let mut sessions = self.sessions.lock().unwrap();
        sessions.values_mut().filter(|session| session.device == *device).find_map(|session| session.offered.remove(&key))
    }

    /// 手机端发来一批文件，`saved` 是已经存进"下载"文件夹的那些：收全了就放进电脑剪贴板；收全没收全都用系统通知提示
    /// （docs/prd/send-files.md）。
    fn receive_files(&self, saved: &[PathBuf], complete: bool) {
        let message = match (complete, saved.len()) {
            (true, count) => {
                self.clipboard.write_files(saved);
                format!("收到 {count} 个文件，已复制到剪贴板")
            }
            (false, 0) => "没能接收文件".into(),
            (false, count) => format!("接收中断，已保存 {count} 个文件"),
        };
        (self.notify)(message);
    }

    /// 手机端处理完电脑端发去的一份文件清单：清单作废，用系统通知提示结果，用户在手机上取消的不提示（docs/prd/send-files.md）。
    fn file_offer_result(&self, owner: Owner, result: FileOfferResult) {
        let device = {
            let mut sessions = self.sessions.lock().unwrap();
            let Some(session) = sessions.get_mut(&owner) else {
                return;
            };
            session.offered.retain(|(offer_id, _), _| *offer_id != result.offer_id);
            session.device.clone()
        };
        let phones = self.pairing.lock().unwrap().phones();
        let Some(phone) = phones.into_iter().find(|phone| phone.device == device) else {
            return;
        };
        let name = phone.name;
        let message = match result.outcome() {
            file_offer_result::Outcome::Saved => format!("{name} 已保存 {} 个文件", result.saved),
            file_offer_result::Outcome::Failed if result.saved == 0 => format!("{name} 没能接收文件"),
            file_offer_result::Outcome::Failed => format!("{name} 接收中断，已保存 {} 个文件", result.saved),
            file_offer_result::Outcome::Unsupported => format!("{name} 的系统版本太低，不能接收文件"),
            file_offer_result::Outcome::Cancelled | file_offer_result::Outcome::Unspecified => return,
        };
        (self.notify)(message);
    }

    /// 配过的手机端 `phone_id`，已经取消配对时为 `None`。
    fn phone(&self, phone_id: &str) -> Option<PairedPhone> {
        self.pairing.lock().unwrap().phones().into_iter().find(|phone| phone.id == phone_id)
    }

    fn is_online(&self, phone: &PairedPhone) -> bool {
        self.sessions.lock().unwrap().values().any(|session| session.device == phone.device)
    }

    /// 发给一部手机端，返回它是否在线、发出去了。
    fn send_to(&self, phone: &PairedPhone, body: server_message::Body) -> bool {
        let message = ServerMessage { body: Some(body) };
        let sessions = self.sessions.lock().unwrap();
        let mut sent = false;
        for session in sessions.values().filter(|session| session.device == phone.device) {
            sent |= session.outbound.send(message.clone()).is_ok();
        }
        sent
    }

    /// 一部手机端发来了剪贴板：比现有的新才用。开着接收就写进电脑剪贴板；不论接收开没开，都转给其他在线的手机端，
    /// 不发回给它（ADR-0018）。
    fn receive_clipboard(&self, from: Owner, clipboard: ClipboardText) {
        if clipboard.text.len() > MAX_TEXT_BYTES {
            return;
        }
        {
            let mut synced = self.synced_clipboard.lock().unwrap();
            if !synced.merge(&clipboard) {
                return;
            }
            if self.clipboard_receive.load(Ordering::Relaxed) {
                self.clipboard.write(&clipboard.text);
            }
        }
        self.send_clipboard(clipboard, Some(from));
    }

    /// 发给所有在线的手机端；`except` 是发来这条内容的连接，不发回给它。写不写进剪贴板由手机端自己决定。
    fn send_clipboard(&self, clipboard: ClipboardText, except: Option<Owner>) {
        let message = ServerMessage {
            body: Some(server_message::Body::ClipboardText(clipboard)),
        };
        for (_, session) in self.sessions.lock().unwrap().iter().filter(|(owner, _)| Some(**owner) != except) {
            let _ = session.outbound.send(message.clone());
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

/// 在 TLS 上提供 WebSocket 服务和发送文件的接口（ADR-0014、ADR-0021），每条连接各自握手，互不影响。
pub async fn serve(listener: TcpListener, context: Arc<ServerContext>, tls: Arc<ServerConfig>) {
    let router = Router::new()
        .route("/ws", get(upgrade))
        // 上传的文件没有大小上限。
        .route("/files", post(upload).layer(DefaultBodyLimit::disable()))
        .route("/files/{offer_id}/{index}", get(download))
        .with_state(context);
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

/// 发送文件的请求在请求头里带着设备密钥（ADR-0021），认得这把密钥才处理。
struct Paired(DeviceId);

impl FromRequestParts<Arc<ServerContext>> for Paired {
    type Rejection = StatusCode;

    async fn from_request_parts(parts: &mut Parts, context: &Arc<ServerContext>) -> Result<Self, Self::Rejection> {
        let key = parts.headers.get(header::AUTHORIZATION).and_then(|value| value.to_str().ok()?.strip_prefix("Bearer "));
        let device = key.and_then(|key| context.pairing.lock().unwrap().device_for_key(key));
        device.map(Self).ok_or(StatusCode::UNAUTHORIZED)
    }
}

/// 手机端发来一批文件：边收边存进"下载"文件夹，收完以后放进电脑剪贴板；中途断开时已经存好的保留。
async fn upload(State(context): State<Arc<ServerContext>>, _: Paired, multipart: Multipart) -> Result<StatusCode, StatusCode> {
    let mut saved = Vec::new();
    let received = save_uploaded(&context.downloads, multipart, &mut saved).await;
    context.receive_files(&saved, received.is_ok());
    received.map(|()| StatusCode::NO_CONTENT)
}

/// 把一个上传请求里的文件逐个存进 `dir`，存好一个就放进 `saved`。
async fn save_uploaded(dir: &std::path::Path, mut multipart: Multipart, saved: &mut Vec<PathBuf>) -> Result<(), StatusCode> {
    while let Some(field) = multipart.next_field().await.map_err(|err| err.status())? {
        let name = field.file_name().unwrap_or_default().to_owned();
        let path = files::save(dir, &name, field).await.map_err(|err| {
            tracing::warn!("failed to receive a file: {err}");
            StatusCode::INTERNAL_SERVER_ERROR
        })?;
        saved.push(path);
    }
    if saved.is_empty() {
        return Err(StatusCode::BAD_REQUEST);
    }
    Ok(())
}

/// 手机端下载电脑端发来的清单里的一个文件。
async fn download(
    State(context): State<Arc<ServerContext>>,
    Paired(device): Paired,
    Path((offer_id, index)): Path<(String, u32)>,
) -> Result<impl IntoResponse, StatusCode> {
    let file = context.take_offered(&device, offer_id, index).ok_or(StatusCode::NOT_FOUND)?;
    let (size, body) = file.body().await.map_err(|err| {
        tracing::warn!("failed to read {}: {err}", file.name);
        StatusCode::NOT_FOUND
    })?;
    Ok(([(header::CONTENT_LENGTH, size)], body))
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
                offered: HashMap::new(),
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
        Body::ClipboardText(clipboard) => context.receive_clipboard(owner, clipboard),
        Body::FileOfferResult(result) => context.file_offer_result(owner, result),
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
            AudioChunk, ButtonAction, CharacterPress, DictationCancel, DictationStart, DictationStop, KeyPress, PointerMove,
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

    /// 记下写入的内容，读到的是最后写入的那条。
    #[derive(Default)]
    struct RecordingClipboard(Mutex<Vec<ClipboardContent>>);

    impl Clipboard for RecordingClipboard {
        fn read(&self) -> Option<ClipboardContent> {
            self.0.lock().unwrap().last().cloned()
        }

        fn write(&self, text: &str) {
            self.copy(ClipboardContent::Text(text.into()));
        }

        fn write_files(&self, files: &[PathBuf]) {
            self.copy(ClipboardContent::Files(files.to_vec()));
        }
    }

    impl RecordingClipboard {
        /// 像用户在电脑上复制了这些内容。
        fn copy(&self, content: ClipboardContent) {
            self.0.lock().unwrap().push(content);
        }

        fn written(&self) -> Vec<ClipboardContent> {
            self.0.lock().unwrap().clone()
        }
    }

    fn text(text: &str) -> ClipboardContent {
        ClipboardContent::Text(text.into())
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
        downloads: PathBuf,
        notifications: Arc<Mutex<Vec<String>>>,
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
        let downloads = dir.join("Downloads");
        std::fs::create_dir_all(&downloads).unwrap();
        let notifications = Arc::new(Mutex::new(Vec::new()));
        let status = Arc::new(StatusHub::new(|_| {}));
        let notified = notifications.clone();
        let system = System {
            input: input.clone(),
            clipboard: clipboard.clone(),
            downloads: downloads.clone(),
            notify: Box::new(move |message| notified.lock().unwrap().push(message)),
        };
        let context = Arc::new(ServerContext::new("Test Mac".into(), host, certificate.fingerprint(), pairing, status, system));
        context.set_dictation(dictation);
        context.set_clipboard(&ClipboardSettings::default());
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(serve(listener, context.clone(), certificate.server_config().unwrap()));
        Harness {
            context,
            input,
            clipboard,
            downloads,
            notifications,
            address,
            certificate: certificate.der().clone(),
        }
    }

    impl Harness {
        fn pairing_token(&self) -> String {
            self.context.pairing.lock().unwrap().pairing_token().to_owned()
        }

        /// 像手机端一样只认这台电脑端的证书。
        fn tls_config(&self) -> ClientConfig {
            ClientConfig::builder_with_provider(Arc::new(provider()))
                .with_safe_default_protocol_versions()
                .unwrap()
                .dangerous()
                .with_custom_certificate_verifier(Arc::new(PinnedCertificate(self.certificate.clone())))
                .with_no_client_auth()
        }

        /// 像手机端一样建立加密连接。
        async fn open(&self) -> Client {
            let tcp = TcpStream::connect(self.address).await.unwrap();
            let tls = TlsConnector::from(Arc::new(self.tls_config()))
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

        /// 像手机端一样发 HTTPS 请求，`device_key` 放进请求头。
        fn request(&self, method: reqwest::Method, path: &str, device_key: Option<&str>) -> reqwest::RequestBuilder {
            let client = reqwest::Client::builder()
                .tls_backend_preconfigured(self.tls_config())
                .resolve(CERTIFICATE_NAME, self.address)
                .no_proxy()
                .build()
                .unwrap();
            let request = client.request(method, format!("https://{CERTIFICATE_NAME}:{}{path}", self.address.port()));
            match device_key {
                Some(key) => request.bearer_auth(key),
                None => request,
            }
        }

        /// 像手机端一样把一批文件放进一个上传请求：文件名按 UTF-8 原样放进引号里，引号前加反斜杠。
        async fn upload(&self, device_key: Option<&str>, files: &[(&str, &str)]) -> reqwest::StatusCode {
            self.upload_body(device_key, multipart_parts(files) + &format!("--{BOUNDARY}--\r\n")).await
        }

        async fn upload_body(&self, device_key: Option<&str>, body: String) -> reqwest::StatusCode {
            let request = self.request(reqwest::Method::POST, "/files", device_key);
            let request = request.header(reqwest::header::CONTENT_TYPE, format!("multipart/form-data; boundary={BOUNDARY}"));
            request.body(body).send().await.unwrap().status()
        }

        /// 下载清单里的一个文件，返回状态和内容。
        async fn download(&self, device_key: &str, offer_id: &str, index: u32) -> (reqwest::StatusCode, String) {
            let response = self.request(reqwest::Method::GET, &format!("/files/{offer_id}/{index}"), Some(device_key)).send().await.unwrap();
            (response.status(), response.text().await.unwrap())
        }

        fn notifications(&self) -> Vec<String> {
            self.notifications.lock().unwrap().clone()
        }

        /// 用户主动同步给手机端 `phone_id`，剪贴板里应该是文字或者发不出去。
        fn sync_clipboard(&self, phone_id: &str) -> SyncResult {
            match self.context.sync_clipboard(phone_id) {
                ClipboardSync::Done(result) => result,
                ClipboardSync::Confirm(pending) => panic!("unexpected confirmation: {}", pending.question()),
            }
        }

        /// 用户主动同步给手机端 `phone_id`，剪贴板里应该是要确认的文件。
        fn pending_files(&self, phone_id: &str) -> PendingFiles {
            match self.context.sync_clipboard(phone_id) {
                ClipboardSync::Confirm(pending) => pending,
                ClipboardSync::Done(result) => panic!("expected a confirmation, got {result:?}"),
            }
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

    const BOUNDARY: &str = "echopad-boundary";

    /// 上传请求里的这些文件，不带结尾的分隔线。
    fn multipart_parts(files: &[(&str, &str)]) -> String {
        let mut body = String::new();
        for (name, contents) in files {
            let name = name.replace('"', "\\\"");
            body += &format!("--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\n\r\n{contents}\r\n");
        }
        body
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

    /// 电脑端启动之后的某个时刻。
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
    async fn newer_clipboard_from_a_phone_reaches_the_desktop_and_the_other_phones() {
        let harness = start_server(None).await;
        let (mut sender, _) = harness.pair("phone-1").await;
        let (mut other, _) = harness.pair("phone-2").await;

        let copied = clipboard("hello", later(1_000));
        send_clipboard(&mut sender, &copied).await;
        assert_eq!(receive(&mut other).await, server_message::Body::ClipboardText(copied));
        assert_eq!(harness.clipboard.written(), [text("hello")]);
        assert_nothing_received(&mut sender).await;
    }

    #[tokio::test]
    async fn clipboard_that_is_not_newer_stops_here() {
        let harness = start_server(None).await;
        let (mut first, _) = harness.pair("phone-1").await;
        let (mut second, _) = harness.pair("phone-2").await;

        let copied = clipboard("hello", later(1_000));
        send_clipboard(&mut first, &copied).await;
        assert!(matches!(receive(&mut second).await, server_message::Body::ClipboardText(_)));
        // 同一条内容经别的电脑转了一圈回来，以及更早的内容，都不再写入、也不再转发。
        send_clipboard(&mut second, &copied).await;
        send_clipboard(&mut second, &clipboard("older", later(500))).await;
        harness.wait_until_handled(&mut second).await;
        assert_eq!(harness.clipboard.written(), [text("hello")]);
        assert_nothing_received(&mut first).await;
    }

    #[tokio::test]
    async fn clipboard_copied_on_two_phones_at_once_settles_on_the_newer_one() {
        let harness = start_server(None).await;
        let (mut first, _) = harness.pair("phone-1").await;
        let (mut second, _) = harness.pair("phone-2").await;

        let newer = clipboard("newer", later(2_000));
        send_clipboard(&mut first, &newer).await;
        send_clipboard(&mut second, &clipboard("older", later(1_000))).await;
        harness.wait_until_handled(&mut first).await;
        harness.wait_until_handled(&mut second).await;
        assert_eq!(harness.clipboard.written().last(), Some(&text("newer")));
        assert_eq!(receive(&mut second).await, server_message::Body::ClipboardText(newer));
    }

    #[tokio::test]
    async fn text_copied_on_the_desktop_reaches_every_online_phone() {
        let harness = start_server(None).await;
        let (mut first, _) = harness.pair("phone-1").await;
        let (mut second, _) = harness.pair("phone-2").await;

        let before = now_millis();
        harness.context.share_clipboard("copied".into());
        for client in [&mut first, &mut second] {
            let server_message::Body::ClipboardText(copied) = receive(client).await else {
                panic!("expected the clipboard");
            };
            assert_eq!(copied.text, "copied");
            assert!(copied.updated_at >= before);
        }

        // 内容没变不算复制；太长的文字不同步。
        harness.context.share_clipboard("copied".into());
        harness.context.share_clipboard("x".repeat(MAX_TEXT_BYTES + 1));
        assert_nothing_received(&mut first).await;
    }

    #[tokio::test]
    async fn syncing_on_purpose_sends_the_desktop_clipboard_even_if_unchanged() {
        let harness = start_server(None).await;
        harness.context.set_clipboard(&ClipboardSettings { auto_send: false, receive: true });
        let mut client = paired_client(&harness).await;
        harness.clipboard.write("on the desktop");

        let mut sent = Vec::new();
        for _ in 0..2 {
            assert_eq!(harness.sync_clipboard("phone-1"), SyncResult::TextSent("Phone phone-1".into()));
            let server_message::Body::ClipboardText(copied) = receive(&mut client).await else {
                panic!("expected the clipboard");
            };
            assert_eq!(copied.text, "on the desktop");
            sent.push(copied.updated_at);
        }
        assert!(sent[1] > sent[0]);
    }

    #[tokio::test]
    async fn syncing_and_sending_on_purpose_only_reach_the_chosen_phone() {
        let harness = start_server(None).await;
        let (mut chosen, _) = harness.pair("phone-1").await;
        let (mut other, _) = harness.pair("phone-2").await;

        harness.clipboard.write("on the desktop");
        assert_eq!(harness.sync_clipboard("phone-1"), SyncResult::TextSent("Phone phone-1".into()));
        assert!(matches!(receive(&mut chosen).await, server_message::Body::ClipboardText(_)));
        harness.clipboard.copy(ClipboardContent::Image(b"png".to_vec()));
        let pending = harness.pending_files("phone-1");
        assert_eq!(pending.question(), "把 1 个文件（3 字节）发送到 Phone phone-1？");
        harness.context.send_files("phone-1", pending.files);
        assert!(matches!(receive(&mut chosen).await, server_message::Body::FileOffer(_)));
        assert_nothing_received(&mut other).await;

        drop(other);
        harness.wait_for_phones(&[("Phone phone-1", true), ("Phone phone-2", false)]).await;
        assert_eq!(harness.sync_clipboard("phone-2"), SyncResult::PhoneOffline);
    }

    #[tokio::test]
    async fn typed_text_reaches_only_the_chosen_phone_and_leaves_the_desktop_clipboard_alone() {
        let harness = start_server(None).await;
        let (mut chosen, _) = harness.pair("phone-1").await;
        let (mut other, _) = harness.pair("phone-2").await;

        assert_eq!(harness.context.send_text("phone-1", "typed".into()), SyncResult::TextSent("Phone phone-1".into()));
        let server_message::Body::ClipboardText(sent) = receive(&mut chosen).await else {
            panic!("expected the clipboard");
        };
        assert_eq!(sent.text, "typed");
        assert_nothing_received(&mut other).await;
        assert!(harness.clipboard.written().is_empty());
        assert_eq!(harness.context.send_text("phone-3", "typed".into()), SyncResult::PhoneOffline);
    }

    #[tokio::test]
    async fn earlier_copies_are_not_sent_to_a_phone_that_connects_later() {
        let harness = start_server(None).await;
        harness.context.share_clipboard("copied before the phone connected".into());
        let mut client = paired_client(&harness).await;
        assert_nothing_received(&mut client).await;
    }

    #[tokio::test]
    async fn a_desktop_that_does_not_receive_still_passes_clipboard_on() {
        let harness = start_server(None).await;
        harness.context.set_clipboard(&ClipboardSettings { auto_send: true, receive: false });
        let (mut sender, _) = harness.pair("phone-1").await;
        let (mut other, _) = harness.pair("phone-2").await;

        let copied = clipboard("hello", later(1_000));
        send_clipboard(&mut sender, &copied).await;
        assert_eq!(receive(&mut other).await, server_message::Body::ClipboardText(copied));
        assert!(harness.clipboard.written().is_empty());
    }

    #[tokio::test]
    async fn files_from_a_paired_phone_are_saved_and_copied() {
        let harness = start_server(None).await;
        let (_client, device_key) = harness.pair("phone-1").await;
        std::fs::write(harness.downloads.join("照片.jpg"), "already there").unwrap();

        let status = harness.upload(Some(&device_key), &[("照片.jpg", "jpeg"), ("报告 \"终版\".pdf", "pdf")]).await;

        assert_eq!(status, reqwest::StatusCode::NO_CONTENT);
        let saved = vec![harness.downloads.join("照片 (1).jpg"), harness.downloads.join("报告 \"终版\".pdf")];
        assert_eq!(std::fs::read_to_string(&saved[0]).unwrap(), "jpeg");
        assert_eq!(std::fs::read_to_string(&saved[1]).unwrap(), "pdf");
        assert_eq!(harness.clipboard.written(), [ClipboardContent::Files(saved)]);
        assert_eq!(harness.notifications(), ["收到 2 个文件，已复制到剪贴板"]);
    }

    #[tokio::test]
    async fn an_interrupted_upload_keeps_the_finished_files_and_says_so() {
        let harness = start_server(None).await;
        let (_client, device_key) = harness.pair("phone-1").await;

        // 第二个文件传到一半连接断了：请求里没有它的结尾和结尾的分隔线。
        let mut body = multipart_parts(&[("a.txt", "done")]);
        body += &format!("--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"b.txt\"\r\n\r\nhal");
        assert!(!harness.upload_body(Some(&device_key), body).await.is_success());

        let saved: Vec<_> = std::fs::read_dir(&harness.downloads).unwrap().map(|entry| entry.unwrap().file_name()).collect();
        assert_eq!(saved, ["a.txt"]);
        assert!(harness.clipboard.written().is_empty());
        assert_eq!(harness.notifications(), ["接收中断，已保存 1 个文件"]);
    }

    #[tokio::test]
    async fn the_phone_reports_how_an_offer_ended() {
        let harness = start_server(None).await;
        let (mut client, device_key) = harness.pair("phone-1").await;
        let mut offer_ids = Vec::new();
        for _ in 0..4 {
            harness.clipboard.copy(ClipboardContent::Image(b"png".to_vec()));
            harness.context.send_files("phone-1", harness.pending_files("phone-1").files);
            let server_message::Body::FileOffer(offer) = receive(&mut client).await else {
                panic!("expected a file offer");
            };
            offer_ids.push(offer.offer_id);
        }
        use file_offer_result::Outcome;
        let outcomes = [(Outcome::Saved, 1), (Outcome::Failed, 0), (Outcome::Unsupported, 0), (Outcome::Cancelled, 0)];
        for (offer_id, (outcome, saved)) in offer_ids.iter().zip(outcomes) {
            let result = FileOfferResult { offer_id: offer_id.clone(), outcome: outcome.into(), saved };
            send(&mut client, client_message::Body::FileOfferResult(result)).await;
        }
        harness.wait_until_handled(&mut client).await;

        // 用户在手机上取消的不提示。
        assert_eq!(
            harness.notifications(),
            ["Phone phone-1 已保存 1 个文件", "Phone phone-1 没能接收文件", "Phone phone-1 的系统版本太低，不能接收文件"]
        );
        // 不论结果如何，清单都随之作废，剩下的文件不能再下载。
        assert_eq!(harness.download(&device_key, &offer_ids[3], 0).await.0, reqwest::StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn uploads_without_a_paired_device_key_are_rejected() {
        let harness = start_server(None).await;
        let (_client, device_key) = harness.pair("phone-1").await;
        harness.context.unpair("phone-1").unwrap();

        for key in [None, Some("not a device key"), Some(device_key.as_str())] {
            assert_eq!(harness.upload(key, &[("a.txt", "a")]).await, reqwest::StatusCode::UNAUTHORIZED);
        }
        assert_eq!(std::fs::read_dir(&harness.downloads).unwrap().count(), 0);
        assert!(harness.clipboard.written().is_empty());
    }

    #[tokio::test]
    async fn files_copied_on_the_desktop_are_offered_and_downloaded_once() {
        let harness = start_server(None).await;
        let (mut client, device_key) = harness.pair("phone-1").await;
        let copied = harness.downloads.join("a.txt");
        std::fs::write(&copied, "hello").unwrap();
        harness.clipboard.copy(ClipboardContent::Files(vec![harness.downloads.clone(), copied]));

        let pending = harness.pending_files("phone-1");
        assert_eq!(pending.question(), "把 1 个文件（5 字节）发送到 Phone phone-1？");
        assert_eq!(harness.context.send_files("phone-1", pending.files), SyncResult::FilesSending("Phone phone-1".into()));

        let server_message::Body::FileOffer(offer) = receive(&mut client).await else {
            panic!("expected a file offer");
        };
        assert_eq!(offer.files, [protocol::OfferedFile { name: "a.txt".into(), size: 5 }]);
        assert_eq!(harness.download(&device_key, &offer.offer_id, 0).await, (reqwest::StatusCode::OK, "hello".into()));
        assert_eq!(harness.download(&device_key, &offer.offer_id, 0).await.0, reqwest::StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn an_offer_is_only_for_its_phone_until_it_disconnects() {
        let harness = start_server(None).await;
        let (mut client, device_key) = harness.pair("phone-1").await;
        harness.clipboard.copy(ClipboardContent::Image(b"png".to_vec()));
        let pending = harness.pending_files("phone-1");
        assert_eq!(pending.files[0].name, "图片.png");
        harness.context.send_files("phone-1", pending.files);
        let server_message::Body::FileOffer(offer) = receive(&mut client).await else {
            panic!("expected a file offer");
        };

        // 之后才连上的手机端拿不到这份清单里的文件。
        let (_other, other_key) = harness.pair("phone-2").await;
        assert_eq!(harness.download(&other_key, &offer.offer_id, 0).await.0, reqwest::StatusCode::NOT_FOUND);

        drop(client);
        harness.wait_for_phones(&[("Phone phone-1", false), ("Phone phone-2", true)]).await;
        assert_eq!(harness.download(&device_key, &offer.offer_id, 0).await.0, reqwest::StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn files_are_not_sent_without_an_online_phone_or_as_folders() {
        let harness = start_server(None).await;
        let copied = harness.downloads.join("a.txt");
        std::fs::write(&copied, "hello").unwrap();
        harness.clipboard.copy(ClipboardContent::Files(vec![copied]));
        assert_eq!(harness.sync_clipboard("phone-1"), SyncResult::PhoneOffline);

        let _client = paired_client(&harness).await;
        harness.clipboard.copy(ClipboardContent::Files(vec![harness.downloads.clone()]));
        assert_eq!(harness.sync_clipboard("phone-1"), SyncResult::OnlyFolders);
        harness.clipboard.copy(ClipboardContent::Files(vec![harness.downloads.join("ejected.txt")]));
        assert_eq!(harness.sync_clipboard("phone-1"), SyncResult::FilesUnreadable);
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
