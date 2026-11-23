package com.wbbb.echopad.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wbbb.echopad.mobile.clipboard.ClipboardSettings
import com.wbbb.echopad.mobile.connection.ConnectionNotice
import com.wbbb.echopad.mobile.dictation.DictationController
import com.wbbb.echopad.mobile.files.OutgoingFile
import com.wbbb.echopad.mobile.files.SendFilesPrompt
import com.wbbb.echopad.mobile.pairing.PairingLink
import com.wbbb.echopad.mobile.touchpad.TouchpadSettings
import com.wbbb.echopad.mobile.typing.KeyboardInput
import com.wbbb.echopad.mobile.update.AppUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 界面用到的状态和操作。连接和剪贴板同步跟随整个进程，在 [EchoPadCore] 里，界面关掉以后也还在。 */
class EchoPadViewModel(core: EchoPadCore) : ViewModel() {
    private val storage = core.storage
    val connection = core.connection
    private val clipboardSync = core.clipboardSync
    private val fileTransfers = core.fileTransfers
    val dictation = DictationController(connection, viewModelScope)
    val keyboard = KeyboardInput(connection)
    val updater = core.platform.packages?.let(::AppUpdater)

    val touchpadSettings: StateFlow<TouchpadSettings> =
        storage.touchpadSettings.stateIn(viewModelScope, SharingStarted.Eagerly, TouchpadSettings())

    /** 剪贴板同步设置；平台不支持剪贴板时（iOS 版还没有）为 null，设置里不显示这一项。 */
    val clipboardSettings: StateFlow<ClipboardSettings>? = clipboardSync?.let {
        storage.clipboardSettings.stateIn(viewModelScope, SharingStarted.Eagerly, ClipboardSettings())
    }

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 需要提示用户的消息。 */
    val notices: Flow<String> = merge(
        _notices,
        connection.notices.map { notice ->
            when (notice) {
                ConnectionNotice.PairingCodeRejected -> "配对码已失效，请重新扫码"
                is ConnectionNotice.PairingRevoked -> "${notice.desktopName} 已取消和这部手机的配对"
                is ConnectionNotice.Switched -> "已切换到 ${notice.desktopName}"
            }
        },
        dictation.failures,
    )

    fun onPairingLink(text: String) {
        val link = PairingLink.parse(text)
        if (link == null) notify("这不是 EchoPad 的配对码") else connection.pair(link)
    }

    fun setTouchpadSettings(settings: TouchpadSettings) {
        viewModelScope.launch { storage.setTouchpadSettings(settings) }
    }

    fun setClipboardSettings(settings: ClipboardSettings) {
        viewModelScope.launch { storage.setClipboardSettings(settings) }
    }

    /** 平台支持剪贴板时（iOS 版还没有）才有 EchoPad 里的"发送剪贴板"按钮。 */
    val canSyncClipboard: Boolean get() = clipboardSync != null

    /** 剪贴板里是文件时，发之前的确认框。 */
    val sendFilesPrompt = SendFilesPrompt()

    /** EchoPad 里的"发送剪贴板"：文字发给所有在线的电脑，文件确认后发给当前电脑（docs/prd/send-files.md），结果用 Snackbar 提示。 */
    fun syncClipboard() {
        val sync = clipboardSync ?: return
        viewModelScope.launch { sync.syncClipboard(sendFilesPrompt::ask)?.let { notify(it.message) } }
    }

    /** EchoPad 里的"发送文件"：用户在文件选择器里选好的文件，直接发给当前电脑（docs/prd/send-files.md），结果用 Snackbar 提示。 */
    fun sendFiles(files: List<OutgoingFile>) {
        viewModelScope.launch { fileTransfers.sendToCurrentDesktop(files)?.let { notify(it.message) } }
    }

    /** 打开设置时 [force] 为 false，距上次检查不足 5 分钟就跳过；手动检查为 true。 */
    fun checkForUpdates(force: Boolean) {
        viewModelScope.launch { updater?.check(force) }
    }

    fun applyUpdate() {
        viewModelScope.launch { updater?.apply() }
    }

    fun notify(message: String) {
        _notices.tryEmit(message)
    }

}
