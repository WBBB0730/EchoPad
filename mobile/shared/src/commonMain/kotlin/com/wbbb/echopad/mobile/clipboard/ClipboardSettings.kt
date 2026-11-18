package com.wbbb.echopad.mobile.clipboard

/**
 * 用户在手机端的剪贴板同步设置（docs/prd/clipboard-sync.md），默认都开着。两个开关只管自动的部分：
 * 通知按钮、磁贴、"同步到 EchoPad"这些用户主动的同步不受影响，转发也不受影响（ADR-0018）。
 */
data class ClipboardSettings(
    /** 回到 EchoPad 时，把新复制的内容自动发给电脑端。 */
    val autoSend: Boolean = true,
    /** 电脑端同步来的内容写进手机剪贴板。 */
    val receive: Boolean = true,
)
