//! 在电脑上模拟键鼠（ADR-0001、docs/prd/touchpad.md）。enigo 只在专用线程里使用。

use std::{
    sync::mpsc::{self, RecvTimeoutError},
    thread,
    time::{Duration, Instant},
};

use enigo::{Axis, Button, Coordinate, Direction, Enigo, Key, Keyboard, Mouse, Settings};

/// 每隔这么久检查一次辅助功能权限：用户授权后无需重启电脑端；使用中被关掉时界面也会更新。
const PERMISSION_CHECK_INTERVAL: Duration = Duration::from_secs(1);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MouseButton {
    Left,
    Right,
}

/// 特殊键：作用于电脑的单个按键，包括媒体键（docs/prd/typing.md）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SpecialKey {
    Enter,
    Backspace,
    Delete,
    Tab,
    Escape,
    Space,
    Up,
    Down,
    Left,
    Right,
    Home,
    End,
    PageUp,
    PageDown,
    PlayPause,
    NextTrack,
    PreviousTrack,
    VolumeUp,
    VolumeDown,
    Mute,
}

/// 快捷操作：按电脑的系统换成对应的组合键。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Shortcut {
    Copy,
    Cut,
    Paste,
    Undo,
    Redo,
    SelectAll,
    SwitchApp,
}

/// 发出操作的那条连接。多部手机端同时连着时，按它分开记录按住的鼠标键和进行中的缩放（ADR-0015）。
pub type Owner = u64;

#[derive(Debug, Clone, PartialEq)]
pub enum InputCommand {
    /// 指针的相对位移，单位为屏幕上的点。
    Move { dx: f32, dy: f32 },
    Click(MouseButton),
    DoubleClick(MouseButton),
    Press { owner: Owner, button: MouseButton },
    Release { owner: Owner, button: MouseButton },
    /// 双指滑动的距离，单位为像素，内容跟随手指移动。
    Scroll { dx: f32, dy: f32 },
    /// 双指捏合缩放开始，之后的缩放和结束都属于这一次。
    ZoomStart { owner: Owner },
    /// 两指距离的变化比例减 1：放大为正，缩小为负。
    Zoom { owner: Owner, magnification: f32 },
    ZoomEnd { owner: Owner },
    /// 在插入点输入文字。
    Text(String),
    Key(SpecialKey),
    Shortcut(Shortcut),
    /// 松开这条连接按住的鼠标键、结束它进行中的缩放，在连接断开时使用。
    ReleaseAll { owner: Owner },
}

/// 输入命令的去处。实际实现转给输入线程，测试里可以替换。
pub trait InputSink: Send + Sync + 'static {
    fn send(&self, command: InputCommand);
}

pub struct InputController {
    commands: mpsc::Sender<InputCommand>,
}

impl InputController {
    /// 启动输入线程。辅助功能权限的状态变化时调用 `on_permission`。
    pub fn spawn(on_permission: impl Fn(bool) + Send + 'static) -> Self {
        let (commands, receiver) = mpsc::channel();
        thread::Builder::new()
            .name("input".into())
            .spawn(move || InputWorker::new(on_permission).run(receiver))
            .expect("failed to spawn input thread");
        Self { commands }
    }
}

impl InputSink for InputController {
    fn send(&self, command: InputCommand) {
        // 输入线程只会随进程退出，发送失败时没有可做的事。
        let _ = self.commands.send(command);
    }
}

struct InputWorker<F> {
    device: Device<F>,
    pending_move: Fraction,
    pending_scroll: Fraction,
    /// 每条连接按住的鼠标键。同一个键被几条连接按着时，最后一条松开才真正松开。
    held: Vec<(Owner, MouseButton)>,
    /// 正在缩放的连接：已经发出缩放开始、还没有发出结束。
    zooming: Option<Owner>,
}

impl<F: Fn(bool)> InputWorker<F> {
    fn new(on_permission: F) -> Self {
        Self {
            device: Device {
                enigo: None,
                last_attempt: None,
                permission: None,
                on_permission,
            },
            pending_move: Fraction::default(),
            pending_scroll: Fraction::default(),
            held: Vec::new(),
            zooming: None,
        }
    }

    fn run(mut self, commands: mpsc::Receiver<InputCommand>) {
        // 启动时就检查一次权限，让界面尽早显示授权状态。
        self.device.get();
        loop {
            // 不等手机端发来操作，到时间就检查权限，授权或被关掉都会立即反映到界面上。
            match commands.recv_timeout(PERMISSION_CHECK_INTERVAL) {
                Ok(command) => {
                    if let Err(err) = self.handle(command) {
                        tracing::warn!("input command failed: {err}");
                    }
                }
                Err(RecvTimeoutError::Timeout) => self.device.refresh(),
                Err(RecvTimeoutError::Disconnected) => return,
            }
        }
    }

    fn handle(&mut self, command: InputCommand) -> enigo::InputResult<()> {
        tracing::trace!(?command, "input");
        match command {
            InputCommand::Move { dx, dy } => {
                let (x, y) = self.pending_move.take_whole(dx, dy);
                if (x, y) != (0, 0)
                    && let Some(enigo) = self.device.get()
                {
                    #[cfg(target_os = "macos")]
                    let (x, y) = displays::confine(enigo.location()?, (x, y));
                    enigo.move_mouse(x, y, Coordinate::Rel)?;
                }
            }
            InputCommand::Click(button) => {
                if let Some(enigo) = self.device.get() {
                    enigo.button(button.into(), Direction::Click)?;
                }
            }
            InputCommand::DoubleClick(button) => {
                // enigo 按两次点击的间隔判断双击，连续两次点击即为一次双击。
                if let Some(enigo) = self.device.get() {
                    enigo.button(button.into(), Direction::Click)?;
                    enigo.button(button.into(), Direction::Click)?;
                }
            }
            InputCommand::Press { owner, button } => {
                let pressed = self.is_held(button);
                if let Some(enigo) = self.device.get() {
                    if !pressed {
                        enigo.button(button.into(), Direction::Press)?;
                    }
                    if !self.held.contains(&(owner, button)) {
                        self.held.push((owner, button));
                    }
                }
            }
            InputCommand::Release { owner, button } => {
                self.held.retain(|held| *held != (owner, button));
                self.release_if_unheld(button)?;
            }
            InputCommand::Scroll { dx, dy } => {
                let (x, y) = self.pending_scroll.take_whole(dx, dy);
                if let Some(enigo) = self.device.get() {
                    // enigo 的正值表示内容向上、向左移动，与手指方向相反。
                    if y != 0 {
                        enigo.smooth_scroll(-y, Axis::Vertical)?;
                    }
                    if x != 0 {
                        enigo.smooth_scroll(-x, Axis::Horizontal)?;
                    }
                }
            }
            InputCommand::ZoomStart { owner } => {
                // 上一次缩放没收到结束（比如手机端的触控区被关掉了），先把它结束，保证每次缩放都有开始和结束。
                self.end_zoom();
                if self.device.get().is_some() {
                    post_zoom(ZoomPhase::Began, 0.0);
                    self.zooming = Some(owner);
                }
            }
            InputCommand::Zoom { owner, magnification } => {
                if self.zooming == Some(owner) {
                    post_zoom(ZoomPhase::Changed, magnification.into());
                }
            }
            InputCommand::ZoomEnd { owner } => {
                if self.zooming == Some(owner) {
                    self.end_zoom();
                }
            }
            InputCommand::Text(text) => {
                if let Some(enigo) = self.device.get() {
                    enigo.text(&text)?;
                }
            }
            InputCommand::Key(key) => {
                if let Some(enigo) = self.device.get() {
                    enigo.key(key.into(), Direction::Click)?;
                }
            }
            InputCommand::Shortcut(shortcut) => {
                if let Some(enigo) = self.device.get() {
                    let (modifiers, key) = shortcut.chord();
                    chord(enigo, &modifiers, key)?;
                }
            }
            InputCommand::ReleaseAll { owner } => {
                if self.zooming == Some(owner) {
                    self.end_zoom();
                }
                let (released, kept) = std::mem::take(&mut self.held).into_iter().partition(|(held_by, _)| *held_by == owner);
                self.held = kept;
                for (_, button) in released {
                    self.release_if_unheld(button)?;
                }
            }
        }
        Ok(())
    }

    fn is_held(&self, button: MouseButton) -> bool {
        self.held.iter().any(|(_, held)| *held == button)
    }

    /// 没有别的连接还按着这个键时，才真正松开它。
    fn release_if_unheld(&mut self, button: MouseButton) -> enigo::InputResult<()> {
        if !self.is_held(button)
            && let Some(enigo) = self.device.get()
        {
            enigo.button(button.into(), Direction::Release)?;
        }
        Ok(())
    }

    fn end_zoom(&mut self) {
        if self.zooming.take().is_some() {
            post_zoom(ZoomPhase::Ended, 0.0);
        }
    }
}

/// 按需创建 enigo，并在缺少辅助功能权限时定期重试。
struct Device<F> {
    enigo: Option<Enigo>,
    last_attempt: Option<Instant>,
    permission: Option<bool>,
    on_permission: F,
}

impl<F: Fn(bool)> Device<F> {
    fn refresh(&mut self) {
        if self.enigo.is_some() && !permission_granted() {
            // 使用中被关掉了辅助功能权限：放弃当前的 enigo，等用户重新授权后再创建。
            self.enigo = None;
            self.report(false);
        }
        self.get();
    }

    fn get(&mut self) -> Option<&mut Enigo> {
        let due = self
            .last_attempt
            .is_none_or(|attempt| attempt.elapsed() >= PERMISSION_CHECK_INTERVAL);
        if self.enigo.is_none() && due {
            // 只在第一次尝试时弹出系统授权提示，之后静默重试，等用户授权。
            let settings = Settings {
                open_prompt_to_get_permissions: self.last_attempt.is_none(),
                ..Settings::default()
            };
            self.last_attempt = Some(Instant::now());
            match Enigo::new(&settings) {
                Ok(enigo) => self.enigo = Some(enigo),
                Err(err) => tracing::warn!("input simulation unavailable: {err}"),
            }
            self.report(self.enigo.is_some());
        }
        self.enigo.as_mut()
    }

    fn report(&mut self, granted: bool) {
        if self.permission != Some(granted) {
            self.permission = Some(granted);
            (self.on_permission)(granted);
        }
    }
}

/// macOS 上模拟键鼠需要辅助功能权限，其他系统不需要。
fn permission_granted() -> bool {
    #[cfg(target_os = "macos")]
    return unsafe { objc2_application_services::AXIsProcessTrusted() };
    #[cfg(not(target_os = "macos"))]
    true
}

/// 累积不足一个单位的位移，避免慢速移动时被取整丢掉。
#[derive(Default)]
struct Fraction {
    x: f32,
    y: f32,
}

impl Fraction {
    fn take_whole(&mut self, dx: f32, dy: f32) -> (i32, i32) {
        self.x += dx;
        self.y += dy;
        let (x, y) = (self.x.trunc(), self.y.trunc());
        self.x -= x;
        self.y -= y;
        (x as i32, y as i32)
    }
}

/// macOS 不会替模拟的移动挡住屏幕边缘：移到屏幕外的位置会被系统原样记下，指针虽然停在边上，
/// 往回移动时却要先走完超出的距离。所以每次移动前先把目标限制在显示器上；其他系统自己会挡住。
#[cfg(target_os = "macos")]
mod displays {
    use objc2_core_foundation::CGRect;
    use objc2_core_graphics::{CGDisplayBounds, CGError, CGGetActiveDisplayList};

    /// 一块显示器在全局坐标中的范围，单位为点，不含右边和下边。
    #[derive(Debug, Clone, Copy)]
    struct Bounds {
        left: i32,
        top: i32,
        right: i32,
        bottom: i32,
    }

    impl Bounds {
        fn contains(&self, (x, y): (i32, i32)) -> bool {
            (self.left..self.right).contains(&x) && (self.top..self.bottom).contains(&y)
        }

        fn clamp(&self, (x, y): (i32, i32)) -> (i32, i32) {
            (x.clamp(self.left, self.right - 1), y.clamp(self.top, self.bottom - 1))
        }

        fn distance_squared(&self, point: (i32, i32)) -> i64 {
            let (x, y) = self.clamp(point);
            let (dx, dy) = (i64::from(point.0 - x), i64::from(point.1 - y));
            dx * dx + dy * dy
        }
    }

    impl From<CGRect> for Bounds {
        fn from(rect: CGRect) -> Self {
            let (min, max) = (rect.min(), rect.max());
            Self {
                left: min.x as i32,
                top: min.y as i32,
                right: max.x as i32,
                bottom: max.y as i32,
            }
        }
    }

    /// 把指针从 `from` 出发的位移限制在显示器上，返回限制后的位移。
    pub fn confine(from: (i32, i32), delta: (i32, i32)) -> (i32, i32) {
        confine_within(&active(), from, delta)
    }

    /// 目标落在任意一块显示器上就照常移动；否则贴着指针所在显示器的边移动。
    /// 指针本身已在所有显示器之外（比如刚拔掉显示器）时，先拉回最近的一块。
    fn confine_within(displays: &[Bounds], from: (i32, i32), (dx, dy): (i32, i32)) -> (i32, i32) {
        let target = (from.0 + dx, from.1 + dy);
        if displays.iter().any(|display| display.contains(target)) {
            return (dx, dy);
        }
        let Some(nearest) = displays.iter().min_by_key(|display| display.distance_squared(from)) else {
            return (dx, dy);
        };
        let (x, y) = nearest.clamp(target);
        (x - from.0, y - from.1)
    }

    /// 每次都重新读取，接上或拔掉显示器、改分辨率之后照常工作。
    fn active() -> Vec<Bounds> {
        const MAX_DISPLAYS: u32 = 32;
        let mut ids = [0; MAX_DISPLAYS as usize];
        let mut count = 0;
        let error = unsafe { CGGetActiveDisplayList(MAX_DISPLAYS, ids.as_mut_ptr(), &mut count) };
        if error != CGError::Success {
            tracing::warn!("failed to list displays: {error:?}");
            return Vec::new();
        }
        ids[..count as usize].iter().map(|&id| CGDisplayBounds(id).into()).collect()
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        /// 一块 1920×1080 的主显示器，右侧是一块竖放的显示器，两块的上下边缘不对齐。
        const DISPLAYS: [Bounds; 2] = [
            Bounds { left: 0, top: 0, right: 1920, bottom: 1080 },
            Bounds { left: 1920, top: -293, right: 3000, bottom: 1627 },
        ];

        #[test]
        fn movement_within_displays_is_unchanged() {
            assert_eq!(confine_within(&DISPLAYS, (500, 500), (30, -20)), (30, -20));
            // 跨到相邻的显示器上。
            assert_eq!(confine_within(&DISPLAYS, (1910, 100), (30, -150)), (30, -150));
        }

        #[test]
        fn movement_past_an_edge_stops_at_the_edge() {
            assert_eq!(confine_within(&DISPLAYS, (2990, 500), (40, 0)), (9, 0));
            // 沿着边缘滑动。
            assert_eq!(confine_within(&DISPLAYS, (100, 2), (10, -5)), (10, -2));
            // 两块显示器上下边缘不对齐的地方。
            assert_eq!(confine_within(&DISPLAYS, (1925, -200), (-10, 0)), (-5, 0));
        }

        #[test]
        fn pointer_outside_every_display_returns_to_the_nearest_one() {
            assert_eq!(confine_within(&DISPLAYS, (-300, 500), (10, 0)), (300, 0));
        }
    }
}

/// 缩放手势的阶段，取值与 macOS 的手势阶段一致。
#[derive(Debug, Clone, Copy)]
enum ZoomPhase {
    Began = 1,
    Changed = 2,
    Ended = 4,
}

/// 发出和触控板捏合一样的缩放手势，交给指针下的应用。macOS 没有公开接口能模拟手势，
/// 这里用的是未公开的手势事件字段（ADR-0012）。
#[cfg(target_os = "macos")]
fn post_zoom(phase: ZoomPhase, magnification: f64) {
    use objc2_core_graphics::{CGEvent, CGEventField, CGEventTapLocation, CGEventType};

    /// 手势事件。
    const GESTURE: CGEventType = CGEventType(29);
    /// 手势种类，8 是缩放。
    const GESTURE_HID_TYPE: CGEventField = CGEventField(110);
    const GESTURE_HID_TYPE_ZOOM: i64 = 8;
    const GESTURE_ZOOM_VALUE: CGEventField = CGEventField(113);
    const GESTURE_PHASE: CGEventField = CGEventField(132);

    let Some(event) = CGEvent::new(None) else {
        tracing::warn!("failed to create zoom event");
        return;
    };
    CGEvent::set_type(Some(&event), GESTURE);
    CGEvent::set_integer_value_field(Some(&event), GESTURE_HID_TYPE, GESTURE_HID_TYPE_ZOOM);
    CGEvent::set_integer_value_field(Some(&event), GESTURE_PHASE, phase as i64);
    CGEvent::set_double_value_field(Some(&event), GESTURE_ZOOM_VALUE, magnification);
    CGEvent::post(CGEventTapLocation::HIDEventTap, Some(&event));
}

/// Windows 版再做。
#[cfg(not(target_os = "macos"))]
fn post_zoom(_phase: ZoomPhase, _magnification: f64) {}

/// 按下修饰键、点一下主键，再按相反顺序松开修饰键；中途出错也会松开已按下的修饰键。
fn chord(enigo: &mut Enigo, modifiers: &[Key], key: Key) -> enigo::InputResult<()> {
    let mut pressed = Vec::new();
    let mut result = Ok(());
    for modifier in modifiers {
        if let Err(err) = enigo.key(*modifier, Direction::Press) {
            result = Err(err);
            break;
        }
        pressed.push(*modifier);
    }
    if result.is_ok() {
        result = enigo.key(key, Direction::Click);
    }
    for modifier in pressed.into_iter().rev() {
        let released = enigo.key(modifier, Direction::Release);
        if result.is_ok() {
            result = released;
        }
    }
    result
}

impl Shortcut {
    /// macOS 的组合键用 Command，其他系统用 Ctrl。
    fn chord(self) -> (Vec<Key>, Key) {
        #[cfg(target_os = "macos")]
        let (primary, redo, switch_app) = (
            Key::Meta,
            (vec![Key::Meta, Key::Shift], Key::Unicode('z')),
            (vec![Key::Meta], Key::Tab),
        );
        #[cfg(not(target_os = "macos"))]
        let (primary, redo, switch_app) = (
            Key::Control,
            (vec![Key::Control], Key::Unicode('y')),
            (vec![Key::Alt], Key::Tab),
        );
        match self {
            Self::Copy => (vec![primary], Key::Unicode('c')),
            Self::Cut => (vec![primary], Key::Unicode('x')),
            Self::Paste => (vec![primary], Key::Unicode('v')),
            Self::Undo => (vec![primary], Key::Unicode('z')),
            Self::SelectAll => (vec![primary], Key::Unicode('a')),
            Self::Redo => redo,
            Self::SwitchApp => switch_app,
        }
    }
}

impl From<SpecialKey> for Key {
    fn from(key: SpecialKey) -> Self {
        match key {
            SpecialKey::Enter => Key::Return,
            SpecialKey::Backspace => Key::Backspace,
            SpecialKey::Delete => Key::Delete,
            SpecialKey::Tab => Key::Tab,
            SpecialKey::Escape => Key::Escape,
            SpecialKey::Space => Key::Space,
            SpecialKey::Up => Key::UpArrow,
            SpecialKey::Down => Key::DownArrow,
            SpecialKey::Left => Key::LeftArrow,
            SpecialKey::Right => Key::RightArrow,
            SpecialKey::Home => Key::Home,
            SpecialKey::End => Key::End,
            SpecialKey::PageUp => Key::PageUp,
            SpecialKey::PageDown => Key::PageDown,
            SpecialKey::PlayPause => Key::MediaPlayPause,
            SpecialKey::NextTrack => Key::MediaNextTrack,
            SpecialKey::PreviousTrack => Key::MediaPrevTrack,
            SpecialKey::VolumeUp => Key::VolumeUp,
            SpecialKey::VolumeDown => Key::VolumeDown,
            SpecialKey::Mute => Key::VolumeMute,
        }
    }
}

impl From<MouseButton> for Button {
    fn from(button: MouseButton) -> Self {
        match button {
            MouseButton::Left => Button::Left,
            MouseButton::Right => Button::Right,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slow_movement_accumulates_into_whole_points() {
        let mut fraction = Fraction::default();
        assert_eq!(fraction.take_whole(0.4, -0.4), (0, 0));
        assert_eq!(fraction.take_whole(0.4, -0.4), (0, 0));
        assert_eq!(fraction.take_whole(0.4, -0.4), (1, -1));
        assert_eq!(fraction.take_whole(2.5, 0.0), (2, 0));
    }
}
