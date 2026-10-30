//! 在局域网里广播电脑端（docs/prd/pairing.md）：电脑的地址变了以后，手机端按电脑端标识找到它的新地址。
//! 找到的地址仍要通过证书校验（ADR-0014），冒充的广播拿不到设备密钥。

use std::collections::HashMap;

use anyhow::Result;
use mdns_sd::{DaemonEvent, IfKind, ServiceDaemon, ServiceInfo};

/// 手机端按这个服务类型查找电脑端。
const SERVICE_TYPE: &str = "_echopad._tcp.local.";

/// 手机端按附加信息里的这一项找到电脑端标识。
const DESKTOP_ID_KEY: &str = "id";

/// 多久检查一次本机地址。电脑换了地址后，手机端要等到新地址广播出去才能连上，所以检查得勤一些（默认 5 秒）。
const ADDRESS_CHECK_INTERVAL_SECS: u32 = 1;

/// 开始广播。服务实例名是电脑名，电脑端标识放在附加信息里；地址由 mdns-sd 跟随本机网络自动更新，
/// 新地址会立即广播出去，并让手机端丢掉旧地址。只在局域网接口上广播 IPv4 地址，因为连接服务只监听 IPv4。
pub fn advertise(desktop_name: &str, desktop_id: &str, port: u16) -> Result<ServiceDaemon> {
    let daemon = ServiceDaemon::new()?;
    daemon.disable_interface(vec![IfKind::IPv6, IfKind::LoopbackV4])?;
    daemon.set_ip_check_interval(ADDRESS_CHECK_INTERVAL_SECS)?;
    let host_name = format!("echopad-{desktop_id}.local.");
    let properties = HashMap::from([(DESKTOP_ID_KEY.to_owned(), desktop_id.to_owned())]);
    let service = ServiceInfo::new(SERVICE_TYPE, desktop_name, &host_name, "", port, properties)?.enable_addr_auto();
    daemon.register(service)?;
    Ok(daemon)
}

/// 本机的地址增减时调用 `on_change`，例如电脑换了 Wi-Fi 或重新分配了 IP。
pub async fn watch_addresses(daemon: &ServiceDaemon, on_change: impl Fn()) -> Result<()> {
    let events = daemon.monitor()?;
    while let Ok(event) = events.recv_async().await {
        if matches!(event, DaemonEvent::IpAdd(_) | DaemonEvent::IpDel(_)) {
            on_change();
        }
    }
    Ok(())
}
