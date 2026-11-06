package com.wbbb.echopad.mobile.touchpad

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 把滚动条上的单指滑动识别成手势（docs/prd/touchpad.md）。超出轻点范围时按方向判定，判定后到手指离开都不变：
 * - 上下方向为主是滚动：只滚上下，按 [direction] 换算方向，快速滑动后离开按惯性继续滚；
 * - 左右方向为主是移动指针：多半是想移动指针时正好按到了滚动条，和触控区单指移动一样，但不会变成单击或拖拽。
 *
 * 轻点什么都不做。只识别在滚动条上按下的那一根手指。时间单位为毫秒，坐标单位为 dp。
 */
class ScrollStripRecognizer(private val direction: ScrollDirection = ScrollDirection.Natural) {
    private var origin = TouchPoint(0f, 0f)
    private var last = origin
    private var lastTime = 0L
    private var gesture: Gesture? = null
    private var fling = FlingTracker()

    fun down(x: Float, y: Float, time: Long) {
        origin = TouchPoint(x, y)
        last = origin
        lastTime = time
        gesture = null
        fling = FlingTracker()
    }

    fun move(x: Float, y: Float, time: Long): List<TouchpadAction> {
        val dx = x - last.x
        val dy = y - last.y
        val elapsed = time - lastTime
        last = TouchPoint(x, y)
        lastTime = time
        val decided = gesture
        if (decided == null) {
            gesture = decide()
            // 和双指滚动一样，判定为滚动的这次移动就开始滚；和触控区一样，超出轻点范围之后的下一次移动才移动指针。
            if (gesture != Gesture.Scroll) return emptyList()
        }
        return when (gesture) {
            Gesture.Scroll -> if (dy == 0f) {
                emptyList()
            } else {
                val scroll = direction.apply(dy)
                fling.add(time, 0f, scroll)
                listOf(TouchpadAction.Scroll(0f, scroll))
            }
            Gesture.Pointer -> if (dx == 0f && dy == 0f) emptyList() else listOf(TouchpadAction.Move(dx, dy, elapsed))
            null -> emptyList()
        }
    }

    /** 只有滚动会记下滑动，所以只有滚动会产生惯性。 */
    fun up(x: Float, y: Float, time: Long): List<TouchpadAction> = move(x, y, time) + listOfNotNull(fling.release(time))

    /** 超出轻点范围后，上下位移不小于左右位移是滚动，否则是移动指针；还在轻点范围内时返回空。 */
    private fun decide(): Gesture? {
        val dx = last.x - origin.x
        val dy = last.y - origin.y
        if (hypot(dx, dy) <= GestureRecognizer.TAP_SLOP) return null
        return if (abs(dy) >= abs(dx)) Gesture.Scroll else Gesture.Pointer
    }

    private enum class Gesture { Scroll, Pointer }
}
