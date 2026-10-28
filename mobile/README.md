# EchoPad Mobile

EchoPad 手机端 App，基于 Kotlin Multiplatform + Compose Multiplatform，界面和逻辑由 Android 与 iOS 共用。项目由 [KMP 官方向导](https://kmp.jetbrains.com/) 生成。

- `shared/`：两端共用的代码。`commonMain` 放通用代码，`androidMain`、`iosMain` 放各平台专属代码。
- `androidApp/`：Android 应用入口。
- `iosApp/`：iOS 应用入口（Xcode 工程）。

## 本地开发

- Android：`./gradlew :androidApp:assembleDebug`
- iOS：用 Xcode 打开 `iosApp/` 运行

## 测试

- Android：`./gradlew :shared:testAndroidHostTest`
- iOS：`./gradlew :shared:iosSimulatorArm64Test`
