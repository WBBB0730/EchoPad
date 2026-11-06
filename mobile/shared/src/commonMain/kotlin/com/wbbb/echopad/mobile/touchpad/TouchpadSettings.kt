package com.wbbb.echopad.mobile.touchpad

/**
 * 用户在手机端的触控板设置（docs/prd/touchpad.md）：指针速度、滚动速度，以及双指滚动和滚动条各自的滚动方向。
 * 滚动方向默认都是自然。
 */
data class TouchpadSettings(
    val speed: TouchpadSpeed = TouchpadSpeed(),
    val twoFingerScroll: ScrollDirection = ScrollDirection.Natural,
    val stripScroll: ScrollDirection = ScrollDirection.Natural,
)
