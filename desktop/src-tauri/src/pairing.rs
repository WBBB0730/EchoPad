//! 配对：配对码里的一次性口令，以及已配对手机端的设备密钥（docs/prd/pairing.md）。
//! 一台电脑端只与一部手机端配对：新手机端配对后，原来那部的设备密钥作废。

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

/// 一台已配对的手机端，用它的设备密钥的哈希区分。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeviceId(String);

#[derive(Clone, Serialize, Deserialize)]
struct PairedDevice {
    name: String,
    /// 只保存设备密钥的哈希，密钥本身只存在手机端。
    key_hash: String,
    paired_at: u64,
}

#[derive(Default, Serialize, Deserialize)]
struct StoredDevices {
    devices: Vec<PairedDevice>,
}

pub struct PairingStore {
    path: PathBuf,
    device: Option<PairedDevice>,
    pairing_token: String,
}

impl PairingStore {
    pub fn load(path: PathBuf) -> Result<Self> {
        let devices = match fs::read(&path) {
            Ok(bytes) => {
                serde_json::from_slice::<StoredDevices>(&bytes)
                    .with_context(|| format!("failed to parse {}", path.display()))?
                    .devices
            }
            Err(err) if err.kind() == io::ErrorKind::NotFound => Vec::new(),
            Err(err) => {
                return Err(err).with_context(|| format!("failed to read {}", path.display()));
            }
        };
        Ok(Self {
            path,
            // 以前的版本允许配对多部手机端，只保留最近配对的那一部。
            device: devices.into_iter().max_by_key(|device| device.paired_at),
            pairing_token: random_secret(),
        })
    }

    pub fn pairing_token(&self) -> &str {
        &self.pairing_token
    }

    pub fn authenticate(&mut self, device_name: &str, credential: Credential) -> Result<AuthOutcome> {
        match credential {
            Credential::PairingToken(token) if token == self.pairing_token => {
                let device_key = random_secret();
                let key_hash = hash(&device_key);
                self.device = Some(PairedDevice {
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
                Ok(if self.device.as_ref().is_some_and(|device| device.key_hash == key_hash) {
                    AuthOutcome::Recognized(DeviceId(key_hash))
                } else {
                    AuthOutcome::Rejected
                })
            }
        }
    }

    /// 作废这部手机端的设备密钥，它之后要重新扫码配对。
    pub fn forget(&mut self, device: &DeviceId) -> Result<()> {
        if self.device.as_ref().is_some_and(|paired| paired.key_hash == device.0) {
            self.device = None;
        }
        self.save()
    }

    fn save(&self) -> Result<()> {
        if let Some(dir) = self.path.parent() {
            fs::create_dir_all(dir)?;
        }
        let json = serde_json::to_vec_pretty(&StoredDevices {
            devices: self.device.iter().cloned().collect(),
        })?;
        fs::write(&self.path, json).with_context(|| format!("failed to write {}", self.path.display()))
    }
}

/// 配对码里的链接，手机端扫码后据此连接电脑端。
pub fn pairing_link(host: IpAddr, port: u16, token: &str) -> String {
    format!("echopad://pair?host={host}&port={port}&token={token}")
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

    #[test]
    fn pairing_token_works_only_once() {
        let mut store = PairingStore::load(temp_store_path()).unwrap();
        let token = store.pairing_token().to_owned();

        let outcome = store.authenticate("Pixel", Credential::PairingToken(&token)).unwrap();
        assert!(matches!(outcome, AuthOutcome::Paired { .. }));
        assert_ne!(store.pairing_token(), token);

        let reused = store.authenticate("Other", Credential::PairingToken(&token)).unwrap();
        assert_eq!(reused, AuthOutcome::Rejected);
    }

    #[test]
    fn issued_device_key_is_recognized_after_reload() {
        let path = temp_store_path();
        let mut store = PairingStore::load(path.clone()).unwrap();
        let token = store.pairing_token().to_owned();
        let AuthOutcome::Paired { device_key, device } =
            store.authenticate("Pixel", Credential::PairingToken(&token)).unwrap()
        else {
            panic!("expected pairing to succeed");
        };

        let mut reloaded = PairingStore::load(path).unwrap();
        let outcome = reloaded.authenticate("Pixel", Credential::DeviceKey(&device_key)).unwrap();
        assert_eq!(outcome, AuthOutcome::Recognized(device));
    }

    #[test]
    fn pairing_a_new_phone_revokes_the_previous_one() {
        let path = temp_store_path();
        let mut store = PairingStore::load(path.clone()).unwrap();
        let first_token = store.pairing_token().to_owned();
        let AuthOutcome::Paired { device_key: first_key, .. } =
            store.authenticate("Pixel", Credential::PairingToken(&first_token)).unwrap()
        else {
            panic!("expected pairing to succeed");
        };
        let second_token = store.pairing_token().to_owned();
        let AuthOutcome::Paired { device_key: second_key, device: second } =
            store.authenticate("iPhone", Credential::PairingToken(&second_token)).unwrap()
        else {
            panic!("expected pairing to succeed");
        };

        let mut reloaded = PairingStore::load(path).unwrap();
        assert_eq!(reloaded.authenticate("Pixel", Credential::DeviceKey(&first_key)).unwrap(), AuthOutcome::Rejected);
        assert_eq!(
            reloaded.authenticate("iPhone", Credential::DeviceKey(&second_key)).unwrap(),
            AuthOutcome::Recognized(second)
        );
    }

    #[test]
    fn only_the_latest_phone_from_older_versions_stays_paired() {
        let path = temp_store_path();
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        let stored = StoredDevices {
            devices: vec![
                PairedDevice { name: "Newer".into(), key_hash: hash("newer-key"), paired_at: 2 },
                PairedDevice { name: "Older".into(), key_hash: hash("older-key"), paired_at: 1 },
            ],
        };
        fs::write(&path, serde_json::to_vec(&stored).unwrap()).unwrap();

        let mut store = PairingStore::load(path).unwrap();
        assert_eq!(store.authenticate("Older", Credential::DeviceKey("older-key")).unwrap(), AuthOutcome::Rejected);
        assert!(matches!(
            store.authenticate("Newer", Credential::DeviceKey("newer-key")).unwrap(),
            AuthOutcome::Recognized(_)
        ));
    }

    #[test]
    fn forgotten_devices_are_rejected_after_reload() {
        let path = temp_store_path();
        let mut store = PairingStore::load(path.clone()).unwrap();
        let token = store.pairing_token().to_owned();
        let AuthOutcome::Paired { device_key, device } =
            store.authenticate("Pixel", Credential::PairingToken(&token)).unwrap()
        else {
            panic!("expected pairing to succeed");
        };

        store.forget(&device).unwrap();
        let mut reloaded = PairingStore::load(path).unwrap();
        assert_eq!(
            reloaded.authenticate("Pixel", Credential::DeviceKey(&device_key)).unwrap(),
            AuthOutcome::Rejected
        );
    }

    #[test]
    fn unknown_credentials_are_rejected() {
        let mut store = PairingStore::load(temp_store_path()).unwrap();
        assert_eq!(
            store.authenticate("Pixel", Credential::PairingToken("guess")).unwrap(),
            AuthOutcome::Rejected
        );
        assert_eq!(
            store.authenticate("Pixel", Credential::DeviceKey("guess")).unwrap(),
            AuthOutcome::Rejected
        );
    }
}
