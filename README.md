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

应用标识：电脑端 `com.wbbb.echopad.desktop`，手机端 `com.wbbb.echopad.mobile`（debug 构建是 `com.wbbb.echopad.mobile.debug`）。

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

macOS 26 起使用的应用图标，源文件是 `desktop/src-tauri/icons/AppIcon.icon`（Icon Composer 文档）。Tauri 打包时编译它会失败（[tauri-apps/tauri#16386](https://github.com/tauri-apps/tauri/issues/16386)），所以打包用的是预先编译好的 `icons/Assets.car`。改了图标后需要用 Xcode 26 以上重新生成；如果报 "Bad file descriptor"，先结束 `ibtoold` 进程再试：

```bash
cd desktop/src-tauri/icons
out=$(mktemp -d)
xcrun actool AppIcon.icon --compile "$out" --app-icon AppIcon --include-all-app-icons --platform macosx --target-device mac --minimum-deployment-target 26.0 --output-partial-info-plist "$out/info.plist" < /dev/null && cp "$out/Assets.car" .
```

## 手机端

用 Android Studio 打开 `mobile/` 运行，或安装到已连接的设备：

```bash
cd mobile
./gradlew :androidApp:installDebug
```

debug 构建的包名带 `.debug` 后缀，桌面上显示为"EchoPad Debug"，可以和发版的正式版同时安装。

测试：

```bash
cd mobile
./gradlew :shared:testAndroidHostTest
```

## 发版

两端共用一个版本号，只写在仓库根的 `package.json`（ADR-0008）。先在仓库根的 `CHANGELOG.md` 里写好这一版的更新日志（写法见 `docs/prd/release.md`）并提交，再在仓库根运行：

```bash
npm run release
```

它先拉取远端（本地与远端分叉时停下），再用 bumpp 交互选择新版本，改好版本号后提交、打 `vX.Y.Z` 标签并推送。bumpp 通过 npx 运行，不用安装依赖。工作区有未提交的改动时拒绝执行，避免漏提交的更新日志没跟上这一版。

GitHub Actions 看到标签后检查两端、打包，全部成功后发到 GitHub Release，两端的应用内更新随之生效（`docs/prd/in-app-update.md`）。发版需要在仓库的 GitHub Secrets 里配好以下内容，Apple 那几项与 DevCube 的同名 Secret 相同：

| Secret | 内容 |
|---|---|
| `MAC_CSC_LINK` | Developer ID 证书（.p12）的 base64 |
| `MAC_CSC_KEY_PASSWORD` | 证书的密码 |
| `APPLE_API_KEY` | App Store Connect API Key（.p8）的 base64 |
| `APPLE_API_KEY_ID` | API Key 的 Key ID |
| `APPLE_API_ISSUER` | API Key 的 Issuer ID |
| `TAURI_SIGNING_PRIVATE_KEY` | 电脑端更新签名私钥，对应的公钥写在 `tauri.conf.json` |
| `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` | 更新签名私钥的密码 |
| `ANDROID_KEYSTORE` | 手机端发版签名文件（.jks）的 base64 |
| `ANDROID_KEYSTORE_PASSWORD` | 签名文件的密码 |
| `ANDROID_KEY_ALIAS` | 签名密钥的别名 |
| `ANDROID_KEY_PASSWORD` | 签名密钥的密码 |

更新签名私钥和手机端签名文件一旦丢了或换了，已安装的用户就无法在应用内更新，务必另外离线备份。

## 调试

- 电脑端的详细日志：用 `RUST_LOG=desktop_lib=debug pnpm tauri dev` 启动，日志里会打印当前的配对链接；换成 `trace` 还会打印每条键鼠命令。
- 在模拟器里配对：没有摄像头时，可以用 adb 打开日志里的配对链接代替扫码，例如 `adb shell am start -a android.intent.action.VIEW -d '"echopad://pair?host=…&port=47321&token=…"'`。
