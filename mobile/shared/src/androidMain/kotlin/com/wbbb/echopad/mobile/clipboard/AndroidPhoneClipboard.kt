package com.wbbb.echopad.mobile.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import com.wbbb.echopad.mobile.files.openOutgoingFiles

/**
 * Android 的系统剪贴板（ADR-0017）。Android 10 起只有拿着焦点的前台页面能读，写不受限制，后台也能写。
 * 相册等应用"复制"的文件在剪贴板上是 content:// 地址（docs/prd/send-files.md）。整个进程只创建一个。
 */
class AndroidPhoneClipboard(context: Context) : PhoneClipboard {
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val resolver = context.contentResolver

    /** 进程启动的时刻，和剪贴板内容的时间戳一样按开机以来的时间计。 */
    private val startedAt = Process.getStartElapsedRealtime()

    /** 上次回到 EchoPad 时看过的剪贴板内容的时间戳。 */
    private var checkedAt = 0L

    override fun read(): PhoneClipboardContent? {
        val clip = manager.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        val files = clip.files()
        if (files.isEmpty()) return clip.text()?.let(PhoneClipboardContent::Text)
        return try {
            PhoneClipboardContent.Files(resolver.openOutgoingFiles(files))
        } catch (e: Exception) {
            PhoneClipboardContent.UnreadableFiles
        }
    }

    override fun write(text: String) {
        manager.setPrimaryClip(ClipData.newPlainText(LABEL, text))
    }

    /**
     * 剪贴板在进程启动后、上次查看后有了新内容才读：进程启动前就在剪贴板上的内容不算，免得旧内容盖掉电脑上更新的内容；
     * 标记为敏感的内容（例如密码管理器复制的）也不算。只能在拿着焦点时调用。Android 8 以下拿不到内容的时间戳，始终返回 null。
     */
    override fun readIfNew(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val description = manager.primaryClipDescription ?: return null
        val copiedAt = description.timestamp
        if (copiedAt == checkedAt || copiedAt < startedAt) return null
        checkedAt = copiedAt
        if (description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return null
        return manager.primaryClip?.takeIf { it.itemCount > 0 && it.files().isEmpty() }?.text()
    }

    /** 复制的文件。 */
    private fun ClipData.files(): List<Uri> =
        (0 until itemCount).mapNotNull { index -> getItemAt(index).uri?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT } }

    private fun ClipData.text(): String? = getItemAt(0).text?.toString()?.ifEmpty { null }

    private companion object {
        /** 剪贴板内容的说明，用户看不到。 */
        const val LABEL = "EchoPad"
    }
}
