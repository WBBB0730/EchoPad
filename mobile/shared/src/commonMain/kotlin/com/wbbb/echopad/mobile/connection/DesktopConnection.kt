package com.wbbb.echopad.mobile.connection

import com.wbbb.echopad.mobile.pairing.PairedDesktop
import com.wbbb.echopad.mobile.pairing.PairedDesktopStore
import com.wbbb.echopad.mobile.pairing.PairingLink
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.Hello
import com.wbbb.echopad.mobile.protocol.ServerMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

sealed interface ConnectionState {
    /** 正在读取已保存的配对信息。 */
    data object Starting : ConnectionState
    data object Unpaired : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val desktopName: String) : ConnectionState
}

enum class ConnectionNotice {
    /** 扫到的配对码已经用过或已失效。 */
    PairingCodeRejected,
    /** 电脑端不再认可这台手机，需要重新配对。 */
    PairingRevoked,
}

/**
 * 维持与电脑端的连接：配对、验证、断线后自动重连（docs/prd/pairing.md、ADR-0004）。
 */
class DesktopConnection(
    private val store: PairedDesktopStore,
    private val deviceName: String,
    private val transport: Transport,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Starting)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<ServerMessage> = _messages.asSharedFlow()

    private val _notices = MutableSharedFlow<ConnectionNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<ConnectionNotice> = _notices.asSharedFlow()

    /** 配对、取消配对的请求。读取已保存的配对期间到达的请求会排在它后面处理。 */
    private val requests = Channel<Target?>(Channel.UNLIMITED)
    private val outgoing = MutableStateFlow<SendChannel<ByteArray>?>(null)

    init {
        scope.launch {
            flow {
                emit(store.load()?.let(Target::Paired))
                emitAll(requests)
            }.collectLatest { target ->
                if (target == null) _state.value = ConnectionState.Unpaired else maintain(target)
            }
        }
    }

    fun pair(link: PairingLink) {
        requests.trySend(Target.Pairing(link))
    }

    fun unpair() {
        scope.launch {
            store.clear()
            requests.send(null)
        }
    }

    /** 未连接时直接丢弃并返回 false：断线期间的操作不在重连后补发。 */
    fun send(message: ClientMessage): Boolean =
        outgoing.value?.trySend(message.encode())?.isSuccess == true

    private suspend fun maintain(initial: Target) {
        var target = initial
        var failures = 0
        while (true) {
            _state.value = ConnectionState.Connecting
            val end = try {
                session(target) { paired ->
                    target = Target.Paired(paired)
                    failures = 0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SessionEnd.Dropped
            }
            when (end) {
                SessionEnd.Dropped -> {
                    _state.value = ConnectionState.Connecting
                    delay(reconnectDelay(failures++))
                }
                SessionEnd.Rejected -> {
                    val current = target
                    if (current is Target.Paired) {
                        store.clear()
                        _notices.emit(ConnectionNotice.PairingRevoked)
                    } else {
                        _notices.emit(ConnectionNotice.PairingCodeRejected)
                    }
                    // 回到已保存的配对；扫到无效配对码时，原来的配对不受影响。
                    requests.send(store.load()?.let(Target::Paired))
                    return
                }
            }
        }
    }

    private suspend fun session(target: Target, onWelcome: (PairedDesktop) -> Unit): SessionEnd {
        val (host, port) = when (target) {
            is Target.Pairing -> target.link.host to target.link.port
            is Target.Paired -> target.desktop.host to target.desktop.port
        }
        return transport.connect(host, port) { session ->
            val hello = when (target) {
                is Target.Pairing -> Hello(device_name = deviceName, pairing_token = target.link.token)
                is Target.Paired -> Hello(device_name = deviceName, device_key = target.desktop.deviceKey)
            }
            session.send(ClientMessage(hello = hello).encode())

            val welcome = ServerMessage.ADAPTER.decode(session.receive()).welcome
                ?: return@connect SessionEnd.Rejected
            val deviceKey = welcome.device_key.ifEmpty { (target as? Target.Paired)?.desktop?.deviceKey }
                ?: return@connect SessionEnd.Rejected
            val desktop = PairedDesktop(host, port, deviceKey, welcome.desktop_name)
            store.save(desktop)
            onWelcome(desktop)
            _state.value = ConnectionState.Connected(desktop.name)

            coroutineScope {
                val queue = Channel<ByteArray>(Channel.UNLIMITED)
                outgoing.value = queue
                try {
                    launch {
                        for (bytes in queue) session.send(bytes)
                    }
                    forwardMessages(session)
                } finally {
                    outgoing.value = null
                    queue.close()
                }
            }
        }
    }

    /** 一直读到连接断开，receive 抛出的异常由 maintain 当作断线处理。 */
    private suspend fun forwardMessages(session: Session): Nothing {
        while (true) {
            _messages.emit(ServerMessage.ADAPTER.decode(session.receive()))
        }
    }

    private sealed interface Target {
        /** 每次扫码都是一个新对象，即使扫到相同的配对码也会重新连接。 */
        class Pairing(val link: PairingLink) : Target
        data class Paired(val desktop: PairedDesktop) : Target
    }

    private enum class SessionEnd { Dropped, Rejected }

    companion object {
        private val RECONNECT_DELAYS = listOf(500.milliseconds, 1.seconds, 2.seconds, 4.seconds)
        private val MAX_RECONNECT_DELAY = 5.seconds

        /** 连续失败次数越多，重连前等待越久。 */
        fun reconnectDelay(failures: Int): Duration = RECONNECT_DELAYS.getOrElse(failures) { MAX_RECONNECT_DELAY }
    }
}
