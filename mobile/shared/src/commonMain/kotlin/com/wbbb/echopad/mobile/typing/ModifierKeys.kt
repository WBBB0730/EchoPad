package com.wbbb.echopad.mobile.typing

import com.wbbb.echopad.mobile.protocol.ModifierKey
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 一个修饰键的状态；没有记录的修饰键是松开的。 */
enum class ModifierState {
    /** 点亮：只作用于下一次操作，用完自动松开。 */
    Latched,

    /** 锁定：一直生效，再点一下才松开。 */
    Locked,
}

/**
 * 修饰键（docs/prd/typing.md）：点一下点亮，作用于下一个特殊键、字或触控区的点击，用完自动松开；
 * 双击或长按锁定，再点一下才松开。状态只在手机端，每条操作带上当时生效的修饰键（ADR-0016）。
 */
class ModifierKeys(private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic) {
    private val _states = MutableStateFlow<Map<ModifierKey, ModifierState>>(emptyMap())

    /** 生效的修饰键和它们的状态。 */
    val states: StateFlow<Map<ModifierKey, ModifierState>> = _states.asStateFlow()

    /** 每个修饰键上一次被点的时间，用来判断双击。 */
    private val tappedAt = mutableMapOf<ModifierKey, ComparableTimeMark>()

    /** 有没有修饰键生效。 */
    val active: Boolean
        get() = _states.value.isNotEmpty()

    /**
     * 点一下 [key]：松开的点亮；点亮的在 [doubleTapTimeout] 内再点是双击，锁定，否则取消；锁定的松开。
     * 返回点完以后的状态，松开时为 null。
     */
    fun tap(key: ModifierKey, doubleTapTimeout: Duration): ModifierState? {
        val now = timeSource.markNow()
        val previous = tappedAt.put(key, now)
        val next = when (_states.value[key]) {
            null -> ModifierState.Latched
            ModifierState.Latched -> ModifierState.Locked.takeIf { previous != null && now - previous <= doubleTapTimeout }
            ModifierState.Locked -> null
        }
        set(key, next)
        return next
    }

    /** 长按 [key] 锁定。 */
    fun lock(key: ModifierKey) = set(key, ModifierState.Locked)

    /** 一次操作带上生效的修饰键：点亮的用完松开，锁定的保留。按 Control、Alt、Shift、Meta 的顺序。 */
    fun consume(): List<ModifierKey> {
        val current = _states.value
        _states.value = current.filterValues { it == ModifierState.Locked }
        return current.keys.sortedBy { it.value }
    }

    /** 全部松开，在隐藏修饰键那一排时使用，免得看不见的修饰键带到下一次操作上。 */
    fun releaseAll() {
        _states.value = emptyMap()
    }

    private fun set(key: ModifierKey, state: ModifierState?) {
        _states.update { if (state == null) it - key else it + (key to state) }
    }
}
