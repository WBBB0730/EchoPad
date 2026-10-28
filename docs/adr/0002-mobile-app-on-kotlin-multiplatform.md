# 手机端用 Kotlin Multiplatform，界面两端共用

手机端用 Kotlin Multiplatform，界面用 Compose Multiplatform 在 Android 和 iOS 之间共用。第一版只发布 Android，但工程从一开始就包含 iOS 目标，保证共享代码始终能在 iOS 上编译。手势识别、指针加速、消息协议这些最难调的部分都放在共享代码里，以后加 iOS 只需补录音等平台专属部分。

iOS 已评估过，没有阻断性的限制：本地网络权限、局域网明文连接、切到后台后断开、屏幕边缘系统手势冲突都有对应的处理方式。

## Alternatives Considered

- 各平台原生（Kotlin + Swift）：两端体验都最好，但最难调的手势和手感要调两遍。
- Flutter：一套代码覆盖两端，但录音等能力依赖社区插件。
- React Native：实时传输录音数据要另接原生模块，触感略逊。
