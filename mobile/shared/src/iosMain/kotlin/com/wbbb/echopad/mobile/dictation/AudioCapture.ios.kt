package com.wbbb.echopad.mobile.dictation

import androidx.compose.runtime.Composable

// 第一版只发布 Android（docs/prd/dictation.md），iOS 的录音之后用 AVAudioEngine 实现。
@Composable
actual fun rememberAudioCapture(): AudioCapture = TODO("iOS 版的录音尚未实现")
