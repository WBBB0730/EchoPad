package com.wbbb.echopad.mobile.touchpad

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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
    data object ZoomStart : TouchpadAction
    /** 两指距离是上一次的多少倍：大于 1 是张开放大，小于 1 是捏合缩小。 */
    data class Zoom(val scale: Float) : TouchpadAction
    data object ZoomEnd : TouchpadAction
}

/** 手指在触控区上的位置，单位为 dp。 */
data class TouchPoint(val x: Float, val y: Float)

/**
 * 把触控区上的手指事件识别成手势（docs/prd/touchpad.md）：
 * 单指滑动移动指针，轻点立即左键单击（连点两下由电脑识别成双击），按住不动一会儿再移动是拖拽，
 * 双指轻点右键，双指滑动滚动，双指张开或捏合缩放。三指及以上的手势第一版不处理。
 *
 * 按下后要等一小段时间，才能确定是不是要拖拽，等待的截止时间见 [deadline]，
 * 调用方需要在截止时间到达时调用 [timeout]。时间单位为毫秒，坐标单位为 dp。
 */
class GestureRecognizer {
    var deadline: Long? = null
        private set

    private var state: State = State.Idle
    private val pointers = mutableMapOf<Long, TouchPoint>()

    fun down(id: Long, x: Float, y: Float, time: Long): List<TouchpadAction> {
        pointers[id] = TouchPoint(x, y)
        val actions = mutableListOf<TouchpadAction>()
        state = when (val current = state) {
            State.Idle -> {
                deadline = time + HOLD_TIMEOUT
                State.OneFinger(id, TouchPoint(x, y), time, moved = false)
            }
            is State.OneFinger -> {
                deadline = null
                State.TwoFingers(pointers.toMap(), time, centroid(), spread())
            }
            is State.Dragging -> current
            is State.TwoFingers -> {
                if (current.gesture == TwoFingerGesture.Zoom) actions += TouchpadAction.ZoomEnd
                State.Ignoring
            }
            State.Ignoring -> State.Ignoring
        }
        return actions
    }

    /**
     * 一次触摸事件里移动了的手指和它们的新位置。同一次事件里的手指要一起传进来：
     * 只更新了一根手指时，两指的距离和中点都还不对，会把双指滑动误判成缩放。
     */
    fun move(moved: Map<Long, TouchPoint>, time: Long): List<TouchpadAction> {
        val changed = moved.filter { (id, point) -> pointers[id].let { it != null && it != point } }
        if (changed.isEmpty()) return emptyList()
        val previous = changed.mapValues { (id, _) -> pointers.getValue(id) }
        pointers.putAll(changed)
        val actions = mutableListOf<TouchpadAction>()
        when (val current = state) {
            is State.OneFinger -> changed[current.id]?.let { point ->
                if (current.moved) {
                    actions += moveAction(previous.getValue(current.id), point, time - current.lastTime)
                    state = current.copy(lastTime = time)
                } else if (distance(current.down, point) > TAP_SLOP) {
                    // 超出轻点范围之前的抖动不移动指针；开始移动后就不再变成拖拽。
                    deadline = null
                    state = current.copy(moved = true, lastTime = time)
                }
            }
            is State.Dragging -> changed[current.id]?.let { point ->
                actions += moveAction(previous.getValue(current.id), point, time - current.lastTime)
                state = current.copy(lastTime = time)
            }
            is State.TwoFingers -> {
                val centroid = centroid()
                val spread = spread()
                val gesture = current.gesture ?: decide(current.origins)
                when (gesture) {
                    TwoFingerGesture.Scroll -> {
                        val dx = centroid.x - current.centroid.x
                        val dy = centroid.y - current.centroid.y
                        actions += TouchpadAction.Scroll(dx, dy)
                        current.samples.add(Sample(time, dx, dy))
                    }
                    TwoFingerGesture.Zoom -> {
                        if (current.gesture == null) actions += TouchpadAction.ZoomStart
                        actions += TouchpadAction.Zoom(spread / current.spread)
                    }
                    null -> Unit
                }
                state = current.copy(centroid = centroid, spread = spread, gesture = gesture)
            }
            State.Idle, State.Ignoring -> Unit
        }
        return actions
    }

    fun up(id: Long, x: Float, y: Float, time: Long): List<TouchpadAction> =
        move(mapOf(id to TouchPoint(x, y)), time) + release(id, time)

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
                when (current.gesture) {
                    null -> if (time - current.startTime <= TWO_FINGER_TAP_TIMEOUT) actions += TouchpadAction.RightClick
                    TwoFingerGesture.Scroll -> flingVelocity(current.samples, time)?.let { actions += it }
                    TwoFingerGesture.Zoom -> actions += TouchpadAction.ZoomEnd
                }
                if (pointers.isEmpty()) State.Idle else State.Ignoring
            }
            State.Ignoring -> if (pointers.isEmpty()) State.Idle else State.Ignoring
            State.Idle -> current
        }
        return actions
    }

    private fun centroid(): TouchPoint =
        TouchPoint(pointers.values.sumOf { it.x.toDouble() }.toFloat() / pointers.size, pointers.values.sumOf { it.y.toDouble() }.toFloat() / pointers.size)

    /** 双指之间的距离。 */
    private fun spread(): Float {
        val (a, b) = pointers.values.toList()
        return distance(a, b)
    }

    /**
     * 参照 Mac 触控板和 libinput：两根手指都超出轻点范围后，朝大致相同的方向移动是滚动，朝相反的方向是缩放；
     * 只有一根手指在动、移动超过 [ONE_FINGER_SCROLL_DISTANCE] 时也是滚动。还判断不了时返回空。
     */
    private fun decide(origins: Map<Long, TouchPoint>): TwoFingerGesture? {
        val (a, b) = origins.map { (id, origin) -> origin to pointers.getValue(id) }
        val movedA = distance(a.first, a.second)
        val movedB = distance(b.first, b.second)
        if (min(movedA, movedB) <= TAP_SLOP) {
            return if (max(movedA, movedB) > ONE_FINGER_SCROLL_DISTANCE) TwoFingerGesture.Scroll else null
        }
        // 两根手指移动方向的夹角小于 90° 时，点积为正。
        val dot = (a.second.x - a.first.x) * (b.second.x - b.first.x) + (a.second.y - a.first.y) * (b.second.y - b.first.y)
        return if (dot > 0) TwoFingerGesture.Scroll else TwoFingerGesture.Zoom
    }

    private sealed interface State {
        data object Idle : State
        data class OneFinger(
            val id: Long,
            val down: TouchPoint,
            val lastTime: Long,
            /** 已经超出轻点范围，在移动指针。 */
            val moved: Boolean,
        ) : State
        data class Dragging(val id: Long, val lastTime: Long) : State
        data class TwoFingers(
            /** 双指按下时每根手指的位置。 */
            val origins: Map<Long, TouchPoint>,
            val startTime: Long,
            /** 上一次触摸事件时两指的中点和距离。 */
            val centroid: TouchPoint,
            val spread: Float,
            /** 判定出的手势，判定后到手指离开都不变；还没超出轻点范围时为空。 */
            val gesture: TwoFingerGesture? = null,
            val samples: MutableList<Sample> = mutableListOf(),
        ) : State
        /** 三指及以上，或双指只抬起了一根：等所有手指离开后再重新开始识别。 */
        data object Ignoring : State
    }

    private enum class TwoFingerGesture { Scroll, Zoom }

    private data class Sample(val time: Long, val dx: Float, val dy: Float)

    companion object {
        /** 移动不超过这个距离（dp）仍算轻点。 */
        const val TAP_SLOP = 8f
        /** 按住不动超过这段时间，就开始拖拽。 */
        const val HOLD_TIMEOUT = 250L
        const val TWO_FINGER_TAP_TIMEOUT = 300L
        /** 双指中只有一根在动、移动超过这个距离（dp，约 4mm）时，当作滚动。 */
        const val ONE_FINGER_SCROLL_DISTANCE = 24f

        /** 只用离开前这段时间内的滑动来估算速度。 */
        private const val FLING_WINDOW = 100L
        /** 离开前停顿超过这段时间，就不产生惯性。 */
        private const val FLING_MAX_PAUSE = 50L
        private const val MIN_FLING_SPEED = 0.1f

        private fun distance(a: TouchPoint, b: TouchPoint) = hypot(a.x - b.x, a.y - b.y)

        private fun moveAction(from: TouchPoint, to: TouchPoint, durationMillis: Long) =
            TouchpadAction.Move(to.x - from.x, to.y - from.y, durationMillis)

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
