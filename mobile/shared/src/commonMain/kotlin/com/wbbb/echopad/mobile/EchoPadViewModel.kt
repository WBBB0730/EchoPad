package com.wbbb.echopad.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wbbb.echopad.mobile.connection.ConnectionNotice
import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.connection.KtorTransport
import com.wbbb.echopad.mobile.dictation.DictationController
import com.wbbb.echopad.mobile.dictation.DictationMode
import com.wbbb.echopad.mobile.pairing.PairingLink
import com.wbbb.echopad.mobile.typing.Composer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EchoPadViewModel(platform: Platform) : ViewModel() {
    private val storage = platform.storage
    private val transport = KtorTransport()
    val connection = DesktopConnection(storage.pairedDesktops, platform.deviceName, transport, viewModelScope)
    val dictation = DictationController(connection, viewModelScope)
    val composer = Composer(connection, viewModelScope)

    val dictationMode: StateFlow<DictationMode> =
        storage.dictationMode.stateIn(viewModelScope, SharingStarted.Eagerly, DictationMode.Direct)

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 需要提示用户的消息。 */
    val notices: Flow<String> = merge(
        _notices,
        connection.notices.map { notice ->
            when (notice) {
                ConnectionNotice.PairingCodeRejected -> "配对码已失效，请重新扫码"
                ConnectionNotice.PairingRevoked -> "电脑端已不认可这台手机，请重新扫码配对"
            }
        },
        dictation.failures,
    )

    fun onPairingLink(text: String) {
        val link = PairingLink.parse(text)
        if (link == null) notify("这不是 EchoPad 的配对码") else connection.pair(link)
    }

    fun setDictationMode(mode: DictationMode) {
        viewModelScope.launch { storage.setDictationMode(mode) }
    }

    fun notify(message: String) {
        _notices.tryEmit(message)
    }

    override fun onCleared() {
        transport.close()
    }
}
