package com.wbbb.echopad.mobile.typing

import androidx.compose.ui.Modifier

// 第一版只发布 Android（docs/prd/typing.md），iOS 的手机键盘直通之后用 UIKeyInput 实现。
actual fun Modifier.phoneKeyboard(keyboard: KeyboardInput, open: Boolean): Modifier =
    TODO("iOS 版的手机键盘直通尚未实现")
