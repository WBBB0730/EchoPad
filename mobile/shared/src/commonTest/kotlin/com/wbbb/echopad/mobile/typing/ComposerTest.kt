package com.wbbb.echopad.mobile.typing

import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.connection.FakeSession
import com.wbbb.echopad.mobile.connection.FakeTransport
import com.wbbb.echopad.mobile.connection.MemoryStore
import com.wbbb.echopad.mobile.pairing.PairedDesktop
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.ComposeText
import com.wbbb.echopad.mobile.protocol.KeyPress
import com.wbbb.echopad.mobile.protocol.ServerMessage
import com.wbbb.echopad.mobile.protocol.TypeText
import com.wbbb.echopad.mobile.protocol.Welcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerTest {
    private val transport = FakeTransport()

    private suspend fun TestScope.composer(): Pair<Composer, FakeSession> {
        val store = MemoryStore(PairedDesktop("192.168.1.2", 47321, "device-key", "Mac"))
        val connection = DesktopConnection(store, "Phone", transport, backgroundScope)
        val composer = Composer(connection, backgroundScope)
        return composer to transport.sessions.receive()
    }

    private suspend fun TestScope.connect(session: FakeSession) {
        session.hello()
        session.reply(ServerMessage(welcome = Welcome(desktop_name = "Mac")))
        runCurrent()
    }

    @Test
    fun voiceTextFromComposeModeIsAppendedInOrder() = runTest {
        val (composer, session) = composer()
        connect(session)

        session.reply(ServerMessage(compose_text = ComposeText(session_id = 1, text = "第一句。")))
        session.reply(ServerMessage(compose_text = ComposeText(session_id = 1, text = "第二句。")))
        runCurrent()

        assertEquals("第一句。第二句。", composer.text.value)
    }

    @Test
    fun sendingKeepsTheTextWhileDisconnectedAndClearsItAfterSending() = runTest {
        val (composer, session) = composer()
        composer.edit("你好 world")
        assertFalse(composer.send())
        assertEquals("你好 world", composer.text.value)

        connect(session)
        assertTrue(composer.send())
        assertEquals("", composer.text.value)
        runCurrent()
        assertEquals(TypeText(text = "你好 world"), ClientMessage.ADAPTER.decode(session.sent.receive()).type_text)
    }

    @Test
    fun specialKeysAreSentImmediately() = runTest {
        val (composer, session) = composer()
        connect(session)

        composer.press(KeyPress.Key.KEY_ENTER)
        runCurrent()

        assertEquals(KeyPress(key = KeyPress.Key.KEY_ENTER), ClientMessage.ADAPTER.decode(session.sent.receive()).key_press)
    }
}
