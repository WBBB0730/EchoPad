//! 屏幕参考：随识别请求附带当前应用和窗口的名称，帮助识别专有名词（ADR-0005）。

#[cfg(target_os = "macos")]
pub fn current() -> Option<String> {
    use objc2_app_kit::NSWorkspace;

    let app = NSWorkspace::sharedWorkspace().frontmostApplication()?;
    let app_name = app.localizedName()?.to_string();
    Some(describe(&app_name, focused_window_title(app.processIdentifier())))
}

/// 前台窗口所属的应用和窗口标题，不需要权限（docs/prd/windows-desktop.md）。
#[cfg(windows)]
pub fn current() -> Option<String> {
    use windows::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowTextW, GetWindowThreadProcessId};

    let window = unsafe { GetForegroundWindow() };
    if window.is_invalid() {
        return None;
    }
    let mut process_id = 0;
    unsafe { GetWindowThreadProcessId(window, Some(&mut process_id)) };
    let app_name = windows_app::name(process_id)?;
    let mut title = [0; 512];
    let length = unsafe { GetWindowTextW(window, &mut title) };
    let title = String::from_utf16_lossy(&title[..length.max(0) as usize]);
    Some(describe(&app_name, Some(title)))
}

fn describe(app_name: &str, window_title: Option<String>) -> String {
    match window_title {
        Some(title) if !title.trim().is_empty() => format!("用户正在「{app_name}」的「{title}」窗口中输入文字"),
        _ => format!("用户正在「{app_name}」中输入文字"),
    }
}

/// 读取窗口标题需要辅助功能权限，模拟键鼠本来就需要这个权限。
#[cfg(target_os = "macos")]
fn focused_window_title(pid: i32) -> Option<String> {
    use objc2_application_services::AXUIElement;
    use objc2_core_foundation::CFString;

    let app = unsafe { AXUIElement::new_application(pid) };
    let window = copy_attribute(&app, "AXFocusedWindow")?.downcast::<AXUIElement>().ok()?;
    let title = copy_attribute(&window, "AXTitle")?.downcast::<CFString>().ok()?;
    Some(title.to_string())
}

#[cfg(target_os = "macos")]
fn copy_attribute(
    element: &objc2_application_services::AXUIElement,
    name: &'static str,
) -> Option<objc2_core_foundation::CFRetained<objc2_core_foundation::CFType>> {
    use std::ptr::{self, NonNull};

    use objc2_application_services::AXError;
    use objc2_core_foundation::{CFRetained, CFString, CFType};

    let mut value: *const CFType = ptr::null();
    let error = unsafe { element.copy_attribute_value(&CFString::from_static_str(name), NonNull::from(&mut value)) };
    if error != AXError::Success {
        return None;
    }
    // AXUIElementCopyAttributeValue 遵循 Copy 规则，返回的值归调用方所有。
    NonNull::new(value.cast_mut()).map(|value| unsafe { CFRetained::from_raw(value) })
}

/// Windows 上一个进程的应用名称。
#[cfg(windows)]
mod windows_app {
    use std::path::Path;

    use windows::{
        Win32::{
            Foundation::CloseHandle,
            Storage::FileSystem::{GetFileVersionInfoSizeW, GetFileVersionInfoW, VerQueryValueW},
            System::Threading::{OpenProcess, PROCESS_NAME_WIN32, PROCESS_QUERY_LIMITED_INFORMATION, QueryFullProcessImageNameW},
        },
        core::{HSTRING, PWSTR},
    };

    /// 程序文件里的"文件说明"，例如 chrome.exe 是"Google Chrome"；没有时用程序的文件名。
    pub fn name(process_id: u32) -> Option<String> {
        let path = program_path(process_id)?;
        file_description(&path)
            .or_else(|| Path::new(&path).file_stem().map(|stem| stem.to_string_lossy().into_owned()))
    }

    fn program_path(process_id: u32) -> Option<String> {
        let process = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, process_id) }.ok()?;
        let mut path = [0; 1024];
        let mut length = path.len() as u32;
        let queried = unsafe { QueryFullProcessImageNameW(process, PROCESS_NAME_WIN32, PWSTR(path.as_mut_ptr()), &mut length) };
        let _ = unsafe { CloseHandle(process) };
        queried.ok()?;
        Some(String::from_utf16_lossy(&path[..length as usize]))
    }

    /// 先找出版本信息里的语言和代码页，再取这个语言的"文件说明"。
    fn file_description(path: &str) -> Option<String> {
        let path = HSTRING::from(path);
        let size = unsafe { GetFileVersionInfoSizeW(&path, None) };
        if size == 0 {
            return None;
        }
        // 版本信息里的值都按 UTF-16 的单元对齐，用 u16 的缓冲区，取出的指针才是对齐的。
        let mut info = vec![0u16; (size as usize).div_ceil(2)];
        unsafe { GetFileVersionInfoW(&path, None, size, info.as_mut_ptr().cast()) }.ok()?;
        // 语言和代码页各一个单元；这一项的长度按字节算。
        let [language, code_page] = query(&info, "\\VarFileInfo\\Translation", |bytes| bytes / 2)?.get(..2)? else {
            return None;
        };
        // 字符串的长度按字符算，含结尾的 0。
        let key = format!("\\StringFileInfo\\{language:04x}{code_page:04x}\\FileDescription");
        let description = String::from_utf16_lossy(query(&info, &key, |chars| chars)?);
        let description = description.trim_end_matches('\0').trim();
        (!description.is_empty()).then(|| description.to_owned())
    }

    /// 版本信息里 `key` 对应的值，指向 `info` 里面。`units` 把系统给的长度换成 UTF-16 的单元数。
    fn query<'a>(info: &'a [u16], key: &str, units: impl FnOnce(usize) -> usize) -> Option<&'a [u16]> {
        let mut value = std::ptr::null_mut();
        let mut length = 0;
        let found = unsafe { VerQueryValueW(info.as_ptr().cast(), &HSTRING::from(key), &mut value, &mut length) };
        if !found.as_bool() || value.is_null() {
            return None;
        }
        Some(unsafe { std::slice::from_raw_parts(value.cast::<u16>(), units(length as usize)) })
    }
}
