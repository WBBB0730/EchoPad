package com.wbbb.echopad.mobile

/** 用户主动同步剪贴板或发送文件的结果，在 EchoPad 以外操作时提示用户。 */
sealed interface SyncResult {
    /** 发给了这几台电脑端，按名称列出。 */
    data class Sent(val desktopNames: List<String>) : SyncResult

    data object NoDesktopOnline : SyncResult

    data object NoText : SyncResult

    data object TooLong : SyncResult

    /** 文件已经交给通知栏里的传输，正在发给当前电脑 [desktopName]（docs/prd/send-files.md）。 */
    data class Sending(val desktopName: String) : SyncResult

    /** 文件只发给当前电脑，它不在线。 */
    data class DesktopOffline(val desktopName: String) : SyncResult

    data object FilesUnreadable : SyncResult
}

/** 同步结果给用户的提示：在 EchoPad 里用 Snackbar，在 EchoPad 以外用 Toast（DESIGN.md）。 */
val SyncResult.message: String
    get() = when (this) {
        is SyncResult.Sent -> desktopNames.singleOrNull()?.let { "已发送到 $it" } ?: "已发送到 ${desktopNames.size} 台电脑"
        SyncResult.NoDesktopOnline -> "没有在线的电脑"
        SyncResult.NoText -> "剪贴板里没有文字"
        SyncResult.TooLong -> "文字太长，没有同步"
        is SyncResult.Sending -> "正在发送到 $desktopName"
        is SyncResult.DesktopOffline -> "$desktopName 不在线"
        SyncResult.FilesUnreadable -> "读不了要发送的文件"
    }
