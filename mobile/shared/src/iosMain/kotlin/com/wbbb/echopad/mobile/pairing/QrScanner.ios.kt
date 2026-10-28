package com.wbbb.echopad.mobile.pairing

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// 第一版只发布 Android（docs/prd/pairing.md），iOS 的扫码之后用 AVFoundation 实现。
@Composable
actual fun QrScanner(
    onScanned: (String) -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier,
): Unit = TODO("iOS 版的扫码尚未实现")
