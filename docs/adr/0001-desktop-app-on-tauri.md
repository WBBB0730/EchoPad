# 电脑端用 Tauri（Rust）

电脑端用 Tauri 2：通信、模拟键鼠、语音转发等核心逻辑用 Rust 写，设置和配对界面用 React + TypeScript。原因是模拟键鼠和系统权限处理都偏原生，Rust 生态有维护良好的跨平台输入模拟库（enigo），平滑滚动这类细节也能直接调用系统接口；同一套代码以后能扩展到 Windows。

## Alternatives Considered

- Electron：全用 TypeScript，但 Node 生态缺少维护良好的键鼠模拟库（robotjs 已基本停更），大概率要自己写原生扩展，安装包也大得多。
- Swift 原生：和 Mac 结合最紧，但无法扩展到 Windows。
