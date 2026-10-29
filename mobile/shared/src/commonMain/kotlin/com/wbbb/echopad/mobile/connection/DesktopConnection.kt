package com.wbbb.echopad.mobile.connection

import co.touchlab.kermit.Logger
import com.wbbb.echopad.mobile.pairing.PairedDesktop
import com.wbbb.echopad.mobile.pairing.PairedDesktopStore
import com.wbbb.echopad.mobile.pairing.PairingLink
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.Hello
import com.wbbb.echopad.mobile.protocol.ProtocolVersion
import com.wbbb.echopad.mobile.protocol.Rejected
import com.wbbb.echopad.mobile.protocol.ServerMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** 当前电脑的连接状态。 */
sealed interface ConnectionState {
    /** 正在读取已保存的配对信息。 */
    data object Starting : ConnectionState

    /** 一台电脑都没配过。 */
    data object Unpaired : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val desktopName: String) : ConnectionState

    /** 和当前电脑版本不兼容（ADR-0010）：配对仍然有效，继续重连，直到较旧的一端更新。 */
    data class VersionMismatch(val outdated: OutdatedApp) : ConnectionState
}

/** 版本不兼容时需要更新的一端。 */
enum class OutdatedApp { Mobile, Desktop }

/** 配过的一台电脑端，以及它是否在线。 */
data class DesktopEntry(val id: String, val name: String, val online: Boolean)

sealed interface ConnectionNotice {
    /** 扫到的配对码已经用过或已失效。 */
    data object PairingCodeRejected : ConnectionNotice

    /** 这台电脑端取消了和这部手机的配对。 */
    data class PairingRevoked(val desktopName: String) : ConnectionNotice

    /** 电脑端让这部手机切换到了它。 */
    data class SwitchedByDesktop(val desktopName: String) : ConnectionNotice
}

/**
 * 与配过的所有电脑端保持连接（docs/prd/pairing.md、ADR-0004、ADR-0015）：每台各自重连，
 * 其中一台是当前电脑，操作只发给它，界面上的连接状态和收到的消息也只看它。
 * 换网时不干等：还没连上时手机的网络一变就立即重来；断线期间用 [finder] 在局域网里查找电脑端，
 * 它换了地址就立即改连新地址。连着的时候不查找，靠心跳发现断线（ADR-0004）。
 *
 * 配对、取消配对、切换以及各条连接报告的结果，都排队按顺序处理，状态不会被并发改乱。
 */
class DesktopConnection(
    private val store: PairedDesktopStore,
    private val deviceName: String,
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val finder: DesktopFinder,
    private val networkChanges: SharedFlow<Unit>,
) {
    private val paired = MutableStateFlow<List<PairedDesktop>>(emptyList())
    private val _activeId = MutableStateFlow<String?>(null)

    /** 当前电脑的电脑端标识。 */
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    /** 各台电脑端是否在线，以及在线时的名称。没有记录的就是正在连接。 */
    private val online = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 和哪些电脑端版本不兼容，以及该更新哪一端；连上后清除。 */
    private val mismatched = MutableStateFlow<Map<String, OutdatedApp>>(emptyMap())
    private val loaded = MutableStateFlow(false)
    private val outgoing = MutableStateFlow<Map<String, SendChannel<ByteArray>>>(emptyMap())

    val desktops: StateFlow<List<DesktopEntry>> = combine(paired, online) { desktops, online ->
        desktops.map { DesktopEntry(it.id, online[it.id] ?: it.name, it.id in online) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<ConnectionState> = combine(loaded, _activeId, online, mismatched) { loaded, activeId, online, mismatched ->
        when {
            !loaded -> ConnectionState.Starting
            activeId == null -> ConnectionState.Unpaired
            else -> online[activeId]?.let(ConnectionState::Connected)
                ?: mismatched[activeId]?.let(ConnectionState::VersionMismatch)
                ?: ConnectionState.Connecting
        }
    }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.Starting)

    private val _messages = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 64)

    /** 当前电脑发来的消息。 */
    val messages: SharedFlow<ServerMessage> = _messages.asSharedFlow()

    private val _notices = MutableSharedFlow<ConnectionNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<ConnectionNotice> = _notices.asSharedFlow()

    /** 读取已保存的配对之后才开始处理，扫码冷启动 App 时，新的配对不会被旧状态覆盖。 */
    private val commands = Channel<Command>(Channel.UNLIMITED)

    // 以下状态只在处理命令的协程里读写。
    private val links = mutableMapOf<String, Job>()
    private lateinit var phoneId: String

    /** 扫码配对前的当前电脑，配对失败时恢复。 */
    private var beforePairing: String? = null

    init {
        scope.launch {
            val saved = store.load()
            phoneId = store.phoneId()
            paired.value = saved.desktops
            _activeId.value = saved.activeId?.takeIf { id -> saved.desktops.any { it.id == id } }
                ?: saved.desktops.firstOrNull()?.id
            saved.desktops.forEach { startLink(it.id, Target.Paired(it)) }
            loaded.value = true
            for (command in commands) handle(command)
        }
    }

    fun pair(link: PairingLink) {
        commands.trySend(Command.Pair(link))
    }

    fun unpair(id: String) {
        commands.trySend(Command.Unpair(id))
    }

    fun switchTo(id: String) {
        commands.trySend(Command.Switch(id, byDesktop = false))
    }

    /** 发给当前电脑。未连接时直接丢弃并返回 false：断线期间的操作不在重连后补发。 */
    fun send(message: ClientMessage): Boolean {
        val id = _activeId.value ?: return false
        return outgoing.value[id]?.trySend(message.encode())?.isSuccess == true
    }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Pair -> {
                val id = command.link.desktopId
                if (_activeId.value != id) beforePairing = _activeId.value
                links.remove(id)?.cancelAndJoin()
                _activeId.value = id
                startLink(id, Target.Pairing(command.link))
            }
            is Command.Welcomed -> {
                val desktop = command.desktop
                store.save(desktop)
                paired.update { desktops ->
                    if (desktops.any { it.id == desktop.id }) desktops.map { if (it.id == desktop.id) desktop else it } else desktops + desktop
                }
                if (command.paired && _activeId.value == desktop.id) store.setActive(desktop.id)
            }
            is Command.Rejected -> {
                links.remove(command.id)
                if (command.pairing) {
                    _notices.emit(ConnectionNotice.PairingCodeRejected)
                    // 扫到无效的配对码时，原来的配对不受影响。
                    paired.value.find { it.id == command.id }?.let { startLink(it.id, Target.Paired(it)) }
                    if (_activeId.value == command.id && paired.value.none { it.id == command.id }) {
                        _activeId.value = beforePairing ?: paired.value.firstOrNull()?.id
                    }
                } else {
                    val name = paired.value.find { it.id == command.id }?.name.orEmpty()
                    forget(command.id)
                    _notices.emit(ConnectionNotice.PairingRevoked(name))
                }
            }
            is Command.Unpair -> {
                links.remove(command.id)?.cancelAndJoin()
                forget(command.id)
            }
            is Command.Switch -> {
                val desktop = paired.value.find { it.id == command.id } ?: return
                val previous = _activeId.value
                if (previous == desktop.id) return
                _activeId.value = desktop.id
                store.setActive(desktop.id)
                // 原来那台重新连接一次：电脑端照常收拾这部手机留下的残局，例如按住的鼠标键、没说完的语音。
                paired.value.find { it.id == previous }?.let { old ->
                    links.remove(old.id)?.cancelAndJoin()
                    startLink(old.id, Target.Paired(old))
                }
                if (command.byDesktop) _notices.emit(ConnectionNotice.SwitchedByDesktop(desktop.name))
            }
        }
    }

    /** 移除一台电脑的配对；它是当前电脑时，换成剩下的第一台。 */
    private suspend fun forget(id: String) {
        store.remove(id)
        paired.update { desktops -> desktops.filter { it.id != id } }
        if (_activeId.value == id) {
            _activeId.value = paired.value.firstOrNull()?.id
            store.setActive(_activeId.value)
        }
    }

    private fun startLink(id: String, target: Target) {
        links[id] = scope.launch { maintain(id, target) }
    }

    /** 一直连着这台电脑端：每次连接都用到断开为止，断了就重连，直到配对被拒。 */
    private suspend fun maintain(id: String, initial: Target) = coroutineScope {
        /** 这次断线以来，电脑端在局域网里广播的地址。 */
        val advertised = MutableStateFlow(emptySet<DesktopAddress>())
        fun lookUp(): Job {
            advertised.value = emptySet()
            return launch {
                finder.addresses(id).collect {
                    log.d { "$id advertised at $it" }
                    advertised.value = it
                }
            }
        }
        var lookup = lookUp()
        var target = initial
        var failures = 0
        var wait = Duration.ZERO
        try {
            while (true) {
                val wake = nextAttempt(wait, target, advertised)
                if (wake == Wake.NetworkChanged) failures = 0
                target = target.locatedAt(advertised.value)
                log.i { "$id connecting to ${target.address}" + (wake?.let { " ($it)" } ?: "") }
                var welcomed = false
                val end = attempt(id, target, advertised) { desktop ->
                    log.i { "$id connected at ${desktop.host}:${desktop.port}" }
                    lookup.cancel()
                    target = Target.Paired(desktop)
                    failures = 0
                    welcomed = true
                }
                log.i { "$id connection ended: $end" }
                online.update { it - id }
                if (welcomed) {
                    // 每次断线后重新开始查找：系统会把当前的地址重新报一遍，包括新出现的网络（例如刚打开的手机热点）。
                    lookup = lookUp()
                }
                if (end !is SessionEnd.VersionMismatch) mismatched.update { it - id }
                wait = when (end) {
                    SessionEnd.Dropped -> reconnectDelay(failures++)
                    // 版本不兼容时保留配对，照常重连，较旧的一端更新后自动连上。
                    is SessionEnd.VersionMismatch -> {
                        mismatched.update { it + (id to end.outdated) }
                        reconnectDelay(failures++)
                    }
                    SessionEnd.NetworkChanged -> {
                        failures = 0
                        Duration.ZERO
                    }
                    SessionEnd.Moved -> Duration.ZERO
                    SessionEnd.Rejected -> {
                        commands.send(Command.Rejected(id, pairing = target is Target.Pairing))
                        break
                    }
                }
            }
        } finally {
            lookup.cancel()
            online.update { it - id }
            mismatched.update { it - id }
        }
    }

    /** 等到下次重连：间隔到了就连；手机的网络变了、或者电脑换了地址，不再等。 */
    private suspend fun nextAttempt(interval: Duration, target: Target, advertised: StateFlow<Set<DesktopAddress>>): Wake? =
        withTimeoutOrNull(interval) {
            merge(networkChanges.map { Wake.NetworkChanged }, moved(target, advertised).map { Wake.Moved }).first()
        }

    /** 连接并一直用到断开。还没连上时手机的网络变了、或者电脑换了地址，这次连接作废，好立即重来。 */
    private suspend fun attempt(
        id: String,
        target: Target,
        advertised: StateFlow<Set<DesktopAddress>>,
        onWelcome: (PairedDesktop) -> Unit,
    ): SessionEnd = try {
        merge(
            flow { emit(session(id, target, onWelcome)) },
            networkChanges.filter { id !in online.value }.map { SessionEnd.NetworkChanged },
            moved(target, advertised).filter { id !in online.value }.map { SessionEnd.Moved },
        ).first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.d(e) { "$id connection failed" }
        SessionEnd.Dropped
    }

    /** 电脑端广播的地址里已经没有 [target] 正在用的地址：电脑换了地址。 */
    private fun moved(target: Target, advertised: StateFlow<Set<DesktopAddress>>): Flow<Unit> =
        advertised.filter(target::movedFrom).map {}

    private suspend fun session(id: String, target: Target, onWelcome: (PairedDesktop) -> Unit): SessionEnd {
        val (host, port, fingerprint) = when (target) {
            is Target.Pairing -> Triple(target.link.host, target.link.port, target.link.fingerprint)
            is Target.Paired -> Triple(target.desktop.host, target.desktop.port, target.desktop.fingerprint)
        }
        return transport.connect(host, port, fingerprint) { session ->
            val hello = when (target) {
                is Target.Pairing -> Hello(
                    device_name = deviceName,
                    phone_id = phoneId,
                    pairing_token = target.link.token,
                    protocol_version = PROTOCOL_VERSION,
                )
                is Target.Paired -> Hello(
                    device_name = deviceName,
                    phone_id = phoneId,
                    device_key = target.desktop.deviceKey,
                    protocol_version = PROTOCOL_VERSION,
                )
            }
            session.send(ClientMessage(hello = hello).encode())

            val reply = ServerMessage.ADAPTER.decode(session.receive())
            reply.rejected?.takeIf { it.reason == Rejected.Reason.REASON_VERSION_MISMATCH }?.let { rejected ->
                val outdated = if (PROTOCOL_VERSION < rejected.protocol_version) OutdatedApp.Mobile else OutdatedApp.Desktop
                return@connect SessionEnd.VersionMismatch(outdated)
            }
            val welcome = reply.welcome ?: return@connect SessionEnd.Rejected
            val deviceKey = welcome.device_key.ifEmpty { (target as? Target.Paired)?.desktop?.deviceKey }
                ?: return@connect SessionEnd.Rejected
            val desktop = PairedDesktop(id, welcome.desktop_name, host, port, deviceKey, fingerprint)
            onWelcome(desktop)
            commands.send(Command.Welcomed(desktop, paired = target is Target.Pairing))
            online.update { it + (id to desktop.name) }

            coroutineScope {
                val queue = Channel<ByteArray>(Channel.UNLIMITED)
                outgoing.update { it + (id to queue) }
                try {
                    launch {
                        for (bytes in queue) session.send(bytes)
                    }
                    forwardMessages(id, session)
                } finally {
                    outgoing.update { it - id }
                    queue.close()
                }
            }
        }
    }

    /** 一直读到连接断开，receive 抛出的异常由 maintain 当作断线处理。 */
    private suspend fun forwardMessages(id: String, session: Session): Nothing {
        while (true) {
            val message = ServerMessage.ADAPTER.decode(session.receive())
            when {
                message.switch_request != null -> commands.send(Command.Switch(id, byDesktop = true))
                _activeId.value == id -> _messages.emit(message)
            }
        }
    }

    private sealed interface Target {
        val address: DesktopAddress

        /** 电脑端广播的地址里没有正在用的地址。还不知道它广播了什么（为空）时不算。 */
        fun movedFrom(advertised: Set<DesktopAddress>): Boolean

        /** 电脑换了地址时改连它广播的地址。 */
        fun locatedAt(advertised: Set<DesktopAddress>): Target

        /** 每次扫码都是一个新对象，即使扫到相同的配对码也会重新连接。只连配对码里的地址。 */
        class Pairing(val link: PairingLink) : Target {
            override val address get() = DesktopAddress(link.host, link.port)

            override fun movedFrom(advertised: Set<DesktopAddress>) = false

            override fun locatedAt(advertised: Set<DesktopAddress>) = this
        }

        data class Paired(val desktop: PairedDesktop) : Target {
            override val address get() = DesktopAddress(desktop.host, desktop.port)

            override fun movedFrom(advertised: Set<DesktopAddress>) = advertised.isNotEmpty() && address !in advertised

            override fun locatedAt(advertised: Set<DesktopAddress>) =
                if (movedFrom(advertised)) {
                    val address = advertised.first()
                    Paired(desktop.copy(host = address.host, port = address.port))
                } else {
                    this
                }
        }
    }

    private enum class Wake { NetworkChanged, Moved }

    private sealed interface Command {
        class Pair(val link: PairingLink) : Command
        class Unpair(val id: String) : Command
        class Switch(val id: String, val byDesktop: Boolean) : Command

        /** 一条连接连上了；[paired] 表示这是扫码配对成功。 */
        class Welcomed(val desktop: PairedDesktop, val paired: Boolean) : Command
        class Rejected(val id: String, val pairing: Boolean) : Command
    }

    private sealed interface SessionEnd {
        data object Dropped : SessionEnd
        data object Rejected : SessionEnd

        /** 还没连上时手机的网络变了。 */
        data object NetworkChanged : SessionEnd

        /** 还没连上时电脑换了地址。 */
        data object Moved : SessionEnd

        data class VersionMismatch(val outdated: OutdatedApp) : SessionEnd
    }

    companion object {
        private val log = Logger.withTag("EchoPad.Connection")

        /** 手机端的协议版本（ADR-0010）。 */
        val PROTOCOL_VERSION = ProtocolVersion.PROTOCOL_VERSION_CURRENT.value

        private val RECONNECT_DELAYS = listOf(500.milliseconds, 1.seconds, 2.seconds, 4.seconds)
        private val MAX_RECONNECT_DELAY = 5.seconds

        /** 连续失败次数越多，重连前等待越久。 */
        fun reconnectDelay(failures: Int): Duration = RECONNECT_DELAYS.getOrElse(failures) { MAX_RECONNECT_DELAY }
    }
}
