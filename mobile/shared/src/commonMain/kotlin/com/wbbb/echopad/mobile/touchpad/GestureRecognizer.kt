package com.wbbb.echopad.mobile.touchpad

import kotlin.math.hypot

/** 触控区上识别出的操作。距离单位为 dp，尚未换算成电脑上的距离。 */
sealed interface TouchpadAction {
    data class Move(val dx: Float, val dy: Float, val durationMillis: Long) : TouchpadAction
    data class Scroll(val dx: Float, val dy: Float) : TouchpadAction
    /** 双指离开触控区时的滑动速度（dp/ms），用于滚动惯性。 */
    data class Fling(val vx: Float, val vy: Float) : TouchpadAction
    data object LeftClick : TouchpadAction
    data object DoubleClick : TouchpadAction
    data object RightClick : TouchpadAction
    data object DragStart : TouchpadAction
    data object DragEnd : TouchpadAction
}

/**
 * 把触控区上的手指事件识别成手势（docs/prd/touchpad.md）：
 * 单指滑动移动指针，轻点左键，连续两次轻点双击，轻点后按住再移动拖拽，
 * 双指轻点右键，双指滑动滚动。三指及以上的手势第一版不处理。
 *
 * 轻点之后要等一小段时间，才能确定是单击、双击还是拖拽，等待的截止时间见 [deadline]，
 * 调用方需要在截止时间到达时调用 [timeout]。时间单位为毫秒，坐标单位为 dp。
 */
class GestureRecognizer {
    var deadline: Long? = null
        private set

    private var state: State = State.Idle
    private val pointers = mutableMapOf<Long, Point>()

    fun down(id: Long, x: Float, y: Float, time: Long): List<TouchpadAction> {
        pointers[id] = Point(x, y)
        val actions = mutableListOf<TouchpadAction>()
        state = when (val current = state) {
            State.Idle -> State.OneFinger(id, Point(x, y), time, time, moved = false, afterTap = false)
            is State.TapPending -> {
                deadline = null
                State.OneFinger(id, Point(x, y), time, time, moved = false, afterTap = true)
            }
            is State.OneFinger -> {
                // 轻点后第二次按下时又加了一根手指：先补上那次单击。
                if (current.afterTap) actions += TouchpadAction.LeftClick
                State.TwoFingers(centroid(), time, scrolling = false)
            }
            is State.Dragging -> current
            is State.TwoFingers -> State.Ignoring
            State.Ignoring -> State.Ignoring
        }
        return actions
    }

    fun move(id: Long, x: Float, y: Float, time: Long): List<TouchpadAction> {
        val previous = pointers[id] ?: return emptyList()
        val dx = x - previous.x
        val dy = y - previous.y
        if (dx == 0f && dy == 0f) return emptyList()
        pointers[id] = Point(x, y)
        val actions = mutableListOf<TouchpadAction>()
        when (val current = state) {
            is State.OneFinger -> if (current.id == id) {
                if (current.moved) {
                    actions += TouchpadAction.Move(dx, dy, time - current.lastTime)
                    state = current.copy(lastTime = time)
                } else if (distance(current.down, Point(x, y)) > TAP_SLOP) {
                    // 超出轻点范围之前的抖动不移动指针。
                    state = if (current.afterTap) {
                        actions += TouchpadAction.DragStart
                        State.Dragging(id, time)
                    } else {
                        current.copy(moved = true, lastTime = time)
                    }
                }
            }
            is State.Dragging -> if (current.id == id) {
                actions += TouchpadAction.Move(dx, dy, time - current.lastTime)
                state = current.copy(lastTime = time)
            }
            is State.TwoFingers -> {
                val cdx = dx / pointers.size
                val cdy = dy / pointers.size
                val scrolling = current.scrolling || distance(current.start, centroid()) > TAP_SLOP
                if (scrolling) {
                    actions += TouchpadAction.Scroll(cdx, cdy)
                    current.samples.add(Sample(time, cdx, cdy))
                }
                state = current.copy(scrolling = scrolling)
            }
            State.Idle, is State.TapPending, State.Ignoring -> Unit
        }
        return actions
    }

    fun up(id: Long, x: Float, y: Float, time: Long): List<TouchpadAction> = move(id, x, y, time) + release(id, time)

    fun timeout(time: Long): List<TouchpadAction> {
        val pending = deadline ?: return emptyList()
        if (time < pending || state !is State.TapPending) return emptyList()
        deadline = null
        state = State.Idle
        return listOf(TouchpadAction.LeftClick)
    }

    private fun release(id: Long, time: Long): List<TouchpadAction> {
        pointers.remove(id)
        val actions = mutableListOf<TouchpadAction>()
        state = when (val current = state) {
            is State.OneFinger -> if (current.id != id) {
                current
            } else if (!current.moved && time - current.downTime <= TAP_TIMEOUT) {
                if (current.afterTap) {
                    actions += TouchpadAction.DoubleClick
                    State.Idle
                } else {
                    deadline = time + DOUBLE_TAP_WINDOW
                    State.TapPending
                }
            } else {
                // 轻点后第二次按住却没有移动：只算前一次的单击。
                if (current.afterTap) actions += TouchpadAction.LeftClick
                State.Idle
            }
            is State.Dragging -> if (current.id != id) {
                current
            } else {
                actions += TouchpadAction.DragEnd
                if (pointers.isEmpty()) State.Idle else State.Ignoring
            }
            is State.TwoFingers -> {
                if (!current.scrolling && time - current.startTime <= TWO_FINGER_TAP_TIMEOUT) {
                    actions += TouchpadAction.RightClick
                } else if (current.scrolling) {
                    flingVelocity(current.samples, time)?.let { actions += it }
                }
                if (pointers.isEmpty()) State.Idle else State.Ignoring
            }
            State.Ignoring -> if (pointers.isEmpty()) State.Idle else State.Ignoring
            State.Idle, is State.TapPending -> current
        }
        return actions
    }

    private fun centroid(): Point =
        Point(pointers.values.sumOf { it.x.toDouble() }.toFloat() / pointers.size, pointers.values.sumOf { it.y.toDouble() }.toFloat() / pointers.size)

    private sealed interface State {
        data object Idle : State
        /** 一次轻点刚结束，等着看是否接着双击或拖拽。 */
        data object TapPending : State
        data class OneFinger(
            val id: Long,
            val down: Point,
            val downTime: Long,
            val lastTime: Long,
            val moved: Boolean,
            /** 这次按下紧跟在一次轻点之后，可能是双击或拖拽。 */
            val afterTap: Boolean,
        ) : State
        data class Dragging(val id: Long, val lastTime: Long) : State
        data class TwoFingers(
            val start: Point,
            val startTime: Long,
            val scrolling: Boolean,
            val samples: MutableList<Sample> = mutableListOf(),
        ) : State
        /** 三指及以上，或双指只抬起了一根：等所有手指离开后再重新开始识别。 */
        data object Ignoring : State
    }

    private data class Point(val x: Float, val y: Float)

    private data class Sample(val time: Long, val dx: Float, val dy: Float)

    companion object {
        /** 移动不超过这个距离（dp）仍算轻点。 */
        const val TAP_SLOP = 8f
        const val TAP_TIMEOUT = 220L
        /** 轻点后在这段时间内再次按下，就是双击或拖拽。 */
        const val DOUBLE_TAP_WINDOW = 220L
        const val TWO_FINGER_TAP_TIMEOUT = 300L

        /** 只用离开前这段时间内的滑动来估算速度。 */
        private const val FLING_WINDOW = 100L
        /** 离开前停顿超过这段时间，就不产生惯性。 */
        private const val FLING_MAX_PAUSE = 50L
        private const val MIN_FLING_SPEED = 0.1f

        private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

        private fun flingVelocity(samples: List<Sample>, time: Long): TouchpadAction.Fling? {
            val recent = samples.filter { time - it.time <= FLING_WINDOW }
            if (recent.isEmpty() || time - recent.last().time > FLING_MAX_PAUSE) return null
            val span = (recent.last().time - recent.first().time).coerceAtLeast(16L).toFloat()
            val vx = recent.sumOf { it.dx.toDouble() }.toFloat() / span
            val vy = recent.sumOf { it.dy.toDouble() }.toFloat() / span
            return if (hypot(vx, vy) >= MIN_FLING_SPEED) TouchpadAction.Fling(vx, vy) else null
        }
    }
}
