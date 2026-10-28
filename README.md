# EchoPad

把手机变成电脑的触控板，并支持语音输入。手机和电脑在同一局域网内直连。

术语见 `CONTEXT.md`，关键决策见 `docs/adr/`，各功能需求见 `docs/prd/`。

## 仓库结构

一个 Git 仓库，内含多个独立子项目，各自使用原生构建工具，不使用 pnpm workspace、Turborepo 这类 monorepo 工具（各子项目语言不同，没有可共享的 JS 代码）。

| 目录 | 内容 | 技术栈 |
|---|---|---|
| `desktop/` | 电脑端：执行手机端发来的操作、模拟键鼠、把声音转发给识别服务 | Tauri 2（Rust）+ React + TypeScript |
| `mobile/` | 手机端 App，先做 Android，后续可加 iOS | Kotlin Multiplatform + Compose Multiplatform |

应用标识：电脑端 `com.wbbb.echopad.desktop`，手机端 `com.wbbb.echopad.mobile`。

## 电脑端开发

前置条件见 [Tauri Prerequisites](https://tauri.app/start/prerequisites/)。

```bash
cd desktop
pnpm install
pnpm tauri dev
```

## 手机端开发

用 Android Studio 打开 `mobile/`，或：

```bash
cd mobile
./gradlew :androidApp:assembleDebug
```
