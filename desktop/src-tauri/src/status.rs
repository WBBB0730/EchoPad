//! 电脑端界面显示的状态：配对码、配过的手机端及是否在线、版本是否兼容、权限与语音输入是否可用。

use std::sync::Mutex;

use qrcode::{QrCode, render::svg};
use serde::Serialize;

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub pairing_link: String,
    pub pairing_qr_svg: String,
    pub phones: Vec<PhoneStatus>,
    /// 最近一次因版本不兼容拒绝连接时需要更新的一端；有手机端连上后清除（ADR-0010）。
    pub outdated: Option<OutdatedApp>,
    /// 尚未检查时为 `None`。
    pub input_permission: Option<bool>,
    pub dictation_ready: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum OutdatedApp {
    Mobile,
    Desktop,
}

/// 一部配过的手机端。`id` 是手机端的固定标识，取消配对、让它切换过来时用它指定是哪一部。
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PhoneStatus {
    pub id: String,
    pub name: String,
    pub online: bool,
}

pub struct StatusHub {
    status: Mutex<Status>,
    notify: Box<dyn Fn(&Status) + Send + Sync>,
}

impl StatusHub {
    /// 配对码和配过的手机端由连接服务在启动时发布。
    pub fn new(notify: impl Fn(&Status) + Send + Sync + 'static) -> Self {
        Self {
            status: Mutex::new(Status {
                pairing_link: String::new(),
                pairing_qr_svg: String::new(),
                phones: Vec::new(),
                outdated: None,
                input_permission: None,
                dictation_ready: false,
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

    pub fn set_phones(&self, phones: Vec<PhoneStatus>) {
        self.update(|status| status.phones = phones);
    }

    pub fn set_outdated(&self, app: Option<OutdatedApp>) {
        self.update(|status| status.outdated = app);
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
