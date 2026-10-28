mod dictation;
mod input;
mod pairing;
mod protocol;
mod screen_context;
mod server;
mod settings;
mod status;

use std::{
    net::{Ipv4Addr, SocketAddr},
    sync::Arc,
};

use anyhow::Context;
use serde::Serialize;
use tauri::{
    AppHandle, Emitter, Manager, RunEvent, WindowEvent,
    menu::{Menu, MenuItem},
    tray::TrayIconBuilder,
};
use tracing_subscriber::EnvFilter;

use crate::{
    dictation::{CloudDictation, DictationService},
    input::InputController,
    pairing::{PairingStore, pairing_link},
    server::{PORT, ServerContext},
    settings::{CleanupPreset, Credential, DoubaoResource, RecognitionService, Settings, SettingsStore},
    status::{Status, StatusHub},
};

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
                    default_model: preset.default_model(),
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
    default_model: &'static str,
    credential: Option<Credential>,
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

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("desktop_lib=info")))
        .init();

    tauri::Builder::default()
        .setup(|app| {
            // 电脑端常驻在菜单栏，不占用程序坞。
            #[cfg(target_os = "macos")]
            app.set_activation_policy(tauri::ActivationPolicy::Accessory);
            build_tray(app)?;

            let state = build_state(app.handle())?;
            state.apply_settings();
            let server = state.server.clone();
            app.manage(state);
            tauri::async_runtime::spawn(async move {
                let address = SocketAddr::from((Ipv4Addr::UNSPECIFIED, PORT));
                let result = match tokio::net::TcpListener::bind(address).await {
                    Ok(listener) => server::serve(listener, server).await,
                    Err(err) => Err(err),
                };
                if let Err(err) = result {
                    tracing::error!("connection server stopped: {err}");
                }
            });
            Ok(())
        })
        // 关掉窗口只是隐藏，电脑端继续运行，手机端不断线。
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .invoke_handler(tauri::generate_handler![get_status, get_settings, save_settings])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, event| match event {
            // 冷启动时显示主窗口：只在菜单栏的程序不会自动把窗口带到前台。
            // 在后台运行时再次打开 EchoPad（例如在"应用程序"里双击），也显示窗口。
            RunEvent::Ready | RunEvent::Reopen { .. } => show_main_window(app),
            _ => {}
        });
}

/// 菜单栏图标：关掉窗口后从这里重新打开，或者退出电脑端。
fn build_tray(app: &tauri::App) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "打开 EchoPad", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
    let icon = app.default_window_icon().cloned().expect("the app icon is bundled");
    TrayIconBuilder::new()
        .icon(icon)
        .tooltip("EchoPad")
        .menu(&Menu::with_items(app, &[&open, &quit])?)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main_window(app),
            "quit" => app.exit(0),
            _ => {}
        })
        .build(app)?;
    Ok(())
}

fn show_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
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

fn build_state(app: &tauri::AppHandle) -> anyhow::Result<AppState> {
    let config_dir = app.path().app_config_dir()?;
    let pairing = PairingStore::load(config_dir.join("paired-devices.json"))?;
    let settings = SettingsStore::load(config_dir.join("settings.json"))?;
    let host = local_ip_address::local_ip().context("failed to find the LAN address")?;

    let emitter = app.clone();
    let status = Arc::new(StatusHub::new(pairing_link(host, PORT, pairing.pairing_token()), false, move |status| {
        let _ = emitter.emit("status-changed", status);
    }));
    let permission_status = status.clone();
    let input = InputController::spawn(move |granted| permission_status.set_input_permission(granted));

    Ok(AppState {
        server: Arc::new(ServerContext::new(desktop_name(), host, pairing, status, Arc::new(input))),
        settings,
    })
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
        let status = Arc::new(StatusHub::new(pairing_link(host, PORT, pairing.pairing_token()), false, |_| {}));
        AppState {
            server: Arc::new(ServerContext::new("Mac".into(), host, pairing, status, Arc::new(IgnoredInput))),
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
