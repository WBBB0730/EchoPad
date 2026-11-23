package com.wbbb.echopad.mobile.files

import kotlinx.io.RawSource
import kotlin.math.roundToLong

/**
 * 手机上要发给电脑端的一个文件（docs/prd/send-files.md），已经打开：分享来的文件只在分享页面开着时读得到，
 * 要趁那时打开。发完、取消或者出错时关掉。[size] 在提供文件的应用不告诉时为 null。
 */
data class OutgoingFile(val name: String, val size: Long?, val source: RawSource) : AutoCloseable {
    override fun close() = source.close()
}

/** 这些文件的总大小，有大小不明的文件时为 null。 */
internal fun List<OutgoingFile>.totalSize(): Long? = fold(0L as Long?) { total, file -> file.size?.let { total?.plus(it) } }

/** 剪贴板里要发给当前电脑 [desktopName] 的文件，等用户确认。 */
class PendingFiles(val files: List<OutgoingFile>, val desktopName: String) {
    /** 确认框里的问题，例如"把 3 个文件（12.4 MB）发送到 某某？"；有大小不明的文件时不写大小。 */
    val question: String
        get() {
            val size = files.totalSize()?.let { "（${formatSize(it)}）" }.orEmpty()
            return "把 ${files.size} 个文件${size}发送到 $desktopName？"
        }
}

/** 文件大小，和电脑端一样按 1000 进位，保留一位小数，例如"12.4 MB"。 */
internal fun formatSize(bytes: Long): String {
    if (bytes < 1000) return "$bytes 字节"
    var value = bytes / 1000.0
    var unit = 0
    while (value >= 1000 && unit < SIZE_UNITS.lastIndex) {
        value /= 1000
        unit++
    }
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10} ${SIZE_UNITS[unit]}"
}

private val SIZE_UNITS = listOf("KB", "MB", "GB", "TB")
