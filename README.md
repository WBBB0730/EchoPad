# EchoPad

把手机变成电脑的触控板，并支持语音输入。手机和电脑在同一局域网内直连。

术语见 `CONTEXT.md`，关键决策见 `docs/adr/`，各功能需求见 `docs/prd/`，界面约定见 `DESIGN.md`。

## 仓库结构

一个 Git 仓库，内含多个独立子项目，各自使用原生构建工具，不使用 pnpm workspace、Turborepo 这类 monorepo 工具（各子项目语言不同，没有可共享的 JS 代码）。

| 目录 | 内容 | 技术栈 |
|---|---|---|
| `protocol/` | 两端共享的消息定义 | Protobuf |
| `desktop/` | 电脑端：执行手机端发来的操作、模拟键鼠、把声音转发给识别服务 | Tauri 2（Rust）+ React + TypeScript |
| `mobile/` | 手机端 App，先做 Android，后续可加 iOS | Kotlin Multiplatform + Compose Multiplatform |

应用标识：电脑端 `com.wbbb.echopad.desktop`，手机端 `com.wbbb.echopad.mobile`。

## 电脑端

前置条件见 [Tauri Prerequisites](https://tauri.app/start/prerequisites/)。

```bash
cd desktop
pnpm install
pnpm tauri dev
```

第一次运行时 macOS 会请求"辅助功能"权限，授权后电脑端才能控制指针和输入文字。

语音输入需要用户自己的密钥（ADR-0007），在电脑端的"设置"里选择服务并填写。密钥和其他设置一起保存在 `~/Library/Application Support/com.wbbb.echopad.desktop/settings.json`，只有当前用户可以读写。不填时其他功能照常可用。

| 服务 | 获取密钥 |
|---|---|
| 豆包语音（识别） | [豆包语音 API Key 管理](https://console.volcengine.com/speech/new/setting/apikeys?projectName=default)，并在控制台开通"流式语音识别"，在设置里选择对应的资源 |
| 阿里百炼（识别、整理） | [获取与配置 API Key](https://docs.bailian.console.aliyun.com/zh/model-studio/get-api-key)，地域选华北 2（北京），识别和整理共用同一个 Key |
| 火山方舟（整理） | [获取 API Key](https://ark.volcengine.com/region:cn-beijing/docs/api-key)，并在"开通管理"里开通所用的模型；与豆包语音的 Key 不通用 |
| DeepSeek、OpenAI、OpenRouter（整理） | 各自控制台的 API Key 页面 |
| Ollama（整理） | 本机运行，不需要 Key |

测试：

```bash
cd desktop/src-tauri
cargo test
```

打包 macOS 应用：用 `APPLE_SIGNING_IDENTITY` 指定本机钥匙串里的签名证书（例如 `Developer ID Application: …`），产物在 `desktop/src-tauri/target/release/bundle/macos/`。签名不变时，更新后不用重新授权辅助功能。

```bash
cd desktop
APPLE_SIGNING_IDENTITY="Developer ID Application: …" pnpm tauri build --bundles app
```

## 手机端

用 Android Studio 打开 `mobile/` 运行，或安装到已连接的设备：

```bash
cd mobile
./gradlew :androidApp:installDebug
```

测试：

```bash
cd mobile
./gradlew :shared:testAndroidHostTest
```

## 调试

- 电脑端的详细日志：用 `RUST_LOG=desktop_lib=debug pnpm tauri dev` 启动，日志里会打印当前的配对链接；换成 `trace` 还会打印每条键鼠命令。
- 在模拟器里配对：没有摄像头时，可以用 adb 打开日志里的配对链接代替扫码，例如 `adb shell am start -a android.intent.action.VIEW -d '"echopad://pair?host=…&port=47321&token=…"'`。
