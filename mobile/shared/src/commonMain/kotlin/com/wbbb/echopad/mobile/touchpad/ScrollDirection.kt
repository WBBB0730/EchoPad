package com.wbbb.echopad.mobile.touchpad

/** 滚动方向（docs/prd/touchpad.md），双指滚动和滚动条上的滚动各选一个。 */
enum class ScrollDirection {
    /** 内容跟着手指走。 */
    Natural,

    /** 和拖电脑上的滚动条一样：手指往下，页面往下翻。 */
    Traditional;

    /** 把手指的位移换算成 [TouchpadAction.Scroll] 的位移，后者的正方向是内容跟着手指走。 */
    fun apply(delta: Float): Float = when (this) {
        Natural -> delta
        // 用 0 减而不直接取负：没有位移时仍是 0，不会变成 -0。
        Traditional -> 0f - delta
    }
}
