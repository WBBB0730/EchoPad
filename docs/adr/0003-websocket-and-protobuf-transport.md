# 手机端与电脑端用 WebSocket + Protobuf 通信，不用 socket.io

手机端在局域网内直连电脑端，走 WebSocket：手机端用 Ktor 客户端的 CIO 引擎，Android 和 iOS 共用一套连接代码，心跳设置在两个平台上都能生效；电脑端用 axum。消息用 Protobuf 定义，定义文件放在仓库里由两端共享，同时生成 Kotlin（Wire）和 Rust（prost）代码，避免两端消息格式不一致。

不用 socket.io 的原因：它在 Android 和 iOS 上的官方客户端都基本停更（Swift 版最近一次发布在 2024 年，Java 版在 2025 年 2 月），而且两个平台要各接一套，无法共用代码。

底层是 TCP，Wi-Fi 丢包时指针可能短暂卡顿。实测明显时，再为指针移动单独加一条 UDP 通道，其他消息仍走 WebSocket。

## Alternatives Considered

- socket.io：断线重连、心跳、送达确认都现成，因上述客户端停更问题被否。
- gRPC：类型安全，Rust 端成熟；但 Kotlin 官方 gRPC 不支持 iOS，对局域网直连也偏重。
