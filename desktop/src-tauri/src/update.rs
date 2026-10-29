//! 应用内更新（docs/prd/in-app-update.md、ADR-0009）：定时检查最新的 GitHub Release，
//! 发现新版本就在后台下载，用户确认或退出时安装。开发版只提示，确认后打开 Release 页面。

use std::{
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use semver::Version;
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter};
use tauri_plugin_opener::OpenerExt;
use tauri_plugin_updater::{Update, UpdaterExt};

/// 启动后等一个随机时间再检查，不和启动抢资源。
const STARTUP_DELAY_MAX: Duration = Duration::from_secs(30);
const CHECK_INTERVAL: Duration = Duration::from_secs(4 * 60 * 60);
/// 检查失败（网络波动、正在发版）后静默重试的间隔。
const CHECK_RETRY: Duration = Duration::from_secs(15 * 60);
/// 打开设置时自动检查的冷却时间；手动检查不受限。
const CHECK_COOLDOWN: Duration = Duration::from_secs(5 * 60);
const RELEASES_URL: &str = "https://github.com/WBBB0730/EchoPad/releases";

/// 更新日志的一段，由发版流程切好、以 JSON 写进更新清单的 `notes`（ADR-0011）。
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct ChangelogEntry {
    pub version: String,
    pub date: Option<String>,
    pub body: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum UpdatePhase {
    /// 已是最新，检查失败时也显示为这个状态。
    UpToDate,
    Checking,
    /// 开发版发现了新版本：只提示，不下载。
    Available,
    Downloading,
    /// 新版本已下载好，等用户确认或退出时安装。
    Ready,
    /// 下载或安装失败，可以重新检查。
    Failed,
}

/// 推给界面的更新状态。
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateStatus {
    pub phase: UpdatePhase,
    pub current_version: String,
    pub available_version: Option<String>,
    /// 当前版本之后、到新版本为止的更新日志（新→旧）。
    pub changelog: Vec<ChangelogEntry>,
    /// 首页是否显示"新版本可用"：可以安装，或开发版发现了新版本。提示不能关闭。
    pub prompt: bool,
    /// 确认后是否安装并重启；开发版是打开 Release 页面。
    pub installable: bool,
}

struct State {
    phase: UpdatePhase,
    available: Option<(String, Vec<ChangelogEntry>)>,
    /// 已下载好的更新包。
    downloaded: Option<(Update, Vec<u8>)>,
    last_check: Option<Instant>,
}

pub struct Updater {
    app: AppHandle,
    /// 开发版（`tauri dev`）不下载安装（同 DevCube ADR-0017）。
    installable: bool,
    state: Mutex<State>,
    /// 同一时间只做一次检查；并发的请求等它结束后直接返回结果。
    checking: tokio::sync::Mutex<()>,
}

impl Updater {
    pub fn new(app: AppHandle) -> Self {
        Self {
            app,
            installable: !tauri::is_dev(),
            state: Mutex::new(State { phase: UpdatePhase::UpToDate, available: None, downloaded: None, last_check: None }),
            checking: tokio::sync::Mutex::new(()),
        }
    }

    /// 启动后稍等再检查，之后每 4 小时一次；检查失败时 15 分钟后重试。
    pub fn start(self: Arc<Self>) {
        tauri::async_runtime::spawn(async move {
            tokio::time::sleep(STARTUP_DELAY_MAX.mul_f64(rand::random())).await;
            loop {
                let checked = self.check(true).await;
                tokio::time::sleep(if checked { CHECK_INTERVAL } else { CHECK_RETRY }).await;
            }
        });
    }

    pub fn status(&self) -> UpdateStatus {
        let state = self.state.lock().unwrap();
        UpdateStatus {
            phase: state.phase,
            current_version: self.app.package_info().version.to_string(),
            available_version: state.available.as_ref().map(|(version, _)| version.clone()),
            changelog: state.available.as_ref().map(|(_, changelog)| changelog.clone()).unwrap_or_default(),
            prompt: matches!(state.phase, UpdatePhase::Ready | UpdatePhase::Available),
            installable: self.installable,
        }
    }

    /// 检查一次；`force` 为 false 时距上次检查不足 5 分钟就跳过。返回检查是否成功。
    pub async fn check(&self, force: bool) -> bool {
        let Ok(_checking) = self.checking.try_lock() else {
            let _ = self.checking.lock().await;
            return true;
        };
        {
            let mut state = self.state.lock().unwrap();
            let cooling = state.last_check.is_some_and(|at| at.elapsed() < CHECK_COOLDOWN);
            // 新版本已下好就等安装，下次启动再查更新的版本。
            if state.phase == UpdatePhase::Ready || (!force && cooling) {
                return true;
            }
            state.last_check = Some(Instant::now());
            // 开发版已提示新版本时再检查，不把提示撤回。
            if state.phase != UpdatePhase::Available {
                state.phase = UpdatePhase::Checking;
            }
        }
        self.emit();

        let found = match self.app.updater() {
            Ok(updater) => updater.check().await,
            Err(err) => Err(err),
        };
        match found {
            Ok(Some(update)) => {
                self.offer(update).await;
                true
            }
            Ok(None) => {
                self.settle_up_to_date();
                true
            }
            Err(err) => {
                tracing::warn!("update check failed, treated as up to date: {err}");
                self.settle_up_to_date();
                false
            }
        }
    }

    /// 没有更新或检查失败：显示为已是最新，但开发版已有的更新提示不撤回。
    fn settle_up_to_date(&self) {
        {
            let mut state = self.state.lock().unwrap();
            if state.phase == UpdatePhase::Available {
                return;
            }
            state.phase = UpdatePhase::UpToDate;
            state.available = None;
        }
        self.emit();
    }

    async fn offer(&self, update: Update) {
        let changelog = pick_changelog(update.body.as_deref(), &update.current_version, &update.version);
        // 安装失败后再检查到同一个版本，不用重新下载。
        let downloaded = {
            let mut state = self.state.lock().unwrap();
            let downloaded = state.downloaded.as_ref().is_some_and(|(ready, _)| ready.version == update.version);
            state.available = Some((update.version.clone(), changelog));
            state.phase = match (downloaded, self.installable) {
                (true, _) => UpdatePhase::Ready,
                (false, true) => UpdatePhase::Downloading,
                (false, false) => UpdatePhase::Available,
            };
            downloaded
        };
        self.emit();
        if downloaded || !self.installable {
            return;
        }

        let downloaded = update.download(|_, _| {}, || {}).await;
        {
            let mut state = self.state.lock().unwrap();
            match downloaded {
                Ok(bytes) => {
                    state.downloaded = Some((update, bytes));
                    state.phase = UpdatePhase::Ready;
                }
                Err(err) => {
                    tracing::warn!("failed to download the update: {err}");
                    state.phase = UpdatePhase::Failed;
                }
            }
        }
        self.emit();
    }

    /// 用户在更新弹窗里确认：安装并重启；开发版打开该版本的 Release 页面。
    pub fn apply(&self) -> Result<(), String> {
        if !self.installable {
            let version = self.status().available_version.ok_or("没有可用的更新")?;
            return self
                .app
                .opener()
                .open_url(format!("{RELEASES_URL}/tag/v{version}"), None::<&str>)
                .map_err(|err| err.to_string());
        }
        if self.install() {
            self.app.restart();
        }
        Err("安装更新失败".into())
    }

    /// 从菜单栏退出时，新版本已经下好就先装上，下次打开就是新版本。
    pub fn install_on_quit(&self) {
        self.install();
    }

    fn install(&self) -> bool {
        let installed = {
            let state = self.state.lock().unwrap();
            let Some((update, bytes)) = state.downloaded.as_ref() else {
                return false;
            };
            update.install(bytes)
        };
        if let Err(err) = installed {
            tracing::error!("failed to install the update: {err}");
            self.state.lock().unwrap().phase = UpdatePhase::Failed;
            self.emit();
            return false;
        }
        true
    }

    fn emit(&self) {
        let _ = self.app.emit("update-status-changed", self.status());
    }
}

/// 从更新清单的 `notes` 里挑出当前版本之后、到目标版本为止的段落（新→旧）；没写的版本自然缺席。
fn pick_changelog(notes: Option<&str>, current: &str, target: &str) -> Vec<ChangelogEntry> {
    let (Ok(current), Ok(target)) = (Version::parse(current), Version::parse(target)) else {
        return Vec::new();
    };
    let Some(Ok(entries)) = notes.map(serde_json::from_str::<Vec<ChangelogEntry>>) else {
        return Vec::new();
    };
    let mut picked: Vec<_> = entries
        .into_iter()
        .filter_map(|entry| Some((Version::parse(&entry.version).ok()?, entry)))
        .filter(|(version, _)| *version > current && *version <= target)
        .collect();
    picked.sort_by(|(a, _), (b, _)| b.cmp(a));
    picked.into_iter().map(|(_, entry)| entry).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(version: &str) -> ChangelogEntry {
        ChangelogEntry { version: version.into(), date: None, body: format!("- {version}") }
    }

    fn notes(versions: &[&str]) -> String {
        serde_json::to_string(&versions.iter().map(|version| entry(version)).collect::<Vec<_>>()).unwrap()
    }

    fn versions(entries: &[ChangelogEntry]) -> Vec<&str> {
        entries.iter().map(|entry| entry.version.as_str()).collect()
    }

    #[test]
    fn picks_entries_after_the_current_version_up_to_the_target_newest_first() {
        let notes = notes(&["1.3.0", "1.0.0", "1.2.0", "1.1.0", "0.9.0"]);
        let picked = pick_changelog(Some(&notes), "1.0.0", "1.2.0");
        assert_eq!(versions(&picked), ["1.2.0", "1.1.0"]);
        assert_eq!(picked[0], entry("1.2.0"));
    }

    #[test]
    fn versions_without_entries_are_skipped() {
        let notes = notes(&["1.3.0", "1.1.0"]);
        assert_eq!(versions(&pick_changelog(Some(&notes), "1.0.0", "1.3.0")), ["1.3.0", "1.1.0"]);
        assert!(pick_changelog(Some(&notes), "1.1.0", "1.2.0").is_empty());
    }

    #[test]
    fn missing_or_malformed_notes_give_no_entries() {
        assert!(pick_changelog(None, "1.0.0", "1.1.0").is_empty());
        assert!(pick_changelog(Some("- 不是 JSON"), "1.0.0", "1.1.0").is_empty());
        assert!(pick_changelog(Some(&notes(&["1.1.0"])), "not a version", "1.1.0").is_empty());
    }
}
