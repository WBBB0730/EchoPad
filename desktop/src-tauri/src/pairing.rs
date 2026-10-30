//! 配对：配对码里的一次性口令，以及已配对手机端的设备密钥（docs/prd/pairing.md）。
//! 一台电脑端可以配多部手机端（ADR-0015），同一部手机端再次扫码时替换它原来的配对。

use std::{
    fs, io,
    net::IpAddr,
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};

use anyhow::{Context, Result};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub enum Credential<'a> {
    PairingToken(&'a str),
    DeviceKey(&'a str),
}

#[derive(Debug, PartialEq, Eq)]
pub enum AuthOutcome {
    /// 用配对口令完成了首次配对，新发放的设备密钥要交给手机端保存。
    Paired { device_key: String, device: DeviceId },
    /// 已配对的手机端重新连接。
    Recognized(DeviceId),
    Rejected,
}

/// 一部已配对的手机端，用它的设备密钥的哈希区分。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeviceId(String);

/// 电脑端界面上列出的已配对手机端。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PairedPhone {
    /// 手机端的固定标识。
    pub id: String,
    pub name: String,
    pub device: DeviceId,
}

#[derive(Clone, Serialize, Deserialize)]
struct PairedDevice {
    /// 手机端的固定标识。以前的版本没有保存，那时配对的手机端用不了加密连接（ADR-0014），读取时直接丢掉。
    #[serde(default)]
    phone_id: String,
    name: String,
    /// 只保存设备密钥的哈希，密钥本身只存在手机端。
    key_hash: String,
    paired_at: u64,
}

#[derive(Default, Serialize, Deserialize)]
struct StoredPairing {
    /// 电脑端的固定标识，以前的版本没有保存，第一次启动时生成。
    #[serde(default)]
    desktop_id: Option<String>,
    devices: Vec<PairedDevice>,
}

pub struct PairingStore {
    path: PathBuf,
    desktop_id: String,
    devices: Vec<PairedDevice>,
    pairing_token: String,
}

impl PairingStore {
    pub fn load(path: PathBuf) -> Result<Self> {
        let stored = match fs::read(&path) {
            Ok(bytes) => serde_json::from_slice::<StoredPairing>(&bytes)
                .with_context(|| format!("failed to parse {}", path.display()))?,
            Err(err) if err.kind() == io::ErrorKind::NotFound => StoredPairing::default(),
            Err(err) => {
                return Err(err).with_context(|| format!("failed to read {}", path.display()));
            }
        };
        let count = stored.devices.len();
        let devices: Vec<_> = stored.devices.into_iter().filter(|device| !device.phone_id.is_empty()).collect();
        let changed = stored.desktop_id.is_none() || devices.len() != count;
        let store = Self {
            path,
            desktop_id: stored.desktop_id.unwrap_or_else(new_id),
            devices,
            pairing_token: random_secret(),
        };
        if changed {
            store.save()?;
        }
        Ok(store)
    }

    pub fn pairing_token(&self) -> &str {
        &self.pairing_token
    }

    pub fn desktop_id(&self) -> &str {
        &self.desktop_id
    }

    /// `phone_id` 是手机端的固定标识，同一部手机端再次扫码时据此替换它原来的配对。
    pub fn authenticate(&mut self, phone_id: &str, device_name: &str, credential: Credential) -> Result<AuthOutcome> {
        match credential {
            Credential::PairingToken(token) if token == self.pairing_token => {
                let device_key = random_secret();
                let key_hash = hash(&device_key);
                let phone_id = if phone_id.is_empty() { new_id() } else { phone_id.to_owned() };
                self.devices.retain(|device| device.phone_id != phone_id);
                self.devices.push(PairedDevice {
                    phone_id,
                    name: device_name.to_owned(),
                    key_hash: key_hash.clone(),
                    paired_at: unix_now(),
                });
                self.save()?;
                // 配对码只能用一次：配对成功后立即换一个新口令。
                self.pairing_token = random_secret();
                Ok(AuthOutcome::Paired { device_key, device: DeviceId(key_hash) })
            }
            Credential::PairingToken(_) => Ok(AuthOutcome::Rejected),
            Credential::DeviceKey(key) => {
                let key_hash = hash(key);
                let Some(device) = self.devices.iter_mut().find(|device| device.key_hash == key_hash) else {
                    return Ok(AuthOutcome::Rejected);
                };
                // 用户在手机上改了设备名时，电脑端显示的名字跟着更新。
                if device.name != device_name {
                    device.name = device_name.to_owned();
                    self.save()?;
                }
                Ok(AuthOutcome::Recognized(DeviceId(key_hash)))
            }
        }
    }

    pub fn phones(&self) -> Vec<PairedPhone> {
        self.devices
            .iter()
            .map(|device| PairedPhone {
                id: device.phone_id.clone(),
                name: device.name.clone(),
                device: DeviceId(device.key_hash.clone()),
            })
            .collect()
    }

    /// 取消一部手机端的配对，作废它的设备密钥；返回它的设备，用来关闭它的连接。
    pub fn forget(&mut self, phone_id: &str) -> Result<Option<DeviceId>> {
        let Some(index) = self.devices.iter().position(|device| device.phone_id == phone_id) else {
            return Ok(None);
        };
        let device = self.devices.remove(index);
        self.save()?;
        Ok(Some(DeviceId(device.key_hash)))
    }

    fn save(&self) -> Result<()> {
        if let Some(dir) = self.path.parent() {
            fs::create_dir_all(dir)?;
        }
        let json = serde_json::to_vec_pretty(&StoredPairing {
            desktop_id: Some(self.desktop_id.clone()),
            devices: self.devices.clone(),
        })?;
        fs::write(&self.path, json).with_context(|| format!("failed to write {}", self.path.display()))
    }
}

/// 配对码里的链接，手机端扫码后据此连接电脑端，并记下电脑端标识和证书指纹（ADR-0014）。
pub fn pairing_link(host: IpAddr, port: u16, token: &str, desktop_id: &str, fingerprint: &str) -> String {
    format!("echopad://pair?host={host}&port={port}&token={token}&id={desktop_id}&fp={fingerprint}")
}

fn new_id() -> String {
    uuid::Uuid::new_v4().simple().to_string()
}

fn random_secret() -> String {
    let mut bytes = [0u8; 32];
    rand::fill(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

fn hash(secret: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(secret.as_bytes()))
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |elapsed| elapsed.as_secs())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_store_path() -> PathBuf {
        std::env::temp_dir()
            .join(format!("echopad-pairing-{}", uuid::Uuid::new_v4()))
            .join("paired-devices.json")
    }

    /// 用当前的配对口令配对一部手机端，返回发放的设备密钥。
    fn pair(store: &mut PairingStore, phone_id: &str, name: &str) -> (String, DeviceId) {
        let token = store.pairing_token().to_owned();
        let AuthOutcome::Paired { device_key, device } =
            store.authenticate(phone_id, name, Credential::PairingToken(&token)).unwrap()
        else {
            panic!("expected pairing to succeed");
        };
        (device_key, device)
    }

    #[test]
    fn pairing_token_works_only_once() {
        let mut store = PairingStore::load(temp_store_path()).unwrap();
        let token = store.pairing_token().to_owned();

        let outcome = store.authenticate("phone-1", "Pixel", Credential::PairingToken(&token)).unwrap();
        assert!(matches!(outcome, AuthOutcome::Paired { .. }));
        assert_ne!(store.pairing_token(), token);

        let reused = store.authenticate("phone-2", "Other", Credential::PairingToken(&token)).unwrap();
        assert_eq!(reused, AuthOutcome::Rejected);
    }

    #[test]
    fn several_phones_stay_paired_after_reload() {
        let path = temp_store_path();
        let mut store = PairingStore::load(path.clone()).unwrap();
        let (pixel_key, pixel) = pair(&mut store, "phone-1", "Pixel");
        let (tablet_key, tablet) = pair(&mut store, "phone-2", "Tablet");

        let mut reloaded = PairingStore::load(path).unwrap();
        assert_eq!(
            reloaded.authenticate("phone-1", "Pixel", Credential::DeviceKey(&pixel_key)).unwrap(),
            AuthOutcome::Recognized(pixel)
        );
        assert_eq!(
            reloaded.authenticate("phone-2", "Tablet", Credential::DeviceKey(&tablet_key)).unwrap(),
            AuthOutcome::Recognized(tablet)
        );
        let names: Vec<_> = reloaded.phones().into_iter().map(|phone| phone.name).collect();
        assert_eq!(names, ["Pixel", "Tablet"]);
    }

    #[test]
    fn pairing_the_same_phone_again_replaces_its_old_key() {
        let mut store = PairingStore::load(temp_store_path()).unwrap();
        let (old_key, _) = pair(&mut store, "phone-1", "Pixel");
        let (new_key, _) = pair(&mut store, "phone-1", "Pixel");

        assert_eq!(
            store.authenticate("phone-1", "Pixel", Credential::DeviceKey(&old_key)).unwrap(),
            AuthOutcome::Rejected
        );
        assert!(matches!(
            store.authenticate("phone-1", "Pixel", Credential::DeviceKey(&new_key)).unwrap(),
            AuthOutcome::Recognized(_)
        ));
        assert_eq!(store.phones().len(), 1);
    }

    #[test]
    fn a_renamed_phone_shows_its_new_name() {
        let mut store = PairingStore::load(temp_store_path()).unwrap();
        let (key, _) = pair(&mut store, "phone-1", "Pixel");

        store.authenticate("phone-1", "Pixel 10", Credential::DeviceKey(&key)).unwrap();

        assert_eq!(store.phones()[0].name, "Pixel 10");
    }

    #[test]
    fn forgotten_phones_are_rejected_after_reload() {
        let path = temp_store_path();
        let mut store = PairingStore::load(path.clone()).unwrap();
        let (key, device) = pair(&mut store, "phone-1", "Pixel");

        assert_eq!(store.forget("phone-1").unwrap(), Some(device));
        assert_eq!(store.forget("phone-1").unwrap(), None);
        let mut reloaded = PairingStore::load(path).unwrap();
        assert_eq!(
            reloaded.authenticate("phone-1", "Pixel", Credential::DeviceKey(&key)).unwrap(),
            AuthOutcome::Rejected
        );
    }

    #[test]
    fn phones_paired_by_older_versions_are_dropped() {
        let path = temp_store_path();
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        let json = format!(r#"{{"devices":[{{"name":"Pixel","key_hash":"{}","paired_at":1}}]}}"#, hash("old-key"));
        fs::write(&path, json).unwrap();

        let mut store = PairingStore::load(path.clone()).unwrap();
        assert!(store.phones().is_empty());
        assert_eq!(
            store.authenticate("", "Pixel", Credential::DeviceKey("old-key")).unwrap(),
            AuthOutcome::Rejected
        );
        assert!(PairingStore::load(path).unwrap().phones().is_empty());
    }

    #[test]
    fn the_desktop_id_is_generated_once_and_kept() {
        let path = temp_store_path();
        let id = PairingStore::load(path.clone()).unwrap().desktop_id().to_owned();

        assert!(!id.is_empty());
        assert_eq!(PairingStore::load(path).unwrap().desktop_id(), id);
    }

    #[test]
    fn unknown_credentials_are_rejected() {
        let mut store = PairingStore::load(temp_store_path()).unwrap();
        assert_eq!(
            store.authenticate("phone-1", "Pixel", Credential::PairingToken("guess")).unwrap(),
            AuthOutcome::Rejected
        );
        assert_eq!(
            store.authenticate("phone-1", "Pixel", Credential::DeviceKey("guess")).unwrap(),
            AuthOutcome::Rejected
        );
    }
}
