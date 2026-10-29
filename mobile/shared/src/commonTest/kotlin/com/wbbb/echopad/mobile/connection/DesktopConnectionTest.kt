package com.wbbb.echopad.mobile.connection

import com.wbbb.echopad.mobile.pairing.PairedDesktop
import com.wbbb.echopad.mobile.pairing.PairedDesktopStore
import com.wbbb.echopad.mobile.pairing.PairingLink
import com.wbbb.echopad.mobile.pairing.SavedPairing
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.PointerMove
import com.wbbb.echopad.mobile.protocol.Rejected
import com.wbbb.echopad.mobile.protocol.ServerMessage
import com.wbbb.echopad.mobile.protocol.SwitchRequest
import com.wbbb.echopad.mobile.protocol.Welcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopConnectionTest {
    private val home = pairedDesktop("desk-1", "Mac", "192.168.1.2")
    private val office = pairedDesktop("desk-2", "Office", "192.168.1.3")
    private val newAddress = DesktopAddress("192.168.1.20", 47321)
    private val transport = FakeTransport()
    private val finder = FakeFinder()
    private val networkChanges = MutableSharedFlow<Unit>()
    private val move = ClientMessage(pointer_move = PointerMove(dx = 1f, dy = 2f))

    @Test
    fun pairingSavesTheDeviceKeyAndCertificateAndControlsTheNewDesktop() = runTest {
        val store = MemoryStore()
        val connection = connect(store)
        runCurrent()
        assertEquals(ConnectionState.Unpaired, connection.state.value)

        connection.pair(PairingLink("192.168.1.9", 47321, "token", "desk-9", "fingerprint-9"))
        val session = transport.sessions.receive()
        assertEquals("192.168.1.9", session.host)
        assertEquals("fingerprint-9", session.fingerprint)
        val hello = session.hello()
        assertEquals("token", hello.pairing_token)
        assertEquals("phone-1", hello.phone_id)

        session.reply(ServerMessage(welcome = Welcome(desktop_name = "Office Mac", device_key = "new-key", desktop_id = "desk-9")))
        runCurrent()
        assertEquals(ConnectionState.Connected("Office Mac"), connection.state.value)
        assertEquals(listOf(PairedDesktop("desk-9", "Office Mac", "192.168.1.9", 47321, "new-key", "fingerprint-9")), store.desktops)
        assertEquals("desk-9", store.activeId)
    }

    @Test
    fun aPairingRequestDuringStartupIsNotOverriddenBySavedState() = runTest {
        val loaded = CompletableDeferred<Unit>()
        val memory = MemoryStore(home)
        val store = object : PairedDesktopStore by memory {
            override suspend fun load(): SavedPairing {
                loaded.await()
                return memory.load()
            }
        }
        val connection = connect(store)
        runCurrent()

        // 例如用系统相机扫码冷启动 App：配对请求比读取已保存的配对先到。
        connection.pair(PairingLink("192.168.1.9", 47321, "token", "desk-9", "fingerprint-9"))
        loaded.complete(Unit)
        runCurrent()

        assertEquals("desk-9", connection.activeId.value)
        val sessions = listOf(transport.sessions.receive(), transport.sessions.receive())
        val pairing = sessions.single { it.host == "192.168.1.9" }
        assertEquals("token", pairing.hello().pairing_token)
    }

    @Test
    fun everyPairedDesktopConnectsAndOnlyTheActiveOneReceivesInput() = runTest {
        val connection = connect(MemoryStore(home, office))
        val homeSession = welcome(transport.sessions.receive(), "Mac")
        val officeSession = welcome(transport.sessions.receive(), "Office")
        assertEquals(listOf(true, true), connection.desktops.value.map { it.online })

        assertTrue(connection.send(move))
        runCurrent()
        assertEquals(move, ClientMessage.ADAPTER.decode(homeSession.sent.receive()))
        assertTrue(officeSession.sent.tryReceive().isFailure)
    }

    @Test
    fun switchingSendsInputToTheNewDesktopAndReconnectsThePreviousOne() = runTest {
        val store = MemoryStore(home, office)
        val connection = connect(store)
        val homeSession = welcome(transport.sessions.receive(), "Mac")
        val officeSession = welcome(transport.sessions.receive(), "Office")

        connection.switchTo(office.id)
        runCurrent()

        assertEquals(office.id, connection.activeId.value)
        assertEquals(office.id, store.activeId)
        assertEquals(ConnectionState.Connected("Office"), connection.state.value)
        assertTrue(connection.send(move))
        runCurrent()
        assertEquals(move, ClientMessage.ADAPTER.decode(officeSession.sent.receive()))
        // 原来那台重新连接一次，让电脑端收拾这部手机留下的残局。
        assertTrue(homeSession.sent.tryReceive().isFailure)
        assertEquals(home.host, transport.sessions.receive().host)
    }

    @Test
    fun aDesktopCanAskThePhoneToSwitchToIt() = runTest {
        val connection = connect(MemoryStore(home, office))
        val notices = collectNotices(connection)
        welcome(transport.sessions.receive(), "Mac")
        val officeSession = welcome(transport.sessions.receive(), "Office")

        officeSession.reply(ServerMessage(switch_request = SwitchRequest()))
        runCurrent()

        assertEquals(office.id, connection.activeId.value)
        assertEquals(listOf<ConnectionNotice>(ConnectionNotice.SwitchedByDesktop("Office")), notices)
    }

    @Test
    fun reconnectsWithTheDeviceKeyAfterTheConnectionDrops() = runTest {
        val connection = connect(MemoryStore(home))
        val first = welcome(transport.sessions.receive(), "Mac")
        assertEquals(ConnectionState.Connected("Mac"), connection.state.value)

        first.drop()
        runCurrent()
        assertEquals(ConnectionState.Connecting, connection.state.value)
        advanceTimeBy(DesktopConnection.reconnectDelay(0).inWholeMilliseconds - 1)
        assertTrue(transport.sessions.tryReceive().isFailure)

        advanceTimeBy(2)
        val second = transport.sessions.receive()
        assertEquals(home.fingerprint, second.fingerprint)
        assertEquals(home.deviceKey, second.hello().device_key)
    }

    @Test
    fun helloCarriesTheProtocolVersion() = runTest {
        connect(MemoryStore(home))
        assertEquals(DesktopConnection.PROTOCOL_VERSION, transport.sessions.receive().hello().protocol_version)
    }

    @Test
    fun aVersionMismatchKeepsThePairingUntilTheOlderAppIsUpdated() = runTest {
        val store = MemoryStore(home)
        val connection = connect(store)
        val notices = collectNotices(connection)

        val first = transport.sessions.receive()
        first.hello()
        first.reply(versionMismatch(DesktopConnection.PROTOCOL_VERSION - 1))
        runCurrent()
        assertEquals(ConnectionState.VersionMismatch(OutdatedApp.Desktop), connection.state.value)
        assertEquals(listOf(home), store.desktops)
        assertEquals(emptyList(), notices)

        // 重连期间仍显示该更新哪一端；电脑端更新之后照常连上。
        advanceTimeBy(DesktopConnection.reconnectDelay(0).inWholeMilliseconds + 1)
        val second = transport.sessions.receive()
        assertEquals(ConnectionState.VersionMismatch(OutdatedApp.Desktop), connection.state.value)
        welcome(second, "Mac")
        assertEquals(ConnectionState.Connected("Mac"), connection.state.value)
    }

    @Test
    fun aNewerDesktopAsksToUpdateThePhone() = runTest {
        val connection = connect(MemoryStore(home))
        val session = transport.sessions.receive()
        session.hello()
        session.reply(versionMismatch(DesktopConnection.PROTOCOL_VERSION + 1))
        runCurrent()
        assertEquals(ConnectionState.VersionMismatch(OutdatedApp.Mobile), connection.state.value)
    }

    @Test
    fun messagesAreDroppedWhileDisconnected() = runTest {
        val connection = connect(MemoryStore(home))
        val session = transport.sessions.receive()
        assertFalse(connection.send(move))

        welcome(session, "Mac")
        assertTrue(connection.send(move))
        runCurrent()
        assertEquals(move, ClientMessage.ADAPTER.decode(session.sent.receive()))
    }

    @Test
    fun aDesktopThatRevokesThePairingIsRemoved() = runTest {
        val store = MemoryStore(home, office)
        val connection = connect(store)
        val notices = collectNotices(connection)

        val homeSession = transport.sessions.receive()
        homeSession.hello()
        homeSession.reply(ServerMessage(rejected = Rejected(reason = Rejected.Reason.REASON_INVALID_CREDENTIAL)))
        runCurrent()

        assertEquals(listOf(office), store.desktops)
        assertEquals(office.id, connection.activeId.value)
        assertEquals(listOf<ConnectionNotice>(ConnectionNotice.PairingRevoked("Mac")), notices)
    }

    @Test
    fun theLastRevokedDesktopLeavesThePhoneUnpaired() = runTest {
        val store = MemoryStore(home)
        val connection = connect(store)

        val session = transport.sessions.receive()
        session.hello()
        session.reply(ServerMessage(rejected = Rejected(reason = Rejected.Reason.REASON_INVALID_CREDENTIAL)))
        runCurrent()

        assertEquals(ConnectionState.Unpaired, connection.state.value)
        assertEquals(emptyList(), store.desktops)
    }

    @Test
    fun unpairingADesktopRemovesItAndSwitchesToAnother() = runTest {
        val store = MemoryStore(home, office)
        val connection = connect(store)
        welcome(transport.sessions.receive(), "Mac")
        welcome(transport.sessions.receive(), "Office")

        connection.unpair(home.id)
        runCurrent()

        assertEquals(listOf(office), store.desktops)
        assertEquals(office.id, connection.activeId.value)
        assertEquals(listOf("Office"), connection.desktops.value.map { it.name })
    }

    @Test
    fun aRejectedPairingCodeKeepsTheExistingPairing() = runTest {
        val store = MemoryStore(home)
        val connection = connect(store)
        val notices = collectNotices(connection)
        transport.sessions.receive()

        connection.pair(PairingLink("192.168.1.9", 47321, "stale-token", "desk-9", "fingerprint-9"))
        val pairing = transport.sessions.receive()
        pairing.hello()
        pairing.reply(ServerMessage(rejected = Rejected(reason = Rejected.Reason.REASON_INVALID_CREDENTIAL)))
        runCurrent()

        assertEquals(listOf<ConnectionNotice>(ConnectionNotice.PairingCodeRejected), notices)
        assertEquals(listOf(home), store.desktops)
        assertEquals(home.id, connection.activeId.value)
    }

    @Test
    fun aDesktopThatMovedIsReachedAtItsNewAddressWithoutWaiting() = runTest {
        val store = MemoryStore(home)
        connect(store)
        transport.sessions.receive().drop()
        runCurrent()
        assertTrue(transport.sessions.tryReceive().isFailure)

        finder.advertise(home.id, newAddress)
        runCurrent()

        // 不等重连间隔，直接连新地址；仍然只认原来那张证书；连上后保存新地址。
        val moved = assertNotNull(transport.sessions.tryReceive().getOrNull())
        assertEquals(newAddress.host, moved.host)
        assertEquals(home.fingerprint, moved.fingerprint)
        welcome(moved, "Mac")
        assertEquals(newAddress.host, store.desktops.single().host)
    }

    @Test
    fun theLocalNetworkIsOnlySearchedWhileDisconnected() = runTest {
        val connection = connect(MemoryStore(home))
        val session = transport.sessions.receive()
        runCurrent()
        assertEquals(1, finder.searching)

        welcome(session, "Mac")
        assertEquals(0, finder.searching)
        // 连着的时候局域网里的变化不影响连接，断线靠心跳发现。
        finder.advertise(home.id, newAddress)
        runCurrent()
        assertFalse(session.closed)
        assertTrue(transport.sessions.tryReceive().isFailure)
        assertEquals(ConnectionState.Connected("Mac"), connection.state.value)

        // 断线后重新查找，立即连广播的新地址。
        session.drop()
        runCurrent()
        assertEquals(1, finder.searching)
        assertEquals(newAddress.host, transport.sessions.tryReceive().getOrNull()?.host)
    }

    @Test
    fun theDesktopIsLookedUpAgainAfterEachDisconnect() = runTest {
        connect(MemoryStore(home))
        transport.sessions.receive().drop()
        runCurrent()
        // 一直没连上时，接着用原来的查找。
        assertEquals(1, finder.lookups)

        welcome(transport.sessions.receive(), "Mac").drop()
        runCurrent()

        assertEquals(2, finder.lookups)
    }

    @Test
    fun aNetworkChangeRestartsAConnectionAttemptInProgress() = runTest {
        connect(MemoryStore(home))
        // 例如刚切网时手机先走了流量，这次连接连不上电脑，要等到超时才会放弃。
        val stuck = transport.sessions.receive()
        stuck.hello()

        networkChanges.emit(Unit)
        runCurrent()

        assertTrue(stuck.closed)
        val retry = assertNotNull(transport.sessions.tryReceive().getOrNull())
        assertEquals(home.deviceKey, retry.hello().device_key)
    }

    @Test
    fun aNetworkChangeLeavesAConnectedDesktopAlone() = runTest {
        val connection = connect(MemoryStore(home))
        val session = welcome(transport.sessions.receive(), "Mac")

        networkChanges.emit(Unit)
        runCurrent()

        assertFalse(session.closed)
        assertTrue(transport.sessions.tryReceive().isFailure)
        assertEquals(ConnectionState.Connected("Mac"), connection.state.value)
    }

    @Test
    fun aNetworkChangeStartsTheReconnectDelaysOver() = runTest {
        connect(MemoryStore(home))
        // 连续失败三次，下次要等的时间已经拉长了。
        repeat(3) { transport.sessions.receive().drop() }
        runCurrent()

        networkChanges.emit(Unit)
        runCurrent()
        assertNotNull(transport.sessions.tryReceive().getOrNull()).drop()
        runCurrent()

        advanceTimeBy(DesktopConnection.reconnectDelay(0).inWholeMilliseconds - 1)
        assertTrue(transport.sessions.tryReceive().isFailure)
        advanceTimeBy(2)
        assertNotNull(transport.sessions.tryReceive().getOrNull())
    }

    @Test
    fun aNetworkChangeReconnectsWithoutWaiting() = runTest {
        connect(MemoryStore(home))
        transport.sessions.receive().drop()
        runCurrent()
        assertTrue(transport.sessions.tryReceive().isFailure)

        networkChanges.emit(Unit)
        runCurrent()

        assertEquals(home.host, transport.sessions.tryReceive().getOrNull()?.host)
    }

    private fun TestScope.connect(store: PairedDesktopStore) =
        DesktopConnection(store, "Phone", transport, backgroundScope, finder, networkChanges)

    /** 电脑端验证通过，回复欢迎。 */
    private suspend fun TestScope.welcome(session: FakeSession, name: String): FakeSession {
        session.hello()
        session.reply(ServerMessage(welcome = Welcome(desktop_name = name)))
        runCurrent()
        return session
    }

    private fun versionMismatch(desktopVersion: Int) = ServerMessage(
        rejected = Rejected(reason = Rejected.Reason.REASON_VERSION_MISMATCH, protocol_version = desktopVersion),
    )

    private fun TestScope.collectNotices(connection: DesktopConnection): List<ConnectionNotice> {
        val notices = mutableListOf<ConnectionNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { connection.notices.toList(notices) }
        return notices
    }
}
