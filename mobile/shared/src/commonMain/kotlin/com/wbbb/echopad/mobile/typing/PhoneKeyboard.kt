package com.wbbb.echopad.mobile.typing

import androidx.compose.ui.Modifier

/**
 * 手机键盘直通（docs/prd/typing.md）：[open] 为 true 时弹出手机键盘，把输入法的输出交给 [keyboard]；
 * 为 false 时收起手机键盘。
 */
expect fun Modifier.phoneKeyboard(keyboard: KeyboardInput, open: Boolean): Modifier
