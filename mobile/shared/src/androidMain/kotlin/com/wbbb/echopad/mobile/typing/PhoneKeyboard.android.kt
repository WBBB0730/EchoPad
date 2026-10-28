package com.wbbb.echopad.mobile.typing

import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import com.wbbb.echopad.mobile.protocol.KeyPress
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

actual fun Modifier.phoneKeyboard(keyboard: KeyboardInput, open: Boolean): Modifier =
    this then PhoneKeyboardElement(keyboard, open)

private data class PhoneKeyboardElement(val keyboard: KeyboardInput, val open: Boolean) :
    ModifierNodeElement<PhoneKeyboardNode>() {
    override fun create() = PhoneKeyboardNode(keyboard, open)

    override fun update(node: PhoneKeyboardNode) = node.update(keyboard, open)
}

/** 打开时建立输入法会话，输入法的输出经 [DirectInputConnection] 交给 [KeyboardInput]。 */
private class PhoneKeyboardNode(
    private var keyboard: KeyboardInput,
    private var open: Boolean,
) : Modifier.Node(), PlatformTextInputModifierNode, CompositionLocalConsumerModifierNode {
    private var session: Job? = null

    override fun onAttach() {
        if (open) startSession()
    }

    fun update(keyboard: KeyboardInput, open: Boolean) {
        if (keyboard == this.keyboard && open == this.open) return
        this.keyboard = keyboard
        this.open = open
        if (!isAttached) return
        session?.cancel()
        session = null
        if (open) startSession() else hideSoftInput()
    }

    private fun startSession() {
        val keyboard = keyboard
        session = coroutineScope.launch {
            establishTextInputSession {
                // 输入法连上这个会话之后再弹出手机键盘。
                launch { view.inputMethodManager.showSoftInput(view, 0) }
                startInputMethod(
                    PlatformTextInputMethodRequest { outAttributes ->
                        outAttributes.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        // 横屏时输入法默认铺满全屏，会挡住触控区。
                        outAttributes.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        outAttributes.initialSelStart = 0
                        outAttributes.initialSelEnd = 0
                        DirectInputConnection(view, keyboard)
                    },
                )
            }
        }
    }

    private fun hideSoftInput() {
        val view = currentValueOf(LocalView)
        view.inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }
}

private val View.inputMethodManager: InputMethodManager
    get() = context.getSystemService(InputMethodManager::class.java)

/**
 * 输入法看到的编辑区，只保存组字：选定的字立即交给 [keyboard] 上屏并从这里清掉，
 * 所以输入法不会回头改写已上屏的字。编辑区为空时的删除和按键作用于电脑。
 */
private class DirectInputConnection(
    view: View,
    private val keyboard: KeyboardInput,
) : BaseInputConnection(view, true) {
    private val editable = SpannableStringBuilder()

    override fun getEditable(): Editable = editable

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        super.setComposingText(text, newCursorPosition)
        keyboard.compose(text.toString())
        return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        clear()
        keyboard.commit(text.toString())
        return true
    }

    override fun finishComposingText(): Boolean {
        clear()
        keyboard.finishComposing()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        keyboard.delete(beforeLength, afterLength)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        deleteSurroundingText(beforeLength, afterLength)

    /** 有的输入法在编辑区为空时发按键事件，而不是调用上面的方法。 */
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val key = SPECIAL_KEYS[event.keyCode]
        when {
            key != null -> keyboard.press(key)
            event.unicodeChar != 0 -> keyboard.commit(event.unicodeChar.toChar().toString())
        }
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        keyboard.press(KeyPress.Key.KEY_ENTER)
        return true
    }

    private fun clear() {
        removeComposingSpans(editable)
        editable.clear()
        Selection.setSelection(editable, 0)
    }

    private companion object {
        val SPECIAL_KEYS = mapOf(
            KeyEvent.KEYCODE_DEL to KeyPress.Key.KEY_BACKSPACE,
            KeyEvent.KEYCODE_FORWARD_DEL to KeyPress.Key.KEY_DELETE,
            KeyEvent.KEYCODE_ENTER to KeyPress.Key.KEY_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER to KeyPress.Key.KEY_ENTER,
            KeyEvent.KEYCODE_TAB to KeyPress.Key.KEY_TAB,
            KeyEvent.KEYCODE_ESCAPE to KeyPress.Key.KEY_ESCAPE,
            KeyEvent.KEYCODE_DPAD_UP to KeyPress.Key.KEY_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to KeyPress.Key.KEY_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to KeyPress.Key.KEY_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to KeyPress.Key.KEY_RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME to KeyPress.Key.KEY_HOME,
            KeyEvent.KEYCODE_MOVE_END to KeyPress.Key.KEY_END,
            KeyEvent.KEYCODE_PAGE_UP to KeyPress.Key.KEY_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN to KeyPress.Key.KEY_PAGE_DOWN,
        )
    }
}
