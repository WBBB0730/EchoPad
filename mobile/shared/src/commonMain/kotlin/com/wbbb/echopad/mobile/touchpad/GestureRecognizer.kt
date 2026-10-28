package com.wbbb.echopad.mobile.touchpad

import kotlin.math.hypot

/** 触控区上识别出的操作。距离单位为 dp，尚未换算成电脑上的距离。 */
sealed interface TouchpadAction {
    data class Move(val dx: Float, val dy: Float, val durationMillis: Long) : TouchpadAction
    data class Scroll(val dx: Float, val dy: Float) : TouchpadAction
    /** 双指离开触控区时的滑动速度（dp/ms），用于滚动惯性。 */
    data class Fling(val vx: Float, val vy: Float) : TouchpadAction
    data object LeftClick : TouchpadAction
    data object RightClick : TouchpadAction
    data object DragStart : TouchpadAction
    data object DragEnd : TouchpadAction
}

/**
 * 把触控区上的手指事件识别成手势（docs/prd/touchpad.md）：
 * 单指滑动移动指针，轻点立即左键单击（连点两下由电脑识别成双击），按住不动一会儿再移动是拖拽，
 * 双指轻点右键，双指滑动滚动。三指及以上的手势第一版不处理。
 *
 * 按下后要等一小段时间，才能确定是不是要拖拽，等待的截止时间见 [deadline]，
 * 调用方需要在截止时间到达时调用 [timeout]。时间单位为毫秒，坐标单位为 dp。
 */
class GestureRecognizer {
    var deadline: Long? = null
        private set

    private var state: State = State.Idle
    private val pointers = mutableMapOf<Long, Point>()

    fun down(id: Long, x: Float, y: Float, time: Long): List<TouchpadAction> {
        pointers[id] = Point(x, y)
        state = when (val current = state) {
            State.Idle -> {
                deadline = time + HOLD_TIMEOUT
                State.OneFinger(id, Point(x, y), time, moved = false)
            }
            is State.OneFinger -> {
                deadline = null
                State.TwoFingers(centroid(), time, scrolling = false)
            }
            is State.Dragging -> current
            is State.TwoFingers -> State.Ignoring
            State.Ignoring -> State.Ignoring
        }
        return emptyList()
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
                    // 超出轻点范围之前的抖动不移动指针；开始移动后就不再变成拖拽。
                    deadline = null
                    state = current.copy(moved = true, lastTime = time)
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
            State.Idle, State.Ignoring -> Unit
        }
        return actions
    }

    fun up(id: Long, x: Float, y: Float, time: Long): List<TouchpadAction> = move(id, x, y, time) + release(id, time)

    /** 按住不动到了截止时间：按下左键，开始拖拽。 */
    fun timeout(time: Long): List<TouchpadAction> {
        val pending = deadline ?: return emptyList()
        val current = state
        if (time < pending || current !is State.OneFinger || current.moved) return emptyList()
        deadline = null
        state = State.Dragging(current.id, time)
        return listOf(TouchpadAction.DragStart)
    }

    private fun release(id: Long, time: Long): List<TouchpadAction> {
        pointers.remove(id)
        val actions = mutableListOf<TouchpadAction>()
        state = when (val current = state) {
            is State.OneFinger -> if (current.id != id) {
                current
            } else {
                deadline = null
                // 没有移动就是轻点，抬手立即单击，不等待。
                if (!current.moved) actions += TouchpadAction.LeftClick
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
            State.Idle -> current
        }
        return actions
    }

    private fun centroid(): Point =
        Point(pointers.values.sumOf { it.x.toDouble() }.toFloat() / pointers.size, pointers.values.sumOf { it.y.toDouble() }.toFloat() / pointers.size)

    private sealed interface State {
        data object Idle : State
        data class OneFinger(
            val id: Long,
            val down: Point,
            val lastTime: Long,
            /** 已经超出轻点范围，在移动指针。 */
            val moved: Boolean,
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
        /** 按住不动超过这段时间，就开始拖拽。 */
        const val HOLD_TIMEOUT = 250L
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
