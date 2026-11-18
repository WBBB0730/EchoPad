//! 电脑的系统剪贴板（docs/prd/clipboard-sync.md、ADR-0017）：发现用户复制的文字，写入手机端同步来的文字；
//! 以及按更新时间合并剪贴板的规则（ADR-0018）。

use std::{
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use crate::protocol::ClipboardText;

/// 同步的文字按 UTF-8 计不超过这个长度，更长的不同步（echopad.proto）。
pub const MAX_TEXT_BYTES: usize = 1_000_000;

/// 剪贴板同步里最新的一条：内容和它最后更新的时间（ADR-0018）。关着接收时，它可能和本机剪贴板上的不一样。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SyncedClipboard {
    /// 启动以后还没见过内容时为 `None`。
    text: Option<String>,
    /// Unix 毫秒。
    updated_at: i64,
}

impl SyncedClipboard {
    /// 启动的那一刻：内容未知，之前的复制都算旧的。
    pub fn unknown_since(now: i64) -> Self {
        Self { text: None, updated_at: now }
    }

    pub fn text(&self) -> Option<&str> {
        self.text.as_deref()
    }

    /// 在本机复制了 `text`，返回要推送出去的消息。
    /// 更新时间取"现在"和"已知最新的更新时间加 1 毫秒"中较大的，本机新复制的总比见过的新。
    pub fn copy(&mut self, text: String, now: i64) -> ClipboardText {
        self.updated_at = now.max(self.updated_at + 1);
        self.text = Some(text.clone());
        ClipboardText { text, updated_at: self.updated_at }
    }

    /// 收到别的设备的内容：比现有的新时才用，返回是否用了。先比更新时间，相同再比文字，任意两条都分得出新旧。
    pub fn merge(&mut self, incoming: &ClipboardText) -> bool {
        if (incoming.updated_at, Some(incoming.text.as_str())) <= (self.updated_at, self.text.as_deref()) {
            return false;
        }
        self.text = Some(incoming.text.clone());
        self.updated_at = incoming.updated_at;
        true
    }
}

/// 用户在电脑端主动同步的结果。
#[derive(Debug, PartialEq, Eq)]
pub enum SyncResult {
    /// 发给了这几部手机端，按名称列出。
    Sent(Vec<String>),
    NoPhoneOnline,
    NoText,
    TooLong,
}

impl SyncResult {
    /// 给用户的提示，和手机端的写法一致：主窗口里用 Toast，菜单栏菜单用系统通知（DESIGN.md）。
    pub fn message(&self) -> String {
        match self {
            Self::Sent(names) if names.len() == 1 => format!("已同步到 {}", names[0]),
            Self::Sent(names) => format!("已同步到 {} 部手机", names.len()),
            Self::NoPhoneOnline => "没有在线的手机".into(),
            Self::NoText => "剪贴板里没有文字".into(),
            Self::TooLong => "文字太长，没有同步".into(),
        }
    }
}

/// 现在的 Unix 毫秒。
pub fn now_millis() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |elapsed| elapsed.as_millis() as i64)
}

/// 多久看一次剪贴板有没有变化。
const POLL_INTERVAL: Duration = Duration::from_millis(500);

/// 电脑的剪贴板。
pub trait Clipboard: Send + Sync {
    /// 剪贴板上的文字，没有文字时为 `None`。用户主动同步时读取。
    fn read(&self) -> Option<String>;

    /// 写入手机端同步来的文字。这次写入不算用户复制的内容，监听时不会再报告出来。
    fn write(&self, text: &str);
}

pub use platform::SystemClipboard;

impl SystemClipboard {
    /// 在单独的线程里每 0.5 秒看一次剪贴板的变化计数，变了才读取（ADR-0017），启动前就在剪贴板上的内容不报告。
    /// `enabled` 为 false（关着自动发送）时不读剪贴板；用户复制了文字时调用 `on_copy`；
    /// `on_access_denied` 报告开着自动发送时系统是否一律拒绝 EchoPad 读剪贴板，只在变化时调用。
    pub fn watch(
        self: Arc<Self>,
        enabled: impl Fn() -> bool + Send + 'static,
        on_copy: impl Fn(String) + Send + 'static,
        on_access_denied: impl Fn(bool) + Send + 'static,
    ) {
        std::thread::Builder::new()
            .name("clipboard".into())
            .spawn(move || {
                let mut denied = None;
                loop {
                    std::thread::sleep(POLL_INTERVAL);
                    let enabled = enabled();
                    let access_denied = enabled && self.access_denied();
                    if denied != Some(access_denied) {
                        denied = Some(access_denied);
                        on_access_denied(access_denied);
                    }
                    if let Some(text) = self.copied_text(enabled && !access_denied) {
                        on_copy(text);
                    }
                }
            })
            .expect("failed to spawn clipboard thread");
    }
}

#[cfg(target_os = "macos")]
mod platform {
    use std::sync::Mutex;

    use objc2::{available, rc::autoreleasepool};
    use objc2_app_kit::{NSPasteboard, NSPasteboardAccessBehavior, NSPasteboardTypeString};
    use objc2_foundation::{NSInteger, NSString, ns_string};

    use super::Clipboard;

    pub struct SystemClipboard {
        /// 最近一次处理过的变化计数：看过的，或者自己写入后的。看计数和写入都持有这把锁，
        /// 自己的写入不会在写到一半时被当成用户复制的内容。
        handled: Mutex<NSInteger>,
    }

    impl SystemClipboard {
        pub fn new() -> Self {
            let current = autoreleasepool(|_| NSPasteboard::generalPasteboard().changeCount());
            Self { handled: Mutex::new(current) }
        }

        /// 剪贴板在上次查看之后有了新内容时，`read` 为 true 就读出其中的文字；为 false 时只记下看过了。
        pub(super) fn copied_text(&self, read: bool) -> Option<String> {
            autoreleasepool(|_| {
                let pasteboard = NSPasteboard::generalPasteboard();
                let mut handled = self.handled.lock().unwrap();
                let count = pasteboard.changeCount();
                if count == *handled {
                    return None;
                }
                *handled = count;
                if !read || is_skipped(&pasteboard) {
                    return None;
                }
                let text = pasteboard.stringForType(unsafe { NSPasteboardTypeString })?.to_string();
                Some(text).filter(|text| !text.is_empty())
            })
        }

        /// 用户在「隐私与安全性 › 粘贴」里一律拒绝了 EchoPad（macOS 15.4 起才有这项设置）。
        pub(super) fn access_denied(&self) -> bool {
            available!(macos = 15.4)
                && autoreleasepool(|_| {
                    NSPasteboard::generalPasteboard().accessBehavior() == NSPasteboardAccessBehavior::AlwaysDeny
                })
        }
    }

    /// 密码管理器等标记的敏感内容和临时内容，不同步（nspasteboard.org 的约定）。
    fn is_skipped(pasteboard: &NSPasteboard) -> bool {
        let Some(types) = pasteboard.types() else {
            return false;
        };
        [ns_string!("org.nspasteboard.ConcealedType"), ns_string!("org.nspasteboard.TransientType")]
            .into_iter()
            .any(|skipped| types.containsObject(skipped))
    }

    impl Clipboard for SystemClipboard {
        fn read(&self) -> Option<String> {
            autoreleasepool(|_| {
                let text = NSPasteboard::generalPasteboard().stringForType(unsafe { NSPasteboardTypeString })?;
                Some(text.to_string()).filter(|text| !text.is_empty())
            })
        }

        fn write(&self, text: &str) {
            autoreleasepool(|_| {
                let pasteboard = NSPasteboard::generalPasteboard();
                let mut handled = self.handled.lock().unwrap();
                pasteboard.clearContents();
                if !pasteboard.setString_forType(&NSString::from_str(text), unsafe { NSPasteboardTypeString }) {
                    tracing::warn!("failed to write the clipboard");
                }
                *handled = pasteboard.changeCount();
            });
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text(text: &str, updated_at: i64) -> ClipboardText {
        ClipboardText { text: text.into(), updated_at }
    }

    #[test]
    fn only_newer_content_replaces_the_current_one() {
        let mut clipboard = SyncedClipboard::unknown_since(1_000);
        assert!(!clipboard.merge(&text("copied before sync was on", 900)));
        assert!(clipboard.merge(&text("newer", 2_000)));
        assert!(!clipboard.merge(&text("older", 1_500)));
        // 同一条内容转了一圈回来，不算新的，转发就此停下。
        assert!(!clipboard.merge(&text("newer", 2_000)));
    }

    #[test]
    fn content_updated_at_the_same_moment_is_ordered_by_text() {
        let mut clipboard = SyncedClipboard::unknown_since(0);
        assert!(clipboard.merge(&text("a", 2_000)));
        assert!(clipboard.merge(&text("b", 2_000)));
        assert!(!clipboard.merge(&text("a", 2_000)));
    }

    #[test]
    fn a_new_copy_is_newer_than_anything_seen_even_with_a_slow_clock() {
        let mut clipboard = SyncedClipboard::unknown_since(0);
        // 发来这条的设备时钟比本机快。
        clipboard.merge(&text("from a fast clock", 5_000));
        assert_eq!(clipboard.copy("copied here".into(), 3_000), text("copied here", 5_001));
        assert_eq!(clipboard.copy("copied later".into(), 9_000), text("copied later", 9_000));
    }
}

/// Windows 版再做。
#[cfg(not(target_os = "macos"))]
mod platform {
    use super::Clipboard;

    pub struct SystemClipboard;

    impl SystemClipboard {
        pub fn new() -> Self {
            Self
        }

        pub(super) fn copied_text(&self, _read: bool) -> Option<String> {
            None
        }

        pub(super) fn access_denied(&self) -> bool {
            false
        }
    }

    impl Clipboard for SystemClipboard {
        fn read(&self) -> Option<String> {
            None
        }

        fn write(&self, _text: &str) {}
    }
}
