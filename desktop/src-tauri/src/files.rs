//! 发送文件（docs/prd/send-files.md、ADR-0021）：手机端上传来的文件存进"下载"文件夹；
//! 电脑端剪贴板里的文件等用户确认后发给手机端，由手机端逐个下载。

use std::{
    io,
    path::{Path, PathBuf},
    pin::pin,
};

use axum::body::{Body, Bytes};
use futures_util::{Stream, TryStreamExt};
use tempfile::PathPersistError;
use tokio::io::{AsyncWriteExt, BufWriter};
use tokio_util::io::{ReaderStream, StreamReader};

/// 电脑端要发给手机端的一个文件。
#[derive(Clone)]
pub struct OutgoingFile {
    pub name: String,
    pub size: u64,
    source: Source,
}

#[derive(Clone)]
enum Source {
    /// 在访达里复制的文件，下载时再读。
    Path(PathBuf),
    /// 截图这类没有文件的图片，转成 PNG 留在内存里。
    Png(Bytes),
}

impl OutgoingFile {
    /// 剪贴板里复制的文件，跳过文件夹。
    pub fn from_paths(paths: Vec<PathBuf>) -> io::Result<Vec<Self>> {
        let mut files = Vec::new();
        for path in paths {
            let metadata = std::fs::metadata(&path)?;
            if metadata.is_file() {
                files.push(Self { name: file_name(&path), size: metadata.len(), source: Source::Path(path) });
            }
        }
        Ok(files)
    }

    /// 剪贴板里的图片。
    pub fn image(png: Vec<u8>) -> Self {
        Self { name: "图片.png".into(), size: png.len() as u64, source: Source::Png(png.into()) }
    }

    /// 文件的长度和内容，内容边读边发。
    pub async fn body(&self) -> io::Result<(u64, Body)> {
        match &self.source {
            Source::Path(path) => {
                let file = tokio::fs::File::open(path).await?;
                let size = file.metadata().await?.len();
                Ok((size, Body::from_stream(ReaderStream::new(file))))
            }
            Source::Png(png) => Ok((png.len() as u64, Body::from(png.clone()))),
        }
    }
}

/// 剪贴板里要发给手机端 `phone` 的文件，等用户确认。
pub struct PendingFiles {
    pub files: Vec<OutgoingFile>,
    pub phone: String,
}

impl PendingFiles {
    /// 确认框里的问题，例如"把 3 个文件（12.4 MB）发送到 小米手机？"。
    pub fn question(&self) -> String {
        let size = self.files.iter().map(|file| file.size).sum();
        format!("把 {} 个文件（{}）发送到 {}？", self.files.len(), format_size(size), self.phone)
    }
}

/// 把手机端发来的一个文件存进 `dir`：先写临时文件，写完改成不重名的文件名；中途出错时临时文件随之删掉，
/// 不留下没传完的文件。
pub async fn save<E>(dir: &Path, name: &str, contents: impl Stream<Item = Result<Bytes, E>>) -> io::Result<PathBuf>
where
    E: Into<Box<dyn std::error::Error + Send + Sync>>,
{
    let name = file_name(Path::new(name));
    #[cfg_attr(not(unix), allow(unused_mut))]
    let mut builder = tempfile::Builder::new();
    // 临时文件默认只有自己能读写；收到的文件是普通的下载，权限和新建的普通文件一样，由 umask 决定。
    #[cfg(unix)]
    builder.permissions(std::os::unix::fs::PermissionsExt::from_mode(0o666));
    let (file, mut temp) = builder.tempfile_in(dir)?.into_parts();
    let mut writer = BufWriter::new(tokio::fs::File::from_std(file));
    let mut reader = pin!(StreamReader::new(contents.map_err(io::Error::other)));
    tokio::io::copy(&mut reader, &mut writer).await?;
    writer.shutdown().await?;
    for copy in 0.. {
        let path = dir.join(numbered(&name, copy));
        match temp.persist_noclobber(&path) {
            Ok(()) => return Ok(path),
            Err(PathPersistError { error, path }) if error.kind() == io::ErrorKind::AlreadyExists => temp = path,
            Err(PathPersistError { error, .. }) => return Err(error),
        }
    }
    unreachable!("there is always an unused name")
}

/// 只取文件名本身，丢掉前面的路径，手机端发来的文件不会写到"下载"文件夹以外。Windows 上再把文件名里不允许的字符
/// 换成下划线、换掉系统保留的名字，否则存不下来（docs/prd/windows-desktop.md）。
fn file_name(path: &Path) -> String {
    let name = path.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_default();
    #[cfg(windows)]
    let name = sanitize_filename::sanitize_with_options(
        name,
        sanitize_filename::Options { replacement: "_", ..Default::default() },
    );
    if name.is_empty() { "未命名".into() } else { name }
}

/// 重名时在文件名后加序号，例如"截图 (1).png"。
fn numbered(name: &str, copy: u32) -> String {
    if copy == 0 {
        return name.into();
    }
    let path = Path::new(name);
    let stem = path.file_stem().map_or_else(|| name.into(), |stem| stem.to_string_lossy());
    match path.extension() {
        Some(extension) => format!("{stem} ({copy}).{}", extension.to_string_lossy()),
        None => format!("{stem} ({copy})"),
    }
}

/// 文件大小，和访达一样按 1000 进位，保留一位小数，例如"12.4 MB"。
fn format_size(bytes: u64) -> String {
    const UNITS: [&str; 4] = ["KB", "MB", "GB", "TB"];
    if bytes < 1000 {
        return format!("{bytes} 字节");
    }
    let mut value = bytes as f64 / 1000.0;
    let mut unit = 0;
    while value >= 1000.0 && unit < UNITS.len() - 1 {
        value /= 1000.0;
        unit += 1;
    }
    format!("{value:.1} {}", UNITS[unit])
}

#[cfg(test)]
mod tests {
    use futures_util::stream;

    use super::*;

    fn temp_dir() -> PathBuf {
        let dir = std::env::temp_dir().join(format!("echopad-files-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn contents(text: &'static str) -> impl Stream<Item = Result<Bytes, io::Error>> {
        stream::iter([Ok(Bytes::from_static(text.as_bytes()))])
    }

    #[tokio::test]
    async fn saved_files_get_a_number_when_the_name_is_taken() {
        let dir = temp_dir();
        assert_eq!(save(&dir, "截图.png", contents("one")).await.unwrap(), dir.join("截图.png"));
        let second = save(&dir, "截图.png", contents("two")).await.unwrap();
        assert_eq!(second, dir.join("截图 (1).png"));
        assert_eq!(std::fs::read_to_string(second).unwrap(), "two");
        save(&dir, "README", contents("three")).await.unwrap();
        assert_eq!(save(&dir, "README", contents("four")).await.unwrap(), dir.join("README (1)"));
    }

    #[tokio::test]
    async fn only_the_file_name_is_kept() {
        let dir = temp_dir();
        assert_eq!(save(&dir, "../../etc/passwd", contents("x")).await.unwrap(), dir.join("passwd"));
        assert_eq!(save(&dir, "..", contents("x")).await.unwrap(), dir.join("未命名"));
    }

    #[cfg(windows)]
    #[tokio::test]
    async fn names_windows_cannot_store_are_changed() {
        let dir = temp_dir();
        assert_eq!(save(&dir, "报告 \"终版\" 12:30?.pdf", contents("x")).await.unwrap(), dir.join("报告 _终版_ 12_30_.pdf"));
        assert_eq!(save(&dir, "会议记录. ", contents("x")).await.unwrap(), dir.join("会议记录_"));
        assert_eq!(save(&dir, "con.txt", contents("x")).await.unwrap(), dir.join("_"));
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn saved_files_get_the_same_permissions_as_ordinary_files() {
        use std::os::unix::fs::PermissionsExt;

        let dir = temp_dir();
        let saved = save(&dir, "a.txt", contents("a")).await.unwrap();
        let ordinary = dir.join("ordinary.txt");
        std::fs::File::create(&ordinary).unwrap();
        let mode = |path: &Path| std::fs::metadata(path).unwrap().permissions().mode();
        assert_eq!(mode(&saved), mode(&ordinary));
    }

    #[tokio::test]
    async fn a_failed_upload_leaves_nothing_behind() {
        let dir = temp_dir();
        let broken = stream::iter([Ok(Bytes::from_static(b"half")), Err(io::Error::other("connection lost"))]);
        assert!(save(&dir, "大文件.zip", broken).await.is_err());
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 0);
    }

    #[test]
    fn folders_on_the_clipboard_are_skipped() {
        let dir = temp_dir();
        std::fs::write(dir.join("a.txt"), "hello").unwrap();
        std::fs::create_dir(dir.join("folder")).unwrap();
        let files = OutgoingFile::from_paths(vec![dir.join("folder"), dir.join("a.txt")]).unwrap();
        assert_eq!(files.iter().map(|file| (file.name.as_str(), file.size)).collect::<Vec<_>>(), [("a.txt", 5)]);
        assert!(OutgoingFile::from_paths(vec![dir.join("missing")]).is_err());
    }

    #[test]
    fn the_question_names_the_count_size_and_phone() {
        let pending = PendingFiles {
            files: vec![OutgoingFile::image(vec![0; 400_000]), OutgoingFile::image(vec![0; 12_000_000])],
            phone: "小米手机".into(),
        };
        assert_eq!(pending.question(), "把 2 个文件（12.4 MB）发送到 小米手机？");
    }

    #[test]
    fn sizes_read_like_finder() {
        assert_eq!(format_size(512), "512 字节");
        assert_eq!(format_size(1_500), "1.5 KB");
        assert_eq!(format_size(12_400_000), "12.4 MB");
        assert_eq!(format_size(3_200_000_000), "3.2 GB");
    }
}
