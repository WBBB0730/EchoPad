package com.wbbb.echopad.mobile.touchpad

/** 用户设置的指针速度和滚动速度（docs/prd/touchpad.md），都是倍数。 */
data class TouchpadSpeed(val pointer: Float = 1f, val scroll: Float = 1f) {
    companion object {
        val RANGE = 0.5f..2f
    }
}
