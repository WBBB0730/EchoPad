package com.wbbb.echopad.mobile.dictation

import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.connection.FakeFinder
import com.wbbb.echopad.mobile.connection.FakeSession
import com.wbbb.echopad.mobile.connection.FakeTransport
import com.wbbb.echopad.mobile.connection.MemoryStore
import com.wbbb.echopad.mobile.connection.pairedDesktop
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.DictationFailed
import com.wbbb.echopad.mobile.protocol.DictationFinished
import com.wbbb.echopad.mobile.protocol.ServerMessage
import com.wbbb.echopad.mobile.protocol.Welcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DictationControllerTest {
    private val transport = FakeTransport()

    /** 已连接的说话按钮，电脑端这一侧，以及手机端提示过的消息。 */
    private suspend fun TestScope.connectedDictation(): Triple<DictationController, FakeSession, List<String>> {
        val store = MemoryStore(pairedDesktop())
        val connection = DesktopConnection(store, "Phone", transport, backgroundScope, FakeFinder(), MutableSharedFlow())
        val session = transport.sessions.receive()
        session.hello()
        session.reply(ServerMessage(welcome = Welcome(desktop_name = "Mac")))
        runCurrent()
        val dictation = DictationController(connection, backgroundScope)
        val notices = mutableListOf<String>()
        backgroundScope.launch { dictation.failures.toList(notices) }
        runCurrent()
        return Triple(dictation, session, notices)
    }

    private fun TestScope.sent(session: FakeSession): List<ClientMessage> {
        runCurrent()
        return generateSequence { session.sent.tryReceive().getOrNull() }.map(ClientMessage.ADAPTER::decode).toList()
    }

    /** 录一段音之后一直录着，直到松开或取消。 */
    private val holding = flow {
        emit(ByteArray(3200))
        awaitCancellation()
    }

    @Test
    fun cancellingTellsTheDesktopAndIgnoresTheOutcome() = runTest {
        val (dictation, session, notices) = connectedDictation()

        dictation.start(holding)
        runCurrent()
        assertTrue(dictation.speaking.value)
        dictation.cancel()
        val sent = sent(session)

        assertFalse(dictation.speaking.value)
        assertNotNull(sent.first().dictation_start)
        assertEquals(3200, sent[1].audio_chunk?.pcm?.size)
        assertEquals(1, sent.last().dictation_cancel?.session_id)
        assertTrue(sent.none { it.dictation_stop != null })

        session.reply(ServerMessage(dictation_failed = DictationFailed(session_id = 1, message = "识别失败")))
        runCurrent()
        assertEquals(emptyList(), notices)
    }

    @Test
    fun failedCleanupIsReportedAfterTheTextIsCommitted() = runTest {
        val (dictation, session, notices) = connectedDictation()

        dictation.start(holding)
        runCurrent()
        dictation.stop()
        assertEquals(1, sent(session).last().dictation_stop?.session_id)

        session.reply(ServerMessage(dictation_finished = DictationFinished(session_id = 1, cleanup_failure = "整理超时")))
        runCurrent()
        assertEquals(listOf("整理失败，已输入识别原文：整理超时"), notices)
    }

    @Test
    fun successfulDictationNeedsNoNotice() = runTest {
        val (dictation, session, notices) = connectedDictation()

        dictation.start(holding)
        runCurrent()
        dictation.stop()
        runCurrent()
        session.reply(ServerMessage(dictation_finished = DictationFinished(session_id = 1)))
        runCurrent()

        assertEquals(emptyList(), notices)
    }
}
