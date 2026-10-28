//! 屏幕参考：随识别请求附带当前应用和窗口的名称，帮助识别专有名词（ADR-0005）。

#[cfg(target_os = "macos")]
pub fn current() -> Option<String> {
    use objc2_app_kit::NSWorkspace;

    let app = NSWorkspace::sharedWorkspace().frontmostApplication()?;
    let app_name = app.localizedName()?.to_string();
    Some(match focused_window_title(app.processIdentifier()) {
        Some(title) if !title.trim().is_empty() => format!("用户正在「{app_name}」的「{title}」窗口中输入文字"),
        _ => format!("用户正在「{app_name}」中输入文字"),
    })
}

#[cfg(not(target_os = "macos"))]
pub fn current() -> Option<String> {
    None
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
