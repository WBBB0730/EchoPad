mod certificate;
mod clipboard;
mod dictation;
mod discovery;
mod files;
mod input;
mod pairing;
mod protocol;
mod screen_context;
mod server;
mod settings;
mod status;
mod update;

use std::{
    net::{Ipv4Addr, SocketAddr},
    path::PathBuf,
    sync::{Arc, Mutex},
};

use anyhow::Context;
use serde::{Deserialize, Serialize};
use tauri::{
    AppHandle, Emitter, Manager, RunEvent, WebviewWindow, WindowEvent,
    image::Image,
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
};
use tauri_plugin_dialog::{DialogExt, MessageDialogBuilder, MessageDialogButtons};
use tauri_plugin_notification::NotificationExt;
use tauri_plugin_opener::OpenerExt;
use tokio::sync::oneshot;
use tracing_subscriber::EnvFilter;

use tokio_rustls::rustls::ServerConfig;

use crate::{
    certificate::DesktopCertificate,
    clipboard::SystemClipboard,
    dictation::{CloudDictation, DictationService},
    input::InputController,
    pairing::PairingStore,
    server::{DEV_PORT, PORT, SendPlan, ServerContext, System},
    settings::{CleanupModel, CleanupPreset, Credential, DoubaoResource, RecognitionService, Settings, SettingsStore},
    status::{PhoneStatus, Status, StatusHub},
    update::{UpdateStatus, Updater},
};

/// 菜单栏图标的标识，手机端在线状态变化时据此更新右键菜单。
const TRAY_ID: &str = "main";
/// 菜单栏图标用的单色模板图，由系统按菜单栏的深浅色着色。
const TRAY_ICON: Image<'_> = tauri::include_image!("icons/tray-template.png");
/// 右键菜单里针对某部手机的菜单项，标识是这些前缀后面接手机端的固定标识：让它控制这台电脑、发送剪贴板、发送文件、发送文本。
const SWITCH_MENU_PREFIX: &str = "switch:";
const SYNC_CLIPBOARD_MENU_PREFIX: &str = "sync_clipboard:";
const SEND_FILES_MENU_PREFIX: &str = "send_files:";
const SEND_TEXT_MENU_PREFIX: &str = "send_text:";

struct AppState {
    server: Arc<ServerContext>,
    settings: SettingsStore,
}

impl AppState {
    /// 按当前设置重建语音输入服务，并更新剪贴板同步的开关。
    fn apply_settings(&self) {
        let settings = self.settings.get();
        let service = settings
            .dictation_config()
            .map(|config| Arc::new(CloudDictation::new(config)) as Arc<dyn DictationService>);
        self.server.set_dictation(service);
        self.server.set_clipboard(&settings.clipboard);
    }

    fn save_settings(&self, settings: Settings) -> Result<SettingsView, String> {
        self.settings.save(settings).map_err(|err| format!("{err:#}"))?;
        self.apply_settings();
        Ok(self.settings_view())
    }

    fn settings_view(&self) -> SettingsView {
        SettingsView {
            settings: self.settings.get(),
            recognition_services: RecognitionService::ALL
                .map(|service| ChoiceView { id: service, name: service.name(), credential: Some(service.credential()) })
                .to_vec(),
            doubao_resources: DoubaoResource::ALL
                .map(|resource| ChoiceView { id: resource, name: resource.name(), credential: None })
                .to_vec(),
            cleanup_presets: CleanupPreset::ALL
                .map(|preset| PresetView {
                    id: preset,
                    name: preset.name(),
                    base_url: preset.base_url(),
                    models: preset.models(),
                    credential: preset.credential(),
                })
                .to_vec(),
        }
    }
}

/// 设置界面需要的全部信息。
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SettingsView {
    settings: Settings,
    recognition_services: Vec<ChoiceView<RecognitionService>>,
    doubao_resources: Vec<ChoiceView<DoubaoResource>>,
    cleanup_presets: Vec<PresetView>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ChoiceView<T> {
    id: T,
    name: &'static str,
    credential: Option<Credential>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PresetView {
    id: CleanupPreset,
    name: &'static str,
    base_url: Option<&'static str>,
    /// 可选的模型，第一个是默认；为空时由用户填写。
    models: &'static [CleanupModel],
    credential: Credential,
}

#[tauri::command]
fn get_status(state: tauri::State<'_, AppState>) -> Status {
    state.server.status.snapshot()
}

#[tauri::command]
fn get_settings(state: tauri::State<'_, AppState>) -> SettingsView {
    state.settings_view()
}

#[tauri::command]
fn save_settings(state: tauri::State<'_, AppState>, settings: Settings) -> Result<SettingsView, String> {
    state.save_settings(settings)
}

/// 主窗口里一部手机的"发送剪贴板"，返回给用户的提示；用户取消发送文件时为 `None`。
#[tauri::command]
async fn sync_clipboard(app: AppHandle, window: WebviewWindow, phone_id: String) -> Option<String> {
    confirm_and_sync_clipboard(app, phone_id, Some(window)).await
}

/// 菜单栏菜单和主窗口里一部手机的"发送剪贴板"：剪贴板里是文件或图片时，先用系统对话框问用户
/// （DESIGN.md、docs/prd/send-files.md）。返回给用户的提示，用户取消时为 `None`。
async fn confirm_and_sync_clipboard(app: AppHandle, phone_id: String, window: Option<WebviewWindow>) -> Option<String> {
    let plan = app.state::<AppState>().server.sync_clipboard(&phone_id);
    confirm_and_send_files(app, phone_id, plan, window).await
}

/// 主窗口里拖进来的文件，发给指针下的那部手机（docs/prd/send-files.md）。返回给用户的提示，用户取消时为 `None`。
#[tauri::command]
async fn send_dropped_files(app: AppHandle, window: WebviewWindow, phone_id: String, paths: Vec<PathBuf>) -> Option<String> {
    let plan = app.state::<AppState>().server.prepare_dropped_files(&phone_id, paths);
    confirm_and_send_files(app, phone_id, plan, Some(window)).await
}

/// 要确认的发送（剪贴板里的文件或图片、拖进主窗口的文件）：先用系统对话框问用户，确认后再发
/// （DESIGN.md、docs/prd/send-files.md）。从主窗口发起时，对话框挂在主窗口上。返回给用户的提示，用户取消时为 `None`。
async fn confirm_and_send_files(app: AppHandle, phone_id: String, plan: SendPlan, window: Option<WebviewWindow>) -> Option<String> {
    let pending = match plan {
        SendPlan::Done(result) => return Some(result.message()),
        SendPlan::Confirm(pending) => pending,
    };
    let (answer, answered) = oneshot::channel();
    let mut dialog = MessageDialogBuilder::new(app.dialog().clone(), pending.question(), "")
        .buttons(MessageDialogButtons::OkCancelCustom("发送".into(), "取消".into()));
    if let Some(window) = window {
        // 从访达拖文件进来时，前台还是访达；先切到前台，对话框才接得到键盘。
        let _ = window.set_focus();
        dialog = dialog.parent(&window);
    }
    dialog.show(move |confirmed| {
        let _ = answer.send(confirmed);
    });
    answered
        .await
        .unwrap_or(false)
        .then(|| app.state::<AppState>().server.send_files(&phone_id, pending.files).message())
}

/// 主窗口里一部手机的"发送文件"，返回给用户的提示；用户没选文件时为 `None`。
#[tauri::command]
async fn send_files(app: AppHandle, phone_id: String) -> Option<String> {
    pick_and_send_files(app, phone_id).await
}

/// 主窗口和菜单栏菜单里一部手机的"发送文件"：用系统的选文件面板选文件，选完直接发给这部手机，不再确认
/// （docs/prd/send-files.md）。返回给用户的提示，用户没选文件时为 `None`。
async fn pick_and_send_files(app: AppHandle, phone_id: String) -> Option<String> {
    let (answer, answered) = oneshot::channel();
    app.dialog().file().pick_files(move |picked| {
        let _ = answer.send(picked);
    });
    let paths: Vec<PathBuf> = answered.await.ok().flatten()?.into_iter().filter_map(|path| path.into_path().ok()).collect();
    Some(app.state::<AppState>().server.send_picked_files(&phone_id, paths).message())
}

/// 主窗口里一部手机的"发送文本"：把用户打的字发给这部手机，返回给用户的提示。
#[tauri::command]
fn send_text(state: tauri::State<'_, AppState>, phone_id: String, text: String) -> String {
    state.server.send_text(&phone_id, text).message()
}

/// 菜单栏菜单里的操作：在后台做完后用系统通知提示结果，点菜单时主窗口通常没开（DESIGN.md）。
fn notify_when_done(app: &AppHandle, action: impl Future<Output = Option<String>> + Send + 'static) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        if let Some(message) = action.await {
            notify(&app, &message);
        }
    });
}

/// 系统通知：菜单栏菜单里操作的结果、收到文件（DESIGN.md）。
fn notify(app: &AppHandle, message: &str) {
    if let Err(err) = app.notification().builder().body(message).show() {
        tracing::warn!("failed to show a notification: {err}");
    }
}

/// 取消一部手机端的配对，它之后要重新扫码配对。
#[tauri::command]
fn unpair_phone(state: tauri::State<'_, AppState>, id: String) -> Result<(), String> {
    state.server.unpair(&id).map_err(|err| format!("{err:#}"))
}

/// macOS「系统设置 › 隐私与安全性」里要用户授权的页面。
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
enum PrivacyPane {
    /// 辅助功能：模拟键鼠需要。
    Accessibility,
    /// 粘贴：系统一律拒绝读剪贴板时，剪贴板同步需要（ADR-0017）。
    Pasteboard,
}

impl PrivacyPane {
    fn url(self) -> &'static str {
        match self {
            Self::Accessibility => "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility",
            Self::Pasteboard => "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?Privacy_Pasteboard",
        }
    }
}

#[tauri::command]
fn open_privacy_settings(app: AppHandle, pane: PrivacyPane) -> Result<(), String> {
    app.opener().open_url(pane.url(), None::<&str>).map_err(|err| err.to_string())
}

#[tauri::command]
fn get_update_status(updater: tauri::State<'_, Arc<Updater>>) -> UpdateStatus {
    updater.status()
}

/// 打开设置时 `force` 为 false，距上次检查不足 5 分钟就跳过；手动检查为 true。
#[tauri::command]
async fn check_for_updates(updater: tauri::State<'_, Arc<Updater>>, force: bool) -> Result<UpdateStatus, String> {
    updater.check(force).await;
    Ok(updater.status())
}

/// 更新弹窗里的确认：安装并重启；开发版打开 Release 页面。
#[tauri::command]
fn apply_update(updater: tauri::State<'_, Arc<Updater>>) -> Result<(), String> {
    updater.apply()
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("desktop_lib=info")))
        .init();

    // 电脑端常驻在后台，macOS 的 App Nap 会把它降到最低优先级，电脑一忙，手机端的操作就要排队（docs/prd/touchpad.md）。
    // 声明在处理用户的操作，系统就不让它进入 App Nap，也不妨碍电脑休眠；运行期间一直持有，退出时随进程结束。
    #[cfg(target_os = "macos")]
    let _activity = objc2_foundation::NSProcessInfo::processInfo().beginActivityWithOptions_reason(
        objc2_foundation::NSActivityOptions::UserInitiatedAllowingIdleSystemSleep,
        objc2_foundation::ns_string!("Applying input from paired phones"),
    );

    tauri::Builder::default()
        .menu(app_menu)
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let dev = is_dev_app(app.handle());
            let port = if dev { DEV_PORT } else { PORT };
            // 窗口标题跟着应用名，测试包是 EchoPad Dev（窗口菜单和调度中心里看得到）。
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.set_title(&app.package_info().name);
            }
            build_tray(app)?;

            let updater = Arc::new(Updater::new(app.handle().clone(), !tauri::is_dev() && !dev));
            updater.clone().start();
            app.manage(updater);

            let (state, tls) = build_state(app.handle(), port, dev)?;
            state.apply_settings();
            let server = state.server.clone();
            app.manage(state);

            // 广播失败（例如网络不允许组播）时，只是手机端无法自动找到换了地址的电脑，其他功能照常。
            let desktop_id = server.pairing.lock().unwrap().desktop_id().to_owned();
            match discovery::advertise(&server.desktop_name, &desktop_id, port) {
                Ok(daemon) => {
                    let server = server.clone();
                    tauri::async_runtime::spawn(async move {
                        // 暂时没有局域网时（例如正在换 Wi-Fi），配对码保持原来的地址。
                        let refresh = || match discovery::lan_address() {
                            Some(host) => server.update_host(host),
                            None => tracing::debug!("no LAN address"),
                        };
                        if let Err(err) = discovery::watch_addresses(&daemon, refresh).await {
                            tracing::warn!("stopped watching network changes: {err:#}");
                        }
                    });
                }
                Err(err) => tracing::warn!("local network discovery unavailable: {err:#}"),
            }

            tauri::async_runtime::spawn(async move {
                let address = SocketAddr::from((Ipv4Addr::UNSPECIFIED, port));
                match tokio::net::TcpListener::bind(address).await {
                    Ok(listener) => server::serve(listener, server, tls).await,
                    Err(err) => tracing::error!("failed to start the connection server: {err}"),
                }
            });
            Ok(())
        })
        // 关掉窗口只是隐藏，电脑端继续运行，手机端不断线。
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                hide_main_window(window);
            }
        })
        .invoke_handler(tauri::generate_handler![
            get_status,
            get_settings,
            save_settings,
            unpair_phone,
            sync_clipboard,
            send_files,
            send_dropped_files,
            send_text,
            open_privacy_settings,
            get_update_status,
            check_for_updates,
            apply_update
        ])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, event| match event {
            // 冷启动时把主窗口带到前台。在后台运行时再次打开 EchoPad（例如在"应用程序"里双击），也显示窗口。
            RunEvent::Ready | RunEvent::Reopen { .. } => show_main_window(app),
            _ => {}
        });
}

/// macOS 的应用菜单：各项照 Tauri 的默认菜单摆，只把文字写死成中文，用词和 DevCube 一致（ADR-0020）。
/// 窗口和帮助两块用 Tauri 约定的标识，Tauri 据此把它们登记成系统的窗口菜单和帮助菜单，macOS 才往里加窗口列表、
/// 平铺和帮助搜索；macOS 自己加的这些项靠 Info.plist 把默认语言设成简体中文。
#[cfg(target_os = "macos")]
fn app_menu(app: &AppHandle) -> tauri::Result<Menu<tauri::Wry>> {
    use tauri::menu::{AboutMetadata, HELP_SUBMENU_ID, Submenu, WINDOW_SUBMENU_ID};

    let package = app.package_info();
    let name = &package.name;
    let about = AboutMetadata {
        name: Some(name.clone()),
        version: Some(package.version.to_string()),
        copyright: app.config().bundle.copyright.clone(),
        authors: app.config().bundle.publisher.clone().map(|publisher| vec![publisher]),
        ..Default::default()
    };
    Menu::with_items(
        app,
        &[
            &Submenu::with_items(
                app,
                name,
                true,
                &[
                    &PredefinedMenuItem::about(app, Some(&format!("关于 {name}")), Some(about))?,
                    &PredefinedMenuItem::separator(app)?,
                    &PredefinedMenuItem::services(app, Some("服务"))?,
                    &PredefinedMenuItem::separator(app)?,
                    &PredefinedMenuItem::hide(app, Some(&format!("隐藏 {name}")))?,
                    &PredefinedMenuItem::hide_others(app, Some("隐藏其他"))?,
                    &PredefinedMenuItem::separator(app)?,
                    &PredefinedMenuItem::quit(app, Some(&format!("退出 {name}")))?,
                ],
            )?,
            &Submenu::with_items(app, "文件", true, &[&PredefinedMenuItem::close_window(app, Some("关闭窗口"))?])?,
            &Submenu::with_items(
                app,
                "编辑",
                true,
                &[
                    &PredefinedMenuItem::undo(app, Some("撤销"))?,
                    &PredefinedMenuItem::redo(app, Some("重做"))?,
                    &PredefinedMenuItem::separator(app)?,
                    &PredefinedMenuItem::cut(app, Some("剪切"))?,
                    &PredefinedMenuItem::copy(app, Some("复制"))?,
                    &PredefinedMenuItem::paste(app, Some("粘贴"))?,
                    &PredefinedMenuItem::select_all(app, Some("全选"))?,
                ],
            )?,
            &Submenu::with_items(app, "视图", true, &[&PredefinedMenuItem::fullscreen(app, Some("切换全屏"))?])?,
            &Submenu::with_id_and_items(
                app,
                WINDOW_SUBMENU_ID,
                "窗口",
                true,
                &[
                    &PredefinedMenuItem::minimize(app, Some("最小化"))?,
                    &PredefinedMenuItem::maximize(app, Some("缩放"))?,
                    &PredefinedMenuItem::separator(app)?,
                    &PredefinedMenuItem::close_window(app, Some("关闭窗口"))?,
                ],
            )?,
            &Submenu::with_id_and_items(app, HELP_SUBMENU_ID, "帮助", true, &[])?,
        ],
    )
}

/// Windows 版再做。
#[cfg(not(target_os = "macos"))]
fn app_menu(app: &AppHandle) -> tauri::Result<Menu<tauri::Wry>> {
    Menu::default(app)
}

/// 菜单栏图标：左键点击打开窗口；右键点击是菜单，可以重新打开窗口、让在线的手机端切换过来、退出电脑端。
fn build_tray(app: &tauri::App) -> tauri::Result<()> {
    TrayIconBuilder::with_id(TRAY_ID)
        .icon(TRAY_ICON)
        .icon_as_template(true)
        .tooltip(&app.package_info().name)
        .menu(&tray_menu(app.handle(), &[])?)
        .show_menu_on_left_click(false)
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                show_main_window(tray.app_handle());
            }
        })
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main_window(app),
            "quit" => {
                app.state::<Arc<Updater>>().install_on_quit();
                app.exit(0);
            }
            id => {
                if let Some(phone_id) = id.strip_prefix(SWITCH_MENU_PREFIX) {
                    app.state::<AppState>().server.request_switch(phone_id);
                } else if let Some(phone_id) = id.strip_prefix(SYNC_CLIPBOARD_MENU_PREFIX) {
                    notify_when_done(app, confirm_and_sync_clipboard(app.clone(), phone_id.into(), None));
                } else if let Some(phone_id) = id.strip_prefix(SEND_FILES_MENU_PREFIX) {
                    notify_when_done(app, pick_and_send_files(app.clone(), phone_id.into()));
                } else if let Some(phone_id) = id.strip_prefix(SEND_TEXT_MENU_PREFIX) {
                    // 打字要有输入框：打开主窗口，让它弹出发给这部手机的输入框。
                    show_main_window(app);
                    let _ = app.emit("send-text", phone_id);
                }
            }
        })
        .build(app)?;
    Ok(())
}

/// 右键菜单：每部在线的手机端一组，"让它控制这台电脑""发送剪贴板到它""发送文件到它…""发送文本到它…"，组和组之间用分隔线隔开
/// （DESIGN.md）。
fn tray_menu(app: &AppHandle, phones: &[PhoneStatus]) -> tauri::Result<Menu<tauri::Wry>> {
    let menu = Menu::new(app)?;
    menu.append(&MenuItem::with_id(app, "open", format!("打开 {}", app.package_info().name), true, None::<&str>)?)?;
    for phone in phones.iter().filter(|phone| phone.online) {
        menu.append(&PredefinedMenuItem::separator(app)?)?;
        for (prefix, label) in [
            (SWITCH_MENU_PREFIX, format!("让 {} 控制这台电脑", phone.name)),
            (SYNC_CLIPBOARD_MENU_PREFIX, format!("发送剪贴板到 {}", phone.name)),
            (SEND_FILES_MENU_PREFIX, format!("发送文件到 {}…", phone.name)),
            (SEND_TEXT_MENU_PREFIX, format!("发送文本到 {}…", phone.name)),
        ] {
            menu.append(&MenuItem::with_id(app, format!("{prefix}{}", phone.id), label, true, None::<&str>)?)?;
        }
    }
    menu.append(&PredefinedMenuItem::separator(app)?)?;
    menu.append(&MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?)?;
    Ok(menu)
}

/// 在线的手机端变了时重建右键菜单。
fn update_tray_menu(app: &AppHandle, phones: &[PhoneStatus]) {
    let Some(tray) = app.tray_by_id(TRAY_ID) else {
        return;
    };
    match tray_menu(app, phones) {
        Ok(menu) => {
            if let Err(err) = tray.set_menu(Some(menu)) {
                tracing::warn!("failed to update the tray menu: {err}");
            }
        }
        Err(err) => tracing::warn!("failed to build the tray menu: {err}"),
    }
}

/// 主窗口打开时程序坞里有图标；关掉窗口后只留在菜单栏。
fn show_main_window(app: &AppHandle) {
    #[cfg(target_os = "macos")]
    let _ = app.set_activation_policy(tauri::ActivationPolicy::Regular);
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn hide_main_window(window: &tauri::Window) {
    let _ = window.hide();
    #[cfg(target_os = "macos")]
    let _ = window.app_handle().set_activation_policy(tauri::ActivationPolicy::Accessory);
}

/// 测试包（EchoPad Dev，用 `tauri.dev.conf.json` 打包或运行）：独立的包名让它有自己的配置目录，也就有自己的
/// 电脑端标识、证书和配对；再配上独立的端口和设备名，就能和正式版同时运行。
fn is_dev_app(app: &AppHandle) -> bool {
    app.config().identifier.ends_with(".dev")
}

/// 手机端显示的电脑名称：macOS 上用「系统设置 › 通用 › 关于本机」里的名称；测试包加 `(Dev)` 后缀，
/// 在手机端和正式版区分开，局域网广播也不重名。
fn desktop_name(dev: bool) -> String {
    let name = host_desktop_name();
    if dev { format!("{name} (Dev)") } else { name }
}

fn host_desktop_name() -> String {
    #[cfg(target_os = "macos")]
    if let Some(name) =
        unsafe { objc2_system_configuration::SCDynamicStore::computer_name(None, std::ptr::null_mut()) }
    {
        return name.to_string();
    }
    gethostname::gethostname().to_string_lossy().into_owned()
}

fn build_state(app: &tauri::AppHandle, port: u16, dev: bool) -> anyhow::Result<(AppState, Arc<ServerConfig>)> {
    let config_dir = app.path().app_config_dir()?;
    let certificate = DesktopCertificate::load_or_create(&config_dir)?;
    let pairing = PairingStore::load(config_dir.join("paired-devices.json"))?;
    let settings = SettingsStore::load(config_dir.join("settings.json"))?;
    let host = discovery::lan_address().context("failed to find the LAN address")?;

    let handle = app.clone();
    // 右键菜单只在在线的手机端变了时重建。
    let online_phones = Mutex::new(Vec::new());
    let status = Arc::new(StatusHub::new(move |status| {
        let _ = handle.emit("status-changed", status);
        let online: Vec<_> = status.phones.iter().filter(|phone| phone.online).cloned().collect();
        let mut previous = online_phones.lock().unwrap();
        if *previous != online {
            update_tray_menu(&handle, &online);
            *previous = online;
        }
    }));
    let permission_status = status.clone();
    let main_thread = app.clone();
    let input = InputController::spawn(
        move |granted| permission_status.set_input_permission(granted),
        move |task| {
            if let Err(err) = main_thread.run_on_main_thread(task) {
                tracing::warn!("failed to run on the main thread: {err}");
            }
        },
    );

    let clipboard = Arc::new(SystemClipboard::new());
    let notifier = app.clone();
    let system = System {
        input: Arc::new(input),
        clipboard: clipboard.clone(),
        downloads: app.path().download_dir()?,
        notify: Box::new(move |message| notify(&notifier, &message)),
    };
    let server = Arc::new(ServerContext::new(desktop_name(dev), host, port, certificate.fingerprint(), pairing, status.clone(), system));
    let (enabled, copied) = (server.clone(), server.clone());
    clipboard.watch(
        move || enabled.clipboard_auto_send(),
        move |text| copied.share_clipboard(text),
        move |denied| status.set_clipboard_access_denied(denied),
    );
    let state = AppState { server, settings };
    Ok((state, certificate.server_config()?))
}

#[cfg(test)]
mod tests {
    use std::{collections::BTreeMap, net::IpAddr};

    use super::*;
    use crate::{
        clipboard::{Clipboard, ClipboardContent},
        input::{InputCommand, InputSink},
    };

    struct IgnoredInput;

    impl InputSink for IgnoredInput {
        fn send(&self, _command: InputCommand) {}
    }

    struct IgnoredClipboard;

    impl Clipboard for IgnoredClipboard {
        fn read(&self) -> Option<ClipboardContent> {
            None
        }

        fn write(&self, _text: &str) {}

        fn write_files(&self, _files: &[std::path::PathBuf]) {}
    }

    fn state() -> AppState {
        let dir = std::env::temp_dir().join(format!("echopad-app-{}", uuid::Uuid::new_v4()));
        let host = IpAddr::from(Ipv4Addr::LOCALHOST);
        let pairing = PairingStore::load(dir.join("paired-devices.json")).unwrap();
        let status = Arc::new(StatusHub::new(|_| {}));
        AppState {
            server: Arc::new(ServerContext::new(
                "Mac".into(),
                host,
                PORT,
                "fingerprint".into(),
                pairing,
                status,
                System {
                    input: Arc::new(IgnoredInput),
                    clipboard: Arc::new(IgnoredClipboard),
                    downloads: dir.clone(),
                    notify: Box::new(|_| {}),
                },
            )),
            settings: SettingsStore::load(dir.join("settings.json")).unwrap(),
        }
    }

    #[test]
    fn saving_and_clearing_keys_toggles_dictation() {
        let state = state();
        state.apply_settings();
        assert!(!state.server.status.snapshot().dictation_ready);

        let mut settings = Settings::default();
        settings.cleanup.enabled = false;
        settings.keys = BTreeMap::from([(Credential::VolcengineSpeech, " speech-key ".to_owned())]);
        let view = state.save_settings(settings.clone()).unwrap();
        assert!(state.server.status.snapshot().dictation_ready);
        // 保存的密钥会显示在设置界面上（去掉两端空白）。
        assert_eq!(view.settings.keys[&Credential::VolcengineSpeech], "speech-key");

        settings.keys.clear();
        state.save_settings(settings).unwrap();
        assert!(!state.server.status.snapshot().dictation_ready);
    }

    #[test]
    fn saving_settings_turns_clipboard_auto_send_on_and_off() {
        let state = state();
        state.apply_settings();
        assert!(state.server.clipboard_auto_send());

        let mut settings = Settings::default();
        settings.clipboard.auto_send = false;
        state.save_settings(settings).unwrap();
        assert!(!state.server.clipboard_auto_send());
    }
}
