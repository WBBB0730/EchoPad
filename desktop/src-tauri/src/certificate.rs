//! 电脑端的证书（ADR-0014）：第一次启动时生成，之后一直使用。配对码带上它的指纹，
//! 手机端只连接证书指纹一致的电脑端。

use std::{fs, io, path::Path, sync::Arc};

use anyhow::{Context, Result};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use sha2::{Digest, Sha256};
use tokio_rustls::rustls::{
    ServerConfig,
    crypto::aws_lc_rs,
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer},
};

use crate::settings::write_private;

/// 证书里的名字，手机端连接时用它做 TLS 的服务器名。电脑的 IP 地址会变，所以证书里不放 IP。
pub const CERTIFICATE_NAME: &str = "echopad";

pub struct DesktopCertificate {
    certificate: CertificateDer<'static>,
    private_key: PrivatePkcs8KeyDer<'static>,
}

impl DesktopCertificate {
    /// 读取保存的证书；还没有时生成一张自签名证书（ECDSA P-256）并保存，私钥只有当前用户可以读写。
    pub fn load_or_create(dir: &Path) -> Result<Self> {
        let certificate_path = dir.join("certificate.der");
        let private_key_path = dir.join("private-key.der");
        match (fs::read(&certificate_path), fs::read(&private_key_path)) {
            (Ok(certificate), Ok(private_key)) => Ok(Self {
                certificate: certificate.into(),
                private_key: private_key.into(),
            }),
            (Err(err), _) | (_, Err(err)) if err.kind() != io::ErrorKind::NotFound => {
                Err(err).with_context(|| format!("failed to read the certificate in {}", dir.display()))
            }
            _ => {
                let generated = rcgen::generate_simple_self_signed(vec![CERTIFICATE_NAME.to_owned()])?;
                fs::create_dir_all(dir)?;
                write_private(&private_key_path, &generated.signing_key.serialize_der())?;
                fs::write(&certificate_path, generated.cert.der())?;
                Ok(Self {
                    certificate: generated.cert.der().clone(),
                    private_key: generated.signing_key.serialize_der().into(),
                })
            }
        }
    }

    /// 证书的 SHA-256 摘要，放进配对码。
    pub fn fingerprint(&self) -> String {
        URL_SAFE_NO_PAD.encode(Sha256::digest(&self.certificate))
    }

    pub fn server_config(&self) -> Result<Arc<ServerConfig>> {
        let config = ServerConfig::builder_with_provider(Arc::new(aws_lc_rs::default_provider()))
            .with_safe_default_protocol_versions()?
            .with_no_client_auth()
            .with_single_cert(vec![self.certificate.clone()], PrivateKeyDer::Pkcs8(self.private_key.clone_key()))?;
        Ok(Arc::new(config))
    }

    #[cfg(test)]
    pub fn der(&self) -> &CertificateDer<'static> {
        &self.certificate
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_certificate_is_created_once_and_its_private_key_is_private() {
        let dir = std::env::temp_dir().join(format!("echopad-certificate-{}", uuid::Uuid::new_v4()));
        let fingerprint = DesktopCertificate::load_or_create(&dir).unwrap().fingerprint();

        assert_eq!(DesktopCertificate::load_or_create(&dir).unwrap().fingerprint(), fingerprint);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(dir.join("private-key.der")).unwrap().permissions().mode();
            assert_eq!(mode & 0o777, 0o600);
        }
    }
}
