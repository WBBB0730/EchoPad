package com.wbbb.echopad.mobile.typing

import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.KeyPress
import com.wbbb.echopad.mobile.protocol.ShortcutAction
import com.wbbb.echopad.mobile.protocol.TypeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 输入框、特殊键和快捷操作（docs/prd/typing.md）。输入框里的文字点发送后整段上屏；
 * 先编辑模式下，电脑端整理好的句子会追加到输入框末尾。
 */
class Composer(
    private val connection: DesktopConnection,
    scope: CoroutineScope,
) {
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _voiceTextArrived = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 先编辑模式下收到语音文字时发出，界面据此打开键盘面板。 */
    val voiceTextArrived: SharedFlow<Unit> = _voiceTextArrived.asSharedFlow()

    init {
        scope.launch {
            connection.messages.collect { message ->
                message.compose_text?.let {
                    _text.value += it.text
                    _voiceTextArrived.tryEmit(Unit)
                }
            }
        }
    }

    fun edit(text: String) {
        _text.value = text
    }

    /** 未连接时不发送，输入框里的文字保留。 */
    fun send(): Boolean {
        val text = _text.value
        if (text.isEmpty() || !connection.send(ClientMessage(type_text = TypeText(text = text)))) return false
        _text.value = ""
        return true
    }

    fun press(key: KeyPress.Key) {
        connection.send(ClientMessage(key_press = KeyPress(key = key)))
    }

    fun shortcut(shortcut: ShortcutAction.Shortcut) {
        connection.send(ClientMessage(shortcut_action = ShortcutAction(shortcut = shortcut)))
    }
}
