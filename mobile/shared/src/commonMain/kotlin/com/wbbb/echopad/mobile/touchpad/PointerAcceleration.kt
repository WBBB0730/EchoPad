package com.wbbb.echopad.mobile.touchpad

import kotlin.math.hypot

/**
 * 指针加速：把手指移动（dp）换算成指针移动（电脑屏幕上的点）。
 * 慢移时接近一比一，便于精细定位；快移时放大，一划就能跨过屏幕。在手机端计算，
 * 因为触摸事件的时间戳在这里最准，电脑端只负责应用位移。
 */
object PointerAcceleration {
    private const val SLOW_SPEED = 0.05f
    private const val FAST_SPEED = 1.2f
    private const val SLOW_GAIN = 1.2f
    private const val FAST_GAIN = 4.5f

    fun apply(dx: Float, dy: Float, durationMillis: Long): Pair<Float, Float> {
        val speed = hypot(dx, dy) / durationMillis.coerceAtLeast(1L)
        val t = ((speed - SLOW_SPEED) / (FAST_SPEED - SLOW_SPEED)).coerceIn(0f, 1f)
        val smooth = t * t * (3 - 2 * t)
        val gain = SLOW_GAIN + (FAST_GAIN - SLOW_GAIN) * smooth
        return dx * gain to dy * gain
    }
}
