//! 在电脑上模拟键鼠（ADR-0001、docs/prd/touchpad.md）。enigo 只在专用线程里使用。

use std::{
    sync::mpsc,
    thread,
    time::{Duration, Instant},
};

use enigo::{Axis, Button, Coordinate, Direction, Enigo, Key, Keyboard, Mouse, Settings};

/// 缺少辅助功能权限时，每隔这么久重新检查一次，用户授权后无需重启电脑端。
const PERMISSION_RETRY_INTERVAL: Duration = Duration::from_secs(1);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MouseButton {
    Left,
    Right,
}

/// 特殊键：作用于电脑的单个功能键（docs/prd/typing.md）。
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

#[derive(Debug, Clone, PartialEq)]
pub enum InputCommand {
    /// 指针的相对位移，单位为屏幕上的点。
    Move { dx: f32, dy: f32 },
    Click(MouseButton),
    DoubleClick(MouseButton),
    Press(MouseButton),
    Release(MouseButton),
    /// 双指滑动的距离，单位为像素，内容跟随手指移动。
    Scroll { dx: f32, dy: f32 },
    /// 在插入点输入文字。
    Text(String),
    Key(SpecialKey),
    Shortcut(Shortcut),
    /// 松开所有被按住的鼠标键，在连接断开时使用。
    ReleaseAll,
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
    held: Vec<MouseButton>,
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
        }
    }

    fn run(mut self, commands: mpsc::Receiver<InputCommand>) {
        // 启动时就检查一次权限，让界面尽早显示授权状态。
        self.device.get();
        for command in commands {
            if let Err(err) = self.handle(command) {
                tracing::warn!("input command failed: {err}");
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
            InputCommand::Press(button) => {
                if let Some(enigo) = self.device.get() {
                    enigo.button(button.into(), Direction::Press)?;
                    if !self.held.contains(&button) {
                        self.held.push(button);
                    }
                }
            }
            InputCommand::Release(button) => {
                self.held.retain(|held| *held != button);
                if let Some(enigo) = self.device.get() {
                    enigo.button(button.into(), Direction::Release)?;
                }
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
            InputCommand::ReleaseAll => {
                let held = std::mem::take(&mut self.held);
                if let Some(enigo) = self.device.get() {
                    for button in held {
                        enigo.button(button.into(), Direction::Release)?;
                    }
                }
            }
        }
        Ok(())
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
    fn get(&mut self) -> Option<&mut Enigo> {
        let due = self
            .last_attempt
            .is_none_or(|attempt| attempt.elapsed() >= PERMISSION_RETRY_INTERVAL);
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
            let granted = self.enigo.is_some();
            if self.permission != Some(granted) {
                self.permission = Some(granted);
                (self.on_permission)(granted);
            }
        }
        self.enigo.as_mut()
    }
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
