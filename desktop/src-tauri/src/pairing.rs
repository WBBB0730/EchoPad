//! 配对：配对码里的一次性口令，以及已配对手机端的设备密钥（docs/prd/pairing.md）。

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
    Paired { device_key: String },
    /// 已配对的手机端重新连接。
    Recognized,
    Rejected,
}

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
    devices: Vec<PairedDevice>,
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
            devices,
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
                self.devices.push(PairedDevice {
                    name: device_name.to_owned(),
                    key_hash: hash(&device_key),
                    paired_at: unix_now(),
                });
                self.save()?;
                // 配对码只能用一次：配对成功后立即换一个新口令。
                self.pairing_token = random_secret();
                Ok(AuthOutcome::Paired { device_key })
            }
            Credential::PairingToken(_) => Ok(AuthOutcome::Rejected),
            Credential::DeviceKey(key) => {
                let key_hash = hash(key);
                Ok(if self.devices.iter().any(|device| device.key_hash == key_hash) {
                    AuthOutcome::Recognized
                } else {
                    AuthOutcome::Rejected
                })
            }
        }
    }

    fn save(&self) -> Result<()> {
        if let Some(dir) = self.path.parent() {
            fs::create_dir_all(dir)?;
        }
        let json = serde_json::to_vec_pretty(&StoredDevices {
            devices: self.devices.clone(),
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
        let AuthOutcome::Paired { device_key } =
            store.authenticate("Pixel", Credential::PairingToken(&token)).unwrap()
        else {
            panic!("expected pairing to succeed");
        };

        let mut reloaded = PairingStore::load(path).unwrap();
        let outcome = reloaded.authenticate("Pixel", Credential::DeviceKey(&device_key)).unwrap();
        assert_eq!(outcome, AuthOutcome::Recognized);
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
