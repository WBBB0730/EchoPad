package com.wbbb.echopad.mobile.connection

import com.wbbb.echopad.mobile.pairing.PairedDesktop
import com.wbbb.echopad.mobile.pairing.PairedDesktopStore
import com.wbbb.echopad.mobile.pairing.PairingLink
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.Hello
import com.wbbb.echopad.mobile.protocol.PointerMove
import com.wbbb.echopad.mobile.protocol.Rejected
import com.wbbb.echopad.mobile.protocol.ServerMessage
import com.wbbb.echopad.mobile.protocol.Welcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopConnectionTest {
    private val paired = PairedDesktop("192.168.1.2", 47321, "device-key", "Mac")
    private val transport = FakeTransport()

    @Test
    fun pairingSavesTheIssuedDeviceKey() = runTest {
        val store = MemoryStore()
        val connection = connect(store)
        runCurrent()
        assertEquals(ConnectionState.Unpaired, connection.state.value)

        connection.pair(PairingLink("192.168.1.9", 47321, "token"))
        val session = transport.sessions.receive()
        assertEquals("192.168.1.9", session.host)
        assertEquals("token", session.hello().pairing_token)

        session.reply(ServerMessage(welcome = Welcome(desktop_name = "Office Mac", device_key = "new-key")))
        runCurrent()
        assertEquals(ConnectionState.Connected("Office Mac"), connection.state.value)
        assertEquals(PairedDesktop("192.168.1.9", 47321, "new-key", "Office Mac"), store.desktop)
    }

    @Test
    fun aPairingRequestDuringStartupIsNotOverriddenBySavedState() = runTest {
        val loaded = CompletableDeferred<Unit>()
        val store = object : PairedDesktopStore by MemoryStore() {
            override suspend fun load(): PairedDesktop? {
                loaded.await()
                return null
            }
        }
        val connection = connect(store)
        runCurrent()

        // 例如用系统相机扫码冷启动 App：配对请求比读取已保存的配对先到。
        connection.pair(PairingLink("192.168.1.9", 47321, "token"))
        loaded.complete(Unit)

        val session = transport.sessions.receive()
        assertEquals("token", session.hello().pairing_token)
        session.reply(ServerMessage(welcome = Welcome(desktop_name = "Mac", device_key = "new-key")))
        runCurrent()
        assertEquals(ConnectionState.Connected("Mac"), connection.state.value)
    }

    @Test
    fun reconnectsWithTheDeviceKeyAfterTheConnectionDrops() = runTest {
        val connection = connect(MemoryStore(paired))
        val first = transport.sessions.receive()
        assertEquals("device-key", first.hello().device_key)
        first.reply(ServerMessage(welcome = Welcome(desktop_name = "Mac")))
        runCurrent()
        assertEquals(ConnectionState.Connected("Mac"), connection.state.value)

        first.drop()
        runCurrent()
        assertEquals(ConnectionState.Connecting, connection.state.value)
        advanceTimeBy(DesktopConnection.reconnectDelay(0).inWholeMilliseconds - 1)
        assertTrue(transport.sessions.tryReceive().isFailure)

        advanceTimeBy(2)
        assertEquals("device-key", transport.sessions.receive().hello().device_key)
    }

    @Test
    fun messagesAreDroppedWhileDisconnected() = runTest {
        val connection = connect(MemoryStore(paired))
        val session = transport.sessions.receive()
        val move = ClientMessage(pointer_move = PointerMove(dx = 1f, dy = 2f))
        assertFalse(connection.send(move))

        session.hello()
        session.reply(ServerMessage(welcome = Welcome(desktop_name = "Mac")))
        runCurrent()
        assertTrue(connection.send(move))
        runCurrent()
        assertEquals(move, ClientMessage.ADAPTER.decode(session.sent.receive()))
    }

    @Test
    fun aRevokedDeviceKeyRequiresPairingAgain() = runTest {
        val store = MemoryStore(paired)
        val connection = connect(store)
        val notices = collectNotices(connection)

        val session = transport.sessions.receive()
        session.hello()
        session.reply(ServerMessage(rejected = Rejected(reason = Rejected.Reason.REASON_INVALID_CREDENTIAL)))
        runCurrent()

        assertEquals(ConnectionState.Unpaired, connection.state.value)
        assertNull(store.desktop)
        assertEquals(listOf(ConnectionNotice.PairingRevoked), notices)
    }

    @Test
    fun aRejectedPairingCodeKeepsTheExistingPairing() = runTest {
        val store = MemoryStore(paired)
        val connection = connect(store)
        val notices = collectNotices(connection)
        transport.sessions.receive()

        connection.pair(PairingLink("192.168.1.9", 47321, "stale-token"))
        val pairing = transport.sessions.receive()
        pairing.hello()
        pairing.reply(ServerMessage(rejected = Rejected(reason = Rejected.Reason.REASON_INVALID_CREDENTIAL)))
        runCurrent()

        assertEquals(listOf(ConnectionNotice.PairingCodeRejected), notices)
        assertEquals(paired, store.desktop)
        assertEquals(paired.host, transport.sessions.receive().host)
    }

    private fun TestScope.connect(store: PairedDesktopStore) =
        DesktopConnection(store, "Phone", transport, backgroundScope)

    private fun TestScope.collectNotices(connection: DesktopConnection): List<ConnectionNotice> {
        val notices = mutableListOf<ConnectionNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { connection.notices.toList(notices) }
        return notices
    }
}
