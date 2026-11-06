package com.wbbb.echopad.mobile.typing

import androidx.compose.ui.Modifier

/**
 * 手机键盘直通（docs/prd/typing.md）：[open] 为 true 时弹出手机键盘，把输入法的输出交给 [keyboard]；
 * 为 false 时收起手机键盘。[plain] 为 true 时（有修饰键生效）让输入法不组字、换成英文键盘，
 * 打的每个字都直接交出来，和修饰键组成组合键。
 */
expect fun Modifier.phoneKeyboard(keyboard: KeyboardInput, open: Boolean, plain: Boolean): Modifier
