mod certificate;
mod dictation;
mod discovery;
mod input;
mod pairing;
mod protocol;
mod screen_context;
mod server;
mod settings;
mod status;

use std::{
    net::{Ipv4Addr, SocketAddr},
    sync::{Arc, Mutex},
};

use anyhow::Context;
use serde::Serialize;
use tauri::{
    AppHandle, Emitter, Manager, RunEvent, WindowEvent,
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
};
use tauri_plugin_opener::OpenerExt;
use tracing_subscriber::EnvFilter;

use tokio_rustls::rustls::ServerConfig;

use crate::{
    certificate::DesktopCertificate,
    dictation::{CloudDictation, DictationService},
    input::InputController,
    pairing::PairingStore,
    server::{PORT, ServerContext},
    settings::{CleanupModel, CleanupPreset, Credential, DoubaoResource, RecognitionService, Settings, SettingsStore},
    status::{PhoneStatus, Status, StatusHub},
};

/// 菜单栏图标的标识，手机端在线状态变化时据此更新右键菜单。
const TRAY_ID: &str = "main";
/// 右键菜单里"让某部手机控制这台电脑"的菜单项标识前缀，后面接手机端的固定标识。
const SWITCH_MENU_PREFIX: &str = "switch:";

struct AppState {
    server: Arc<ServerContext>,
    settings: SettingsStore,
}

impl AppState {
    /// 按当前设置重建语音输入服务。
    fn apply_settings(&self) {
        let service = self
            .settings
            .get()
            .dictation_config()
            .map(|config| Arc::new(CloudDictation::new(config)) as Arc<dyn DictationService>);
        self.server.set_dictation(service);
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

/// 取消一部手机端的配对，它之后要重新扫码配对。
#[tauri::command]
fn unpair_phone(state: tauri::State<'_, AppState>, id: String) -> Result<(), String> {
    state.server.unpair(&id).map_err(|err| format!("{err:#}"))
}

/// macOS「系统设置 › 隐私与安全性 › 辅助功能」页面。
const ACCESSIBILITY_SETTINGS_URL: &str = "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility";

#[tauri::command]
fn open_accessibility_settings(app: AppHandle) -> Result<(), String> {
    app.opener().open_url(ACCESSIBILITY_SETTINGS_URL, None::<&str>).map_err(|err| err.to_string())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("desktop_lib=info")))
        .init();

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            build_tray(app)?;

            let (state, tls) = build_state(app.handle())?;
            state.apply_settings();
            let server = state.server.clone();
            app.manage(state);

            // 广播失败（例如网络不允许组播）时，只是手机端无法自动找到换了地址的电脑，其他功能照常。
            let desktop_id = server.pairing.lock().unwrap().desktop_id().to_owned();
            match discovery::advertise(&server.desktop_name, &desktop_id, PORT) {
                Ok(daemon) => {
                    let server = server.clone();
                    tauri::async_runtime::spawn(async move {
                        let refresh = || match local_ip_address::local_ip() {
                            Ok(host) => server.update_host(host),
                            Err(err) => tracing::debug!("no LAN address: {err}"),
                        };
                        if let Err(err) = discovery::watch_addresses(&daemon, refresh).await {
                            tracing::warn!("stopped watching network changes: {err:#}");
                        }
                    });
                }
                Err(err) => tracing::warn!("local network discovery unavailable: {err:#}"),
            }

            tauri::async_runtime::spawn(async move {
                let address = SocketAddr::from((Ipv4Addr::UNSPECIFIED, PORT));
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
            open_accessibility_settings
        ])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, event| match event {
            // 冷启动时把主窗口带到前台。在后台运行时再次打开 EchoPad（例如在"应用程序"里双击），也显示窗口。
            RunEvent::Ready | RunEvent::Reopen { .. } => show_main_window(app),
            _ => {}
        });
}

/// 菜单栏图标：左键点击打开窗口；右键点击是菜单，可以重新打开窗口、让在线的手机端切换过来、退出电脑端。
fn build_tray(app: &tauri::App) -> tauri::Result<()> {
    let icon = app.default_window_icon().cloned().expect("the app icon is bundled");
    TrayIconBuilder::with_id(TRAY_ID)
        .icon(icon)
        .tooltip("EchoPad")
        .menu(&tray_menu(app.handle(), &[])?)
        .show_menu_on_left_click(false)
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                show_main_window(tray.app_handle());
            }
        })
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main_window(app),
            "quit" => app.exit(0),
            id => {
                if let Some(phone_id) = id.strip_prefix(SWITCH_MENU_PREFIX) {
                    app.state::<AppState>().server.request_switch(phone_id);
                }
            }
        })
        .build(app)?;
    Ok(())
}

/// 右键菜单：每部在线的手机端一项"让它控制这台电脑"。
fn tray_menu(app: &AppHandle, phones: &[PhoneStatus]) -> tauri::Result<Menu<tauri::Wry>> {
    let menu = Menu::new(app)?;
    menu.append(&MenuItem::with_id(app, "open", "打开 EchoPad", true, None::<&str>)?)?;
    for phone in phones.iter().filter(|phone| phone.online) {
        let id = format!("{SWITCH_MENU_PREFIX}{}", phone.id);
        menu.append(&MenuItem::with_id(app, id, format!("让 {} 控制这台电脑", phone.name), true, None::<&str>)?)?;
    }
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

/// 手机端显示的电脑名称：macOS 上用「系统设置 › 通用 › 关于本机」里的名称。
fn desktop_name() -> String {
    #[cfg(target_os = "macos")]
    if let Some(name) =
        unsafe { objc2_system_configuration::SCDynamicStore::computer_name(None, std::ptr::null_mut()) }
    {
        return name.to_string();
    }
    gethostname::gethostname().to_string_lossy().into_owned()
}

fn build_state(app: &tauri::AppHandle) -> anyhow::Result<(AppState, Arc<ServerConfig>)> {
    let config_dir = app.path().app_config_dir()?;
    let certificate = DesktopCertificate::load_or_create(&config_dir)?;
    let pairing = PairingStore::load(config_dir.join("paired-devices.json"))?;
    let settings = SettingsStore::load(config_dir.join("settings.json"))?;
    let host = local_ip_address::local_ip().context("failed to find the LAN address")?;

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
    let input = InputController::spawn(move |granted| permission_status.set_input_permission(granted));

    let server = ServerContext::new(desktop_name(), host, certificate.fingerprint(), pairing, status, Arc::new(input));
    let state = AppState {
        server: Arc::new(server),
        settings,
    };
    Ok((state, certificate.server_config()?))
}

#[cfg(test)]
mod tests {
    use std::{collections::BTreeMap, net::IpAddr};

    use super::*;
    use crate::input::{InputCommand, InputSink};

    struct IgnoredInput;

    impl InputSink for IgnoredInput {
        fn send(&self, _command: InputCommand) {}
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
                "fingerprint".into(),
                pairing,
                status,
                Arc::new(IgnoredInput),
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
}
