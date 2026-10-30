package com.wbbb.echopad.mobile.connection

import com.wbbb.echopad.mobile.pairing.PairedDesktop
import com.wbbb.echopad.mobile.pairing.PairedDesktopStore
import com.wbbb.echopad.mobile.pairing.SavedPairing
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.Hello
import com.wbbb.echopad.mobile.protocol.ServerMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update

/** 一台配过的电脑端，[id] 不同的电脑地址和证书也不同。 */
fun pairedDesktop(id: String = "desk-1", name: String = "Mac", host: String = "192.168.1.2") =
    PairedDesktop(id, name, host, 47321, "key-$id", "fingerprint-$id")

class MemoryStore(vararg desktops: PairedDesktop) : PairedDesktopStore {
    var desktops = desktops.toList()
    var activeId: String? = desktops.firstOrNull()?.id

    override suspend fun load() = SavedPairing(desktops, activeId)

    override suspend fun save(desktop: PairedDesktop) {
        desktops = desktops.filter { it.id != desktop.id } + desktop
    }

    override suspend fun remove(id: String) {
        desktops = desktops.filter { it.id != id }
        if (activeId == id) activeId = null
    }

    override suspend fun setActive(id: String?) {
        activeId = id
    }

    override suspend fun phoneId() = "phone-1"
}

/** 局域网查找：测试设定各台电脑端广播的地址，并记录查找的次数。 */
class FakeFinder : DesktopFinder {
    private val advertised = MutableStateFlow<Map<String, Set<DesktopAddress>>>(emptyMap())

    /** 开始过几次查找。 */
    var lookups = 0
        private set

    /** 正在进行的查找数。 */
    var searching = 0
        private set

    fun advertise(desktopId: String, vararg addresses: DesktopAddress) {
        advertised.update { it + (desktopId to addresses.toSet()) }
    }

    override fun addresses(desktopId: String): Flow<Set<DesktopAddress>> = flow {
        lookups++
        searching++
        try {
            emitAll(advertised.mapNotNull { it[desktopId] })
        } finally {
            searching--
        }
    }
}

/** 每次连接都交给测试控制：测试读取手机端发出的消息，并决定电脑端回复什么。 */
class FakeTransport : Transport {
    val sessions = Channel<FakeSession>(Channel.UNLIMITED)

    override suspend fun <T> connect(host: String, port: Int, fingerprint: String, block: suspend (Session) -> T): T {
        val session = FakeSession(host, fingerprint)
        sessions.send(session)
        return try {
            block(session)
        } finally {
            session.closed = true
        }
    }
}

class FakeSession(val host: String, val fingerprint: String) : Session {
    /** 手机端已经关掉了这条连接。 */
    var closed = false
    val sent = Channel<ByteArray>(Channel.UNLIMITED)
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    override suspend fun send(bytes: ByteArray) {
        sent.send(bytes)
    }

    override suspend fun receive(): ByteArray = incoming.receive()

    suspend fun hello(): Hello = checkNotNull(ClientMessage.ADAPTER.decode(sent.receive()).hello)

    fun reply(message: ServerMessage) {
        incoming.trySend(message.encode())
    }

    fun drop() {
        incoming.close()
    }
}
