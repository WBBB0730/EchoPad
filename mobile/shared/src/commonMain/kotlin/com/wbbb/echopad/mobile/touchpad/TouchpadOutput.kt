package com.wbbb.echopad.mobile.touchpad

import com.wbbb.echopad.mobile.protocol.ButtonAction
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.MouseButton
import com.wbbb.echopad.mobile.protocol.PointerMove
import com.wbbb.echopad.mobile.protocol.Scroll

/**
 * 把手势换算成发给电脑端的消息。指针移动和滚动先累积，每帧屏幕刷新时由 [flush] 合并发送一次；
 * 按键操作立即发送，并先发出之前累积的移动，保证顺序不乱。
 */
class TouchpadOutput(private val send: (ClientMessage) -> Unit) {
    /** 用户设置的指针速度和滚动速度。 */
    var speed = TouchpadSpeed()

    private var moveX = 0f
    private var moveY = 0f
    private var scrollX = 0f
    private var scrollY = 0f

    fun handle(action: TouchpadAction) {
        when (action) {
            is TouchpadAction.Move -> {
                val (x, y) = PointerAcceleration.apply(action.dx, action.dy, action.durationMillis)
                moveX += x * speed.pointer
                moveY += y * speed.pointer
            }
            is TouchpadAction.Scroll -> scroll(action.dx, action.dy)
            is TouchpadAction.Fling -> Unit
            TouchpadAction.LeftClick -> button(MouseButton.MOUSE_BUTTON_LEFT, ButtonAction.Kind.KIND_CLICK)
            TouchpadAction.RightClick -> button(MouseButton.MOUSE_BUTTON_RIGHT, ButtonAction.Kind.KIND_CLICK)
            TouchpadAction.DragStart -> button(MouseButton.MOUSE_BUTTON_LEFT, ButtonAction.Kind.KIND_PRESS)
            TouchpadAction.DragEnd -> button(MouseButton.MOUSE_BUTTON_LEFT, ButtonAction.Kind.KIND_RELEASE)
        }
    }

    /** 双指滑动的距离（dp）。 */
    fun scroll(dx: Float, dy: Float) {
        scrollX += dx * SCROLL_GAIN * speed.scroll
        scrollY += dy * SCROLL_GAIN * speed.scroll
    }

    fun flush() {
        if (moveX != 0f || moveY != 0f) {
            send(ClientMessage(pointer_move = PointerMove(dx = moveX, dy = moveY)))
            moveX = 0f
            moveY = 0f
        }
        if (scrollX != 0f || scrollY != 0f) {
            send(ClientMessage(scroll = Scroll(dx = scrollX, dy = scrollY)))
            scrollX = 0f
            scrollY = 0f
        }
    }

    private fun button(button: MouseButton, kind: ButtonAction.Kind) {
        flush()
        send(ClientMessage(button_action = ButtonAction(button = button, kind = kind)))
    }

    private companion object {
        /** 手机上滑动 1dp，电脑上滚动的像素数。 */
        const val SCROLL_GAIN = 2f
    }
}
