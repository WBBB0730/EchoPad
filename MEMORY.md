# Project Memory

Durable project knowledge not captured elsewhere.

## User-Requested

- **改完就打包并装上**：每次改完代码，电脑端有改动就重新打包，替换 `/Applications/EchoPad.app` 并重新打开；手机端有改动就重新打安卓 APK 并装到用户自己的手机上，不装模拟器。不用等用户提醒。

## Verified Learnings

- **本地打包签名**：电脑端用钥匙串里的 `Developer ID Application: Bangyue Went (Y8NWKVHU3J)` 签名，换了签名就要重新授权辅助功能。安卓 APK 先打 release 包，再用 `~/.android/debug.keystore`（别名 `androiddebugkey`）经 apksigner 签名，输出 `mobile/androidApp/build/outputs/apk/release/EchoPad-<版本号>.apk`。
