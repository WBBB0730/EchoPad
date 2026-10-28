//! 电脑端界面显示的状态：配对码、已连接的手机端、权限与语音输入是否可用。

use std::sync::Mutex;

use qrcode::{QrCode, render::svg};
use serde::Serialize;

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub pairing_link: String,
    pub pairing_qr_svg: String,
    pub connected_devices: Vec<ConnectedDevice>,
    /// 尚未检查时为 `None`。
    pub input_permission: Option<bool>,
    pub dictation_ready: bool,
}

/// 一个已连接的手机端。`id` 区分每一次连接，电脑端断开连接时用它指定是哪一个。
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectedDevice {
    pub id: u64,
    pub name: String,
}

pub struct StatusHub {
    status: Mutex<Status>,
    notify: Box<dyn Fn(&Status) + Send + Sync>,
}

impl StatusHub {
    pub fn new(
        pairing_link: String,
        dictation_ready: bool,
        notify: impl Fn(&Status) + Send + Sync + 'static,
    ) -> Self {
        tracing::debug!(link = %pairing_link, "pairing link");
        let pairing_qr_svg = qr_svg(&pairing_link);
        Self {
            status: Mutex::new(Status {
                pairing_link,
                pairing_qr_svg,
                connected_devices: Vec::new(),
                input_permission: None,
                dictation_ready,
            }),
            notify: Box::new(notify),
        }
    }

    pub fn snapshot(&self) -> Status {
        self.status.lock().unwrap().clone()
    }

    pub fn set_pairing_link(&self, link: String) {
        tracing::debug!(%link, "pairing link changed");
        self.update(|status| {
            status.pairing_qr_svg = qr_svg(&link);
            status.pairing_link = link;
        });
    }

    pub fn device_connected(&self, id: u64, name: &str) {
        self.update(|status| status.connected_devices.push(ConnectedDevice { id, name: name.to_owned() }));
    }

    pub fn device_disconnected(&self, id: u64) {
        self.update(|status| status.connected_devices.retain(|device| device.id != id));
    }

    pub fn set_input_permission(&self, granted: bool) {
        self.update(|status| status.input_permission = Some(granted));
    }

    pub fn set_dictation_ready(&self, ready: bool) {
        self.update(|status| status.dictation_ready = ready);
    }

    fn update(&self, change: impl FnOnce(&mut Status)) {
        let snapshot = {
            let mut status = self.status.lock().unwrap();
            change(&mut status);
            status.clone()
        };
        (self.notify)(&snapshot);
    }
}

fn qr_svg(link: &str) -> String {
    QrCode::new(link.as_bytes())
        .expect("pairing link always fits in a QR code")
        .render::<svg::Color>()
        .min_dimensions(240, 240)
        .quiet_zone(true)
        .build()
}
