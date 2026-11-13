package com.wbbb.echopad.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wbbb.echopad.mobile.connection.ConnectionNotice
import com.wbbb.echopad.mobile.dictation.DictationController
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
    val clipboardSync = core.clipboardSync
    val dictation = DictationController(connection, viewModelScope)
    val keyboard = KeyboardInput(connection)
    val updater = core.platform.packages?.let(::AppUpdater)

    val touchpadSettings: StateFlow<TouchpadSettings> =
        storage.touchpadSettings.stateIn(viewModelScope, SharingStarted.Eagerly, TouchpadSettings())

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 需要提示用户的消息。 */
    val notices: Flow<String> = merge(
        _notices,
        connection.notices.map { notice ->
            when (notice) {
                ConnectionNotice.PairingCodeRejected -> "配对码已失效，请重新扫码"
                is ConnectionNotice.PairingRevoked -> "${notice.desktopName} 已取消和这部手机的配对"
                is ConnectionNotice.SwitchedByDesktop -> "已切换到 ${notice.desktopName}"
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

    fun setClipboardSync(enabled: Boolean) {
        viewModelScope.launch { storage.setClipboardSync(enabled) }
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
