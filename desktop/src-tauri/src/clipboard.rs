//! 电脑的系统剪贴板（docs/prd/clipboard-sync.md、ADR-0017）：发现用户复制的文字，写入手机端同步来的文字，
//! 读写发送文件用到的文件和图片（docs/prd/send-files.md）；以及按更新时间合并剪贴板的规则（ADR-0018）。

use std::{
    path::PathBuf,
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
    /// 文字同步给了这部手机端。
    TextSent(String),
    /// 文件清单发给了这部手机端，它接着下载（docs/prd/send-files.md）。
    FilesSending(String),
    /// 要发给的手机端不在线。
    PhoneOffline,
    NoText,
    TooLong,
    /// 剪贴板里只有文件夹。
    OnlyFolders,
    /// 要发送的文件读不了，例如所在的磁盘已经推出。
    FilesUnreadable,
}

impl SyncResult {
    /// 给用户的提示，和手机端的写法一致：主窗口里用 Toast，菜单栏菜单用系统通知（DESIGN.md）。
    pub fn message(&self) -> String {
        match self {
            Self::TextSent(phone) => format!("已发送到 {phone}"),
            Self::FilesSending(phone) => format!("正在发送到 {phone}"),
            Self::PhoneOffline => "这部手机不在线".into(),
            Self::NoText => "剪贴板里没有文字".into(),
            Self::TooLong => "文字太长，没有同步".into(),
            Self::OnlyFolders => "不能发送文件夹".into(),
            Self::FilesUnreadable => "读不了要发送的文件".into(),
        }
    }
}

/// 现在的 Unix 毫秒。
pub fn now_millis() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |elapsed| elapsed.as_millis() as i64)
}

/// 多久看一次剪贴板有没有变化。
const POLL_INTERVAL: Duration = Duration::from_millis(500);

/// 剪贴板上的内容。
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ClipboardContent {
    Text(String),
    /// 在访达里复制的文件，可能有文件夹。
    Files(Vec<PathBuf>),
    /// 截图等直接放进剪贴板的图片，转成了 PNG。
    Image(Vec<u8>),
}

/// 电脑的剪贴板。
pub trait Clipboard: Send + Sync {
    /// 剪贴板上的内容，用户主动同步时读取。复制的文件优先，其次是文字，最后是图片：访达复制文件时也放了文件名，
    /// Office 等应用复制文字时也放了图片。
    fn read(&self) -> Option<ClipboardContent>;

    /// 写入手机端同步来的文字。这次写入不算用户复制的内容，监听时不会再报告出来。
    fn write(&self, text: &str);

    /// 放进手机端发来的文件，效果和在访达里复制这些文件一样；只有一张图片时另放一份图片数据，
    /// 方便粘贴到只认图片的地方（docs/prd/send-files.md）。
    fn write_files(&self, files: &[PathBuf]);
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
    use std::{
        path::{Path, PathBuf},
        sync::Mutex,
    };

    use objc2::{AllocAnyThread, available, rc::autoreleasepool, runtime::ProtocolObject};
    use objc2_app_kit::{
        NSBitmapImageFileType, NSBitmapImageRep, NSImage, NSPasteboard, NSPasteboardAccessBehavior, NSPasteboardItem,
        NSPasteboardTypeFileURL, NSPasteboardTypePNG, NSPasteboardTypeString, NSPasteboardTypeTIFF,
    };
    use objc2_foundation::{NSArray, NSDictionary, NSInteger, NSString, NSURL, ns_string};
    use objc2_uniform_type_identifiers::{UTType, UTTypeImage};

    use super::{Clipboard, ClipboardContent};

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
        /// 复制的是文件时不读：那是要手动发送的文件，不是文字（docs/prd/send-files.md）。
        pub(super) fn copied_text(&self, read: bool) -> Option<String> {
            autoreleasepool(|_| {
                let pasteboard = NSPasteboard::generalPasteboard();
                let mut handled = self.handled.lock().unwrap();
                let count = pasteboard.changeCount();
                if count == *handled {
                    return None;
                }
                *handled = count;
                if !read || is_skipped(&pasteboard) || !files(&pasteboard).is_empty() {
                    return None;
                }
                text(&pasteboard)
            })
        }

        /// 用户在「隐私与安全性 › 粘贴」里一律拒绝了 EchoPad（macOS 15.4 起才有这项设置）。
        pub(super) fn access_denied(&self) -> bool {
            available!(macos = 15.4)
                && autoreleasepool(|_| {
                    NSPasteboard::generalPasteboard().accessBehavior() == NSPasteboardAccessBehavior::AlwaysDeny
                })
        }

        /// 换掉剪贴板上的内容，`write` 返回是否写成功。这次写入不算用户复制的内容。
        fn replace(&self, write: impl FnOnce(&NSPasteboard) -> bool) {
            autoreleasepool(|_| {
                let pasteboard = NSPasteboard::generalPasteboard();
                let mut handled = self.handled.lock().unwrap();
                pasteboard.clearContents();
                if !write(&pasteboard) {
                    tracing::warn!("failed to write the clipboard");
                }
                *handled = pasteboard.changeCount();
            });
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

    /// 复制的文件和文件夹，按复制的顺序。
    fn files(pasteboard: &NSPasteboard) -> Vec<PathBuf> {
        let Some(items) = pasteboard.pasteboardItems() else {
            return Vec::new();
        };
        items
            .iter()
            .filter_map(|item| {
                let url = item.stringForType(unsafe { NSPasteboardTypeFileURL })?;
                let url = NSURL::URLWithString(&url)?;
                // 访达放的是文件引用（file:///.file/id=…），换成路径。
                Some(PathBuf::from(url.filePathURL()?.path()?.to_string()))
            })
            .collect()
    }

    fn text(pasteboard: &NSPasteboard) -> Option<String> {
        let text = pasteboard.stringForType(unsafe { NSPasteboardTypeString })?.to_string();
        Some(text).filter(|text| !text.is_empty())
    }

    /// 剪贴板上的图片，转成 PNG。截图放的是 PNG，有的应用只放 TIFF。
    fn image(pasteboard: &NSPasteboard) -> Option<Vec<u8>> {
        if let Some(png) = pasteboard.dataForType(unsafe { NSPasteboardTypePNG }) {
            return Some(png.to_vec());
        }
        let tiff = pasteboard.dataForType(unsafe { NSPasteboardTypeTIFF })?;
        let bitmap = NSBitmapImageRep::imageRepWithData(&tiff)?;
        let png = unsafe { bitmap.representationUsingType_properties(NSBitmapImageFileType::PNG, &NSDictionary::new()) }?;
        Some(png.to_vec())
    }

    /// 按扩展名看文件是不是图片。
    fn is_image(path: &Path) -> bool {
        path.extension()
            .and_then(|extension| UTType::typeWithFilenameExtension(&NSString::from_str(&extension.to_string_lossy())))
            .is_some_and(|kind| kind.conformsToType(unsafe { UTTypeImage }))
    }

    impl Clipboard for SystemClipboard {
        fn read(&self) -> Option<ClipboardContent> {
            autoreleasepool(|_| {
                let pasteboard = NSPasteboard::generalPasteboard();
                let files = files(&pasteboard);
                if !files.is_empty() {
                    return Some(ClipboardContent::Files(files));
                }
                text(&pasteboard)
                    .map(ClipboardContent::Text)
                    .or_else(|| image(&pasteboard).map(ClipboardContent::Image))
            })
        }

        fn write(&self, text: &str) {
            self.replace(|pasteboard| {
                pasteboard.setString_forType(&NSString::from_str(text), unsafe { NSPasteboardTypeString })
            });
        }

        fn write_files(&self, files: &[PathBuf]) {
            self.replace(|pasteboard| {
                let items: Vec<_> = files
                    .iter()
                    .map(|path| {
                        let url = NSURL::fileURLWithPath(&NSString::from_str(&path.to_string_lossy()));
                        let item = NSPasteboardItem::new();
                        if let Some(url) = url.absoluteString() {
                            item.setString_forType(&url, unsafe { NSPasteboardTypeFileURL });
                        }
                        if let [path] = files
                            && is_image(path)
                            && let Some(tiff) = NSImage::initWithContentsOfURL(NSImage::alloc(), &url)
                                .and_then(|image| image.TIFFRepresentation())
                        {
                            item.setData_forType(&tiff, unsafe { NSPasteboardTypeTIFF });
                        }
                        ProtocolObject::from_retained(item)
                    })
                    .collect();
                pasteboard.writeObjects(&NSArray::from_retained_slice(&items))
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

/// Windows 的剪贴板（docs/prd/windows-desktop.md）。
#[cfg(windows)]
mod platform {
    use std::{
        io::Cursor,
        num::NonZeroU32,
        path::{Path, PathBuf},
        sync::Mutex,
    };

    use clipboard_win::{Clipboard as OpenClipboard, Getter, SysResult, formats, options::NoClear, raw};
    use image::{DynamicImage, ImageDecoder, ImageError, ImageFormat, ImageReader};

    use super::{Clipboard, ClipboardContent};

    /// 剪贴板正被别的应用占着时，打开前重试的次数，每次间隔 5 毫秒。
    const OPEN_ATTEMPTS: usize = 10;

    pub struct SystemClipboard {
        /// 最近一次处理过的剪贴板序列号：看过的，或者自己写入后的。看序列号和写入都持有这把锁，
        /// 自己的写入不会在写到一半时被当成用户复制的内容。
        handled: Mutex<u32>,
    }

    impl SystemClipboard {
        pub fn new() -> Self {
            Self { handled: Mutex::new(sequence_number()) }
        }

        /// 剪贴板在上次查看之后有了新内容时，`read` 为 true 就读出其中的文字；为 false 时只记下看过了。
        /// 复制的是文件时不读：那是要手动发送的文件，不是文字（docs/prd/send-files.md）。
        pub(super) fn copied_text(&self, read: bool) -> Option<String> {
            let mut handled = self.handled.lock().unwrap();
            let count = sequence_number();
            if count == *handled {
                return None;
            }
            *handled = count;
            if !read {
                return None;
            }
            let _clipboard = open()?;
            if is_skipped() || !files().is_empty() {
                return None;
            }
            text()
        }

        /// Windows 没有一律拒绝应用读剪贴板的设置。
        pub(super) fn access_denied(&self) -> bool {
            false
        }

        /// 清空剪贴板再用 `write` 写入。这次写入不算用户复制的内容。
        fn replace(&self, write: impl FnOnce() -> SysResult<()>) {
            let mut handled = self.handled.lock().unwrap();
            if let Some(_clipboard) = open()
                && let Err(err) = raw::empty().and_then(|()| write())
            {
                tracing::warn!("failed to write the clipboard: {err}");
            }
            // 关上剪贴板以后再取序列号，写入引起的变化都已经算进去了。
            *handled = sequence_number();
        }
    }

    /// 剪贴板的序列号，内容每变一次加一，不用打开剪贴板就能读。
    fn sequence_number() -> u32 {
        raw::seq_num().map_or(0, NonZeroU32::get)
    }

    /// 打开剪贴板，用完随返回值一起关上。
    fn open() -> Option<OpenClipboard> {
        OpenClipboard::new_attempts(OPEN_ATTEMPTS)
            .inspect_err(|err| tracing::warn!("failed to open the clipboard: {err}"))
            .ok()
    }

    /// 微软约定的几种标记：带有它们的内容不进剪贴板历史、不同步到别的设备，密码管理器用它们标记复制的密码，
    /// 这些内容也不同步。见微软文档的"Cloud Clipboard and Clipboard History Formats"。
    fn is_skipped() -> bool {
        let format = |name| raw::register_format(name).map(NonZeroU32::get);
        // 放了任何数据都算。
        if format("ExcludeClipboardContentFromMonitorProcessing").is_some_and(raw::is_format_avail) {
            return true;
        }
        // 值是 0 的 DWORD 算。
        ["CanIncludeInClipboardHistory", "CanUploadToCloudClipboard"].into_iter().any(|name| {
            format(name).is_some_and(|format| {
                let mut value = Vec::new();
                raw::is_format_avail(format)
                    && formats::RawData(format).read_clipboard(&mut value).is_ok()
                    && value.starts_with(&0u32.to_le_bytes())
            })
        })
    }

    /// 在资源管理器里复制的文件和文件夹，按复制的顺序。
    fn files() -> Vec<PathBuf> {
        let mut files = Vec::new();
        if raw::is_format_avail(formats::CF_HDROP)
            && let Err(err) = formats::FileList.read_clipboard(&mut files)
        {
            tracing::warn!("failed to read the copied files: {err}");
        }
        files
    }

    fn text() -> Option<String> {
        let mut text = String::new();
        if !raw::is_format_avail(formats::CF_UNICODETEXT) {
            return None;
        }
        if let Err(err) = formats::Unicode.read_clipboard(&mut text) {
            tracing::warn!("failed to read the copied text: {err}");
            return None;
        }
        Some(text).filter(|text| !text.is_empty())
    }

    /// 剪贴板上的图片，转成 PNG。浏览器等应用会另放一份 PNG；截图等只放位图，转成 PNG。
    fn image() -> Option<Vec<u8>> {
        if let Some(png) = raw::register_format("PNG").map(NonZeroU32::get).filter(|&png| raw::is_format_avail(png)) {
            let mut data = Vec::new();
            match formats::RawData(png).read_clipboard(&mut data) {
                Ok(_) => return Some(data),
                Err(err) => tracing::warn!("failed to read the copied PNG: {err}"),
            }
        }
        if !raw::is_format_avail(formats::CF_BITMAP) {
            return None;
        }
        let mut bmp = Vec::new();
        if let Err(err) = formats::Bitmap.read_clipboard(&mut bmp) {
            tracing::warn!("failed to read the copied bitmap: {err}");
            return None;
        }
        let mut png = Vec::new();
        let converted = image::load_from_memory_with_format(&bmp, ImageFormat::Bmp)
            .and_then(|image| image.write_to(&mut Cursor::new(&mut png), ImageFormat::Png));
        match converted {
            Ok(()) => Some(png),
            Err(err) => {
                tracing::warn!("failed to convert the copied bitmap: {err}");
                None
            }
        }
    }

    /// 图片文件换成位图（BMP 文件的内容）。按扩展名看不是图片、或者读不了时为 `None`。
    fn bitmap(path: &Path) -> Option<Vec<u8>> {
        ImageFormat::from_path(path).ok()?;
        let decoded = ImageReader::open(path).map_err(ImageError::from).and_then(|reader| {
            let mut decoder = reader.into_decoder()?;
            // 手机拍的照片靠 EXIF 里的方向信息转正，解码时不会自动转。
            let orientation = decoder.orientation()?;
            let mut image = DynamicImage::from_decoder(decoder)?;
            image.apply_orientation(orientation);
            Ok(image)
        });
        let image = decoded.inspect_err(|err| tracing::warn!("failed to read the image: {err}")).ok()?;
        // 带透明度的 BMP 用的是 clipboard-win 认不了的文件头，所以换成不透明的 RGB。
        let mut bmp = Vec::new();
        DynamicImage::ImageRgb8(image.to_rgb8())
            .write_to(&mut Cursor::new(&mut bmp), ImageFormat::Bmp)
            .inspect_err(|err| tracing::warn!("failed to convert the image: {err}"))
            .ok()?;
        Some(bmp)
    }

    impl Clipboard for SystemClipboard {
        fn read(&self) -> Option<ClipboardContent> {
            let _clipboard = open()?;
            let files = files();
            if !files.is_empty() {
                return Some(ClipboardContent::Files(files));
            }
            text().map(ClipboardContent::Text).or_else(|| image().map(ClipboardContent::Image))
        }

        fn write(&self, text: &str) {
            self.replace(|| raw::set_string_with(text, NoClear));
        }

        fn write_files(&self, files: &[PathBuf]) {
            // 解码图片可能要一会儿，先做好再打开剪贴板。
            let bitmap = match files {
                [path] => bitmap(path),
                _ => None,
            };
            let paths: Vec<_> = files.iter().map(|path| path.to_string_lossy().into_owned()).collect();
            self.replace(|| {
                raw::set_file_list_with(&paths, NoClear)?;
                if let Some(bitmap) = &bitmap {
                    raw::set_bitmap_with(bitmap, NoClear)?;
                }
                Ok(())
            });
        }
    }
}
