package com.wbbb.echopad.mobile.connection

import com.wbbb.echopad.mobile.pairing.PairedDesktop
import com.wbbb.echopad.mobile.pairing.PairedDesktopStore
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.Hello
import com.wbbb.echopad.mobile.protocol.ServerMessage
import kotlinx.coroutines.channels.Channel

class MemoryStore(var desktop: PairedDesktop? = null) : PairedDesktopStore {
    override suspend fun load() = desktop

    override suspend fun save(desktop: PairedDesktop) {
        this.desktop = desktop
    }

    override suspend fun clear() {
        desktop = null
    }
}

/** 每次连接都交给测试控制：测试读取手机端发出的消息，并决定电脑端回复什么。 */
class FakeTransport : Transport {
    val sessions = Channel<FakeSession>(Channel.UNLIMITED)

    override suspend fun <T> connect(host: String, port: Int, block: suspend (Session) -> T): T {
        val session = FakeSession(host)
        sessions.send(session)
        return block(session)
    }
}

class FakeSession(val host: String) : Session {
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
