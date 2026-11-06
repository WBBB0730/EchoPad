package com.wbbb.echopad.mobile.typing

import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.protocol.CharacterPress
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.KeyPress
import com.wbbb.echopad.mobile.protocol.ShortcutAction
import com.wbbb.echopad.mobile.protocol.TypeText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 手机键盘直通、特殊键、修饰键和快捷操作（docs/prd/typing.md）。
 *
 * 输入法选定的字立即上屏；组字只显示在手机端；没有组字时的退格、删除、回车直接作用于电脑。
 * 手机端不保留已上屏的文字，所以输入法看到的编辑区里只有组字。
 * 有修饰键生效时，打的字不上屏，而是和修饰键一起作为组合键发出。
 */
class KeyboardInput(private val connection: DesktopConnection) {
    /** 修饰键，也作用于触控区的点击和拖拽。 */
    val modifiers = ModifierKeys()

    private val _composing = MutableStateFlow("")

    /** 输入法里还没选定的拼音或字母。 */
    val composing: StateFlow<String> = _composing.asStateFlow()

    /** 输入法选定了文字。文字里的换行转成回车；有修饰键生效时，字和修饰键一起作为组合键发出。 */
    fun commit(text: String) {
        _composing.value = ""
        val line = StringBuilder()
        fun typeLine() {
            if (line.isNotEmpty()) connection.send(ClientMessage(type_text = TypeText(text = line.toString())))
            line.clear()
        }
        var index = 0
        while (index < text.length) {
            // 一个字可能由两个 UTF-16 单元组成，要整个发出去。
            val length = if (text[index].isHighSurrogate() && index + 1 < text.length) 2 else 1
            val character = text.substring(index, index + length)
            index += length
            when {
                character == "\n" -> {
                    typeLine()
                    press(KeyPress.Key.KEY_ENTER)
                }
                modifiers.active -> {
                    typeLine()
                    connection.send(ClientMessage(character_press = CharacterPress(character = character, modifiers = modifiers.consume())))
                }
                else -> line.append(character)
            }
        }
        typeLine()
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

    /** 特殊键，带上生效的修饰键。 */
    fun press(key: KeyPress.Key) {
        connection.send(ClientMessage(key_press = KeyPress(key = key, modifiers = modifiers.consume())))
    }

    /** 媒体键本身就是固定的意思，不带修饰键，也不用掉点亮的修饰键。 */
    fun media(key: KeyPress.Key) {
        connection.send(ClientMessage(key_press = KeyPress(key = key)))
    }

    fun shortcut(shortcut: ShortcutAction.Shortcut) {
        connection.send(ClientMessage(shortcut_action = ShortcutAction(shortcut = shortcut)))
    }
}
