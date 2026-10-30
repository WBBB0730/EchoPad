package com.wbbb.echopad.mobile.connection

import kotlinx.coroutines.flow.Flow

/** 电脑端在局域网里的地址。 */
data class DesktopAddress(val host: String, val port: Int)

/** 在局域网里按电脑端标识留意它广播的地址（docs/prd/pairing.md）。 */
fun interface DesktopFinder {
    /**
     * 这台电脑端在局域网里广播的地址，每次有变化都发出最新的一组，一个都不剩时为空。
     * 有的系统只能报告开始查找时找到的地址（Android 14 以前），之后的变化要重新开始查找才知道。
     * 当前网络无法查找时直接结束。
     */
    fun addresses(desktopId: String): Flow<Set<DesktopAddress>>
}
