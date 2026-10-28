package com.wbbb.echopad.mobile.touchpad

import kotlin.math.hypot
import kotlin.math.pow

/** 双指快速滑动后离开触控区，滚动按惯性逐渐减速停下。速度单位为 dp/ms。 */
class ScrollInertia(private var vx: Float, private var vy: Float) {
    /** 推进 [elapsedMillis] 毫秒，返回这段时间里滚动的距离；速度小到可以忽略时返回 null。 */
    fun step(elapsedMillis: Long): Pair<Float, Float>? {
        if (hypot(vx, vy) < STOP_SPEED) return null
        val dx = vx * elapsedMillis
        val dy = vy * elapsedMillis
        val decay = DECAY_PER_MILLI.pow(elapsedMillis.toFloat())
        vx *= decay
        vy *= decay
        return dx to dy
    }

    private companion object {
        const val DECAY_PER_MILLI = 0.996f
        const val STOP_SPEED = 0.02f
    }
}
