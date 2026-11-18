package com.wbbb.echopad.mobile.clipboard

import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.connection.FakeFinder
import com.wbbb.echopad.mobile.connection.FakeSession
import com.wbbb.echopad.mobile.connection.FakeTransport
import com.wbbb.echopad.mobile.connection.MemoryStore
import com.wbbb.echopad.mobile.connection.pairedDesktop
import com.wbbb.echopad.mobile.pairing.PairedDesktop
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.ClipboardText
import com.wbbb.echopad.mobile.protocol.ServerMessage
import com.wbbb.echopad.mobile.protocol.Welcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardSyncTest {
    private val home = pairedDesktop("desk-1", "Mac", "192.168.1.2")
    private val office = pairedDesktop("desk-2", "Office", "192.168.1.3")
    private val transport = FakeTransport()
    private val settings = MutableStateFlow<ClipboardSettings?>(ClipboardSettings())
    private val clipboard = FakeClipboard()

    /** 手机的时钟，Unix 毫秒。 */
    private var clock = 10_000L

    private class FakeClipboard : PhoneClipboard {
        /** 剪贴板上的文字。 */
        var content: String? = null

        /** 回到 EchoPad 时，剪贴板上有没有 EchoPad 还没看过的新内容。 */
        var hasNewContent = false

        /** 读过几次剪贴板。 */
        var reads = 0

        val written = mutableListOf<String>()

        override fun read(): String? {
            reads++
            return content
        }

        override fun readIfNew(): String? {
            reads++
            return content.takeIf { hasNewContent }.also { hasNewContent = false }
        }

        override fun write(text: String) {
            content = text
            written += text
        }

        /** 用户在别的应用里复制了 [text]。 */
        fun copy(text: String) {
            content = text
            hasNewContent = true
        }
    }

    private fun TestScope.start(vararg desktops: PairedDesktop): ClipboardSync {
        val connection = DesktopConnection(
            MemoryStore(*desktops),
            "Phone",
            transport,
            backgroundScope,
            FakeFinder(),
            MutableSharedFlow(),
        )
        val sync = ClipboardSync(connection, settings, clipboard, backgroundScope, now = { clock })
        runCurrent()
        return sync
    }

    /** 电脑端验证通过，回复欢迎。 */
    private suspend fun TestScope.welcome(name: String): FakeSession {
        val session = transport.sessions.receive()
        session.hello()
        session.reply(ServerMessage(welcome = Welcome(desktop_name = name)))
        runCurrent()
        return session
    }

    /** 手机端发给这台电脑端、还没看过的消息。 */
    private fun TestScope.sent(session: FakeSession): List<ClientMessage> {
        runCurrent()
        return generateSequence { session.sent.tryReceive().getOrNull() }.map(ClientMessage.ADAPTER::decode).toList()
    }

    private fun clipboard(text: String, updatedAt: Long) = ClipboardText(text = text, updated_at = updatedAt)

    private fun message(clipboard: ClipboardText) = ClientMessage(clipboard_text = clipboard)


    private fun FakeSession.send(clipboard: ClipboardText) = reply(ServerMessage(clipboard_text = clipboard))

    @Test
    fun syncingTheClipboardSendsItToEveryOnlineDesktop() = runTest {
        val sync = start(home, office)
        val homeSession = welcome("Mac")
        val officeSession = welcome("Office")

        clock = 20_000
        clipboard.content = "hello"
        assertEquals(SyncResult.Sent(listOf("Mac", "Office")), sync.syncClipboard())
        assertEquals(listOf(message(clipboard("hello", 20_000))), sent(homeSession))
        assertEquals(listOf(message(clipboard("hello", 20_000))), sent(officeSession))
    }

    @Test
    fun syncingSelectedTextAlsoCopiesItOnThePhone() = runTest {
        val sync = start(home)
        val session = welcome("Mac")
        sent(session)

        assertEquals(SyncResult.Sent(listOf("Mac")), sync.syncText("selected"))
        assertEquals(listOf("selected"), clipboard.written)
        assertEquals(listOf("selected"), sent(session).map { it.clipboard_text?.text })
    }

    @Test
    fun aCopyMadeWhileNoDesktopIsOnlineIsNotSentLater() = runTest {
        val sync = start(home)
        clipboard.content = "hello"
        assertEquals(SyncResult.NoDesktopOnline, sync.syncClipboard())

        // 断线期间的复制，连上后不补发。
        val session = welcome("Mac")
        assertEquals(emptyList(), sent(session))
    }

    @Test
    fun newerClipboardFromADesktopIsWrittenAndPassedToTheOtherDesktops() = runTest {
        start(home, office)
        val homeSession = welcome("Mac")
        val officeSession = welcome("Office")
        sent(homeSession)
        sent(officeSession)

        homeSession.send(clipboard("from the mac", 30_000))
        runCurrent()
        assertEquals(listOf("from the mac"), clipboard.written)
        assertEquals(listOf(message(clipboard("from the mac", 30_000))), sent(officeSession))
        assertEquals(emptyList(), sent(homeSession))
    }

    @Test
    fun clipboardThatIsNotNewerStopsHere() = runTest {
        start(home, office)
        val homeSession = welcome("Mac")
        val officeSession = welcome("Office")
        homeSession.send(clipboard("from the mac", 30_000))
        runCurrent()
        sent(homeSession)
        sent(officeSession)

        // 同一条内容经另一台电脑转了一圈回来，以及更早的内容，都不再写入、也不再转发。
        officeSession.send(clipboard("from the mac", 30_000))
        officeSession.send(clipboard("older", 25_000))
        runCurrent()
        assertEquals(listOf("from the mac"), clipboard.written)
        assertEquals(emptyList(), sent(homeSession))
        assertEquals(emptyList(), sent(officeSession))
    }

    @Test
    fun clipboardCopiedOnTwoDesktopsAtOnceSettlesOnTheNewerOne() = runTest {
        start(home, office)
        val homeSession = welcome("Mac")
        val officeSession = welcome("Office")
        sent(homeSession)
        sent(officeSession)

        officeSession.send(clipboard("older", 25_000))
        homeSession.send(clipboard("newer", 30_000))
        runCurrent()
        assertEquals("newer", clipboard.written.last())
        // 两台电脑各自收到对方的内容后，按同一条规则都停在较新的那条上。
        assertEquals(listOf(message(clipboard("older", 25_000))), sent(homeSession))
        assertEquals(listOf(message(clipboard("newer", 30_000))), sent(officeSession))
    }

    @Test
    fun aNewCopyIsNewerThanAnythingSeenEvenWithASlowClock() = runTest {
        val sync = start(home)
        val session = welcome("Mac")
        // 电脑的时钟比手机快。
        session.send(clipboard("from a fast clock", 50_000))
        runCurrent()
        sent(session)

        clock = 20_000
        sync.syncText("copied on the phone")
        assertEquals(listOf(message(clipboard("copied on the phone", 50_001))), sent(session))
    }

    @Test
    fun withReceivingOffClipboardIsNotWrittenButStillPassedOn() = runTest {
        settings.value = ClipboardSettings(receive = false)
        start(home, office)
        val homeSession = welcome("Mac")
        val officeSession = welcome("Office")

        homeSession.send(clipboard("from the mac", 30_000))
        runCurrent()
        assertEquals(emptyList(), clipboard.written)
        assertEquals(listOf(message(clipboard("from the mac", 30_000))), sent(officeSession))
        // 同一条内容转了一圈回来，照样停下。
        officeSession.send(clipboard("from the mac", 30_000))
        runCurrent()
        assertEquals(emptyList(), sent(homeSession))
    }

    @Test
    fun withAutoSendOffOnlySyncingOnPurposeSends() = runTest {
        settings.value = ClipboardSettings(autoSend = false)
        val sync = start(home)
        val session = welcome("Mac")
        clipboard.copy("copied on the phone")

        // 回到 EchoPad 时不读剪贴板。
        sync.syncOnReturn()
        assertEquals(0, clipboard.reads)
        assertEquals(emptyList(), sent(session))

        // 用户主动同步照常发出。
        clock = 40_000
        assertEquals(SyncResult.Sent(listOf("Mac")), sync.syncClipboard())
        assertEquals(listOf(message(clipboard("copied on the phone", 40_000))), sent(session))
    }

    @Test
    fun onlyNewChangedTextIsSyncedOnReturnButSyncingOnPurposeAlwaysSends() = runTest {
        val sync = start(home)
        val session = welcome("Mac")
        session.send(clipboard("from the mac", 30_000))
        runCurrent()
        sent(session)

        // 写进手机剪贴板以后，回到 EchoPad 时读到的就是它。
        clipboard.hasNewContent = true
        sync.syncOnReturn()
        assertEquals(emptyList(), sent(session))

        clock = 40_000
        clipboard.copy("copied on the phone")
        sync.syncOnReturn()
        assertEquals(listOf(message(clipboard("copied on the phone", 40_000))), sent(session))
        sync.syncOnReturn()
        assertEquals(emptyList(), sent(session))
        assertEquals(SyncResult.Sent(listOf("Mac")), sync.syncClipboard())
        assertEquals(listOf(message(clipboard("copied on the phone", 40_001))), sent(session))
    }

    @Test
    fun syncingOnReturnRightAfterStartingWaitsForTheSettings() = runTest {
        settings.value = null
        val sync = start(home)
        clipboard.copy("hello")
        val returned = async { sync.syncOnReturn() }
        runCurrent()
        assertFalse(returned.isCompleted)

        settings.value = ClipboardSettings(autoSend = false)
        returned.await()
        assertEquals(0, clipboard.reads)
    }

    @Test
    fun anEmptyClipboardOrTooLongTextIsNotSynced() = runTest {
        val sync = start(home)
        val session = welcome("Mac")
        sent(session)
        assertEquals(SyncResult.NoText, sync.syncClipboard())
        clipboard.content = "x".repeat(ClipboardSync.MAX_TEXT_BYTES + 1)
        assertEquals(SyncResult.TooLong, sync.syncClipboard())
        assertEquals(emptyList(), sent(session))
    }
}
