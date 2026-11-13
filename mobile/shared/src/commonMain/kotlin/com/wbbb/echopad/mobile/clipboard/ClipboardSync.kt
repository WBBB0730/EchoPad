package com.wbbb.echopad.mobile.clipboard

import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.ClipboardSyncSetting
import com.wbbb.echopad.mobile.protocol.ClipboardText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encodeUtf8
import okio.utf8Size

/** 手机的系统剪贴板，由各平台实现。 */
interface PhoneClipboard {
    /** 剪贴板上的文字；没有文字时为 null。 */
    fun read(): String?

    /** 回到 EchoPad 时调用：剪贴板在 EchoPad 运行期间有了新内容、而且没有标记为敏感时，读出其中的文字，否则返回 null。 */
    fun readIfNew(): String?

    fun write(text: String)
}

/** 用户主动同步的结果，在 EchoPad 以外操作时提示用户。 */
sealed interface SyncResult {
    /** 发给了这几台电脑端，按名称列出。 */
    data class Sent(val desktopNames: List<String>) : SyncResult

    data object NoDesktopOnline : SyncResult

    data object NoText : SyncResult

    data object TooLong : SyncResult

    data object Disabled : SyncResult
}

/**
 * 剪贴板同步（docs/prd/clipboard-sync.md、ADR-0018）：手机只记一条剪贴板内容和它的更新时间。
 * 本机复制时发给所有在线的电脑端；收到比自己新的内容才写进手机剪贴板，再转给其他电脑端。关着同步时不读剪贴板。
 *
 * [setting] 是用户在设置里的开关，还没从本机存储里读出来时为 null。[now] 返回现在的 Unix 毫秒。
 */
class ClipboardSync(
    private val connection: DesktopConnection,
    private val setting: StateFlow<Boolean?>,
    private val clipboard: PhoneClipboard,
    scope: CoroutineScope,
    private val now: () -> Long,
) {
    val enabled: StateFlow<Boolean> = setting.map { it == true }.stateIn(scope, SharingStarted.Eagerly, false)

    /** 只在 [scope] 的线程上读写。手机端启动的那一刻起算，之前的复制都算旧的。 */
    private var synced = SyncedClipboard.unknownSince(now())

    init {
        scope.launch {
            // 打开同步的那一刻起算；开关变化时告诉所有在线的电脑端。
            enabled.collect { enabled ->
                if (enabled) synced = SyncedClipboard.unknownSince(now())
                connection.sendToAll(settingMessage(enabled))
            }
        }
        scope.launch {
            connection.clipboardTexts.collect { (desktopId, received) ->
                if (!enabled.value || received.text.utf8Size() > MAX_TEXT_BYTES) return@collect
                if (synced.merge(received)) {
                    clipboard.write(received.text)
                    connection.sendToAll(ClientMessage(clipboard_text = received), except = desktopId)
                }
            }
        }
    }

    /** 通知上的按钮和磁贴：读手机剪贴板并发出，算一次新的复制，即使内容和现有的相同也照发。 */
    suspend fun syncClipboard(): SyncResult {
        if (!loadedSetting()) return SyncResult.Disabled
        return send(clipboard.read() ?: return SyncResult.NoText)
    }

    /** 文字选择菜单和分享菜单：选中的文字写进手机剪贴板并发出，效果和在手机上复制一次相同。 */
    suspend fun syncText(text: String): SyncResult {
        if (!loadedSetting()) return SyncResult.Disabled
        clipboard.write(text)
        return send(text)
    }

    /** 回到 EchoPad 时：手机剪贴板有了新内容、和现有的不同，才算一次复制，不报告结果。 */
    suspend fun syncOnReturn() {
        if (!loadedSetting()) return
        val text = clipboard.readIfNew() ?: return
        if (text != synced.text) send(text)
    }

    private suspend fun loadedSetting(): Boolean = setting.filterNotNull().first()

    private fun send(text: String): SyncResult {
        if (text.utf8Size() > MAX_TEXT_BYTES) return SyncResult.TooLong
        val sent = connection.sendToAll(ClientMessage(clipboard_text = synced.copy(text, now())))
        if (sent.isEmpty()) return SyncResult.NoDesktopOnline
        return SyncResult.Sent(connection.desktops.value.filter { it.id in sent }.map { it.name })
    }

    companion object {
        /** 同步的文字按 UTF-8 计不超过这个长度，更长的不同步（echopad.proto）。 */
        const val MAX_TEXT_BYTES = 1_000_000

        /** 每条新连接先发的消息：开着同步时告诉电脑端（ADR-0018）。 */
        fun greeting(enabled: Boolean): List<ClientMessage> = if (enabled) listOf(settingMessage(true)) else emptyList()

        private fun settingMessage(enabled: Boolean) =
            ClientMessage(clipboard_sync_setting = ClipboardSyncSetting(enabled = enabled))
    }
}

/** 本机剪贴板上的内容和它最后更新的时间（ADR-0018），和电脑端的规则相同。 */
internal class SyncedClipboard private constructor(
    text: String?,
    /** Unix 毫秒。 */
    private var updatedAt: Long,
) {
    /** 打开同步或启动以后还没见过内容时为 null。 */
    var text: String? = text
        private set

    /**
     * 在本机复制了 [text]，返回要推送出去的消息。
     * 更新时间取"现在"和"已知最新的更新时间加 1 毫秒"中较大的，本机新复制的总比见过的新。
     */
    fun copy(text: String, now: Long): ClipboardText {
        this.text = text
        updatedAt = maxOf(now, updatedAt + 1)
        return ClipboardText(text = text, updated_at = updatedAt)
    }

    /**
     * 收到别的设备的内容：比现有的新时才用，返回是否用了。先比更新时间，相同再比文字，任意两条都分得出新旧。
     * 文字按 UTF-8 字节比较，和电脑端一致，所有设备才会停在同一条上。
     */
    fun merge(incoming: ClipboardText): Boolean {
        val current = text
        val newer = incoming.updated_at > updatedAt ||
            (incoming.updated_at == updatedAt && (current == null || incoming.text.encodeUtf8() > current.encodeUtf8()))
        if (!newer) return false
        text = incoming.text
        updatedAt = incoming.updated_at
        return true
    }

    companion object {
        /** 打开同步或启动的那一刻：内容未知，之前的复制都算旧的。 */
        fun unknownSince(now: Long) = SyncedClipboard(null, now)
    }
}
