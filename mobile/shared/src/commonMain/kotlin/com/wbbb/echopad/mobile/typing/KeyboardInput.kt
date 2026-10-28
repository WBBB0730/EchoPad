package com.wbbb.echopad.mobile.typing

import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.KeyPress
import com.wbbb.echopad.mobile.protocol.ShortcutAction
import com.wbbb.echopad.mobile.protocol.TypeText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 手机键盘直通、特殊键和快捷操作（docs/prd/typing.md）。
 *
 * 输入法选定的字立即上屏；组字只显示在手机端；没有组字时的退格、删除、回车直接作用于电脑。
 * 手机端不保留已上屏的文字，所以输入法看到的编辑区里只有组字。
 */
class KeyboardInput(private val connection: DesktopConnection) {
    private val _composing = MutableStateFlow("")

    /** 输入法里还没选定的拼音或字母。 */
    val composing: StateFlow<String> = _composing.asStateFlow()

    /** 输入法选定了文字。文字里的换行转成回车。 */
    fun commit(text: String) {
        _composing.value = ""
        text.split('\n').forEachIndexed { index, line ->
            if (index > 0) press(KeyPress.Key.KEY_ENTER)
            if (line.isNotEmpty()) connection.send(ClientMessage(type_text = TypeText(text = line)))
        }
    }

    /** 输入法更新了组字。 */
    fun compose(text: String) {
        _composing.value = text
    }

    /** 输入法结束组字但没有选字：和普通文本框一样，组字按原样留下，也就是上屏。 */
    fun finishComposing() {
        val text = _composing.value
        if (text.isNotEmpty()) commit(text)
    }

    /** 输入法要删除插入点前后的字。已上屏的字在电脑上，转成退格和删除。 */
    fun delete(before: Int, after: Int) {
        repeat(before) { press(KeyPress.Key.KEY_BACKSPACE) }
        repeat(after) { press(KeyPress.Key.KEY_DELETE) }
    }

    fun press(key: KeyPress.Key) {
        connection.send(ClientMessage(key_press = KeyPress(key = key)))
    }

    fun shortcut(shortcut: ShortcutAction.Shortcut) {
        connection.send(ClientMessage(shortcut_action = ShortcutAction(shortcut = shortcut)))
    }
}
