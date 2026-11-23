package com.wbbb.echopad.mobile.files

import com.wbbb.echopad.mobile.SyncResult
import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.connection.FakeFinder
import com.wbbb.echopad.mobile.connection.FakeSession
import com.wbbb.echopad.mobile.connection.FakeTransport
import com.wbbb.echopad.mobile.connection.MemoryStore
import com.wbbb.echopad.mobile.connection.pairedDesktop
import com.wbbb.echopad.mobile.pairing.PairedDesktop
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.FileOffer
import com.wbbb.echopad.mobile.protocol.FileOfferResult
import com.wbbb.echopad.mobile.protocol.OfferedFile
import com.wbbb.echopad.mobile.protocol.ServerMessage
import com.wbbb.echopad.mobile.protocol.Welcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileTransfersTest {
    private val home = pairedDesktop("desk-1", "Mac", "192.168.1.2")
    private val office = pairedDesktop("desk-2", "Office", "192.168.1.3")
    private val transport = FakeTransport()
    private val client = FakeFileClient()
    private val incoming = FakeIncomingFiles()
    private val results = mutableListOf<TransferResult>()

    private fun TestScope.start(vararg desktops: PairedDesktop, canReceive: Boolean = true): FileTransfers {
        val connection = DesktopConnection(MemoryStore(*desktops), "Phone", transport, backgroundScope, FakeFinder(), MutableSharedFlow())
        val transfers = FileTransfers(connection, client, incoming.takeIf { canReceive }, backgroundScope)
        backgroundScope.launch { transfers.results.collect(results::add) }
        runCurrent()
        return transfers
    }

    /** 电脑端验证通过，回复欢迎。 */
    private suspend fun TestScope.welcome(name: String): FakeSession {
        val session = transport.sessions.receive()
        session.hello()
        session.reply(ServerMessage(welcome = Welcome(desktop_name = name)))
        runCurrent()
        return session
    }

    private fun TestScope.nextRequest(): FakeFileClient.Request {
        runCurrent()
        return checkNotNull(client.requests.tryReceive().getOrNull()) { "no request was made" }
    }

    /** 手机端告诉这台电脑端的文件清单结果。 */
    private fun TestScope.reports(session: FakeSession): List<FileOfferResult> {
        runCurrent()
        return generateSequence { session.sent.tryReceive().getOrNull() }
            .mapNotNull { ClientMessage.ADAPTER.decode(it).file_offer_result }
            .toList()
    }

    private fun result(outcome: FileOfferResult.Outcome, saved: Int) = FileOfferResult("offer-1", outcome, saved)

    private fun TestScope.assertNoRequest() {
        runCurrent()
        assertTrue(client.requests.tryReceive().isFailure, "unexpected request")
    }

    @Test
    fun batchesAreSentOneAtATimeInOrder() = runTest {
        val transfers = start(home)
        welcome("Mac")
        val first = listOf(outgoingFile("a.txt", "hello"), outgoingFile("b.txt", "world!"))
        val second = listOf(outgoingFile("c.txt", "later"))
        transfers.sendToCurrentDesktop(first)
        transfers.sendToCurrentDesktop(second)

        val upload = nextRequest()
        assertEquals(home, upload.desktop)
        assertEquals(listOf("a.txt" to "hello", "b.txt" to "world!"), upload.files)
        assertEquals(TransferProgress(sending = true, "Mac", index = 1, count = 2, transferred = 11, total = 11), transfers.progress.value)
        assertNoRequest()

        upload.succeed()
        assertEquals(listOf("c.txt" to "later"), nextRequest().files)
        assertEquals(listOf<TransferResult>(TransferResult.Sent(2, "Mac")), results)
        assertTrue(first.all { it.closed })
    }

    @Test
    fun filesGoToTheCurrentDesktopOnlyWhenItIsOnline() = runTest {
        val transfers = start(home, office)
        // 当前电脑是 Mac，先只有 Office 在线。
        val homeSession = transport.sessions.receive()
        welcome("Office")
        val offline = outgoingFile("photo.jpg", "jpeg")
        assertEquals(SyncResult.DesktopOffline("Mac"), transfers.sendToCurrentDesktop(listOf(offline)))
        assertTrue(offline.closed)
        assertNoRequest()

        homeSession.hello()
        homeSession.reply(ServerMessage(welcome = Welcome(desktop_name = "Mac")))
        runCurrent()
        assertEquals(SyncResult.Sending("Mac"), transfers.sendToCurrentDesktop(listOf(outgoingFile("photo.jpg", "jpeg"))))
        assertEquals(home, nextRequest().desktop)
    }

    @Test
    fun cancellingStopsOnlyTheBatchInProgress() = runTest {
        val transfers = start(home)
        welcome("Mac")
        val first = listOf(outgoingFile("a.txt", "hello"))
        transfers.sendToCurrentDesktop(first)
        transfers.sendToCurrentDesktop(listOf(outgoingFile("b.txt", "world")))
        nextRequest()

        transfers.cancel()
        assertEquals(listOf("b.txt" to "world"), nextRequest().files)
        assertEquals(listOf<TransferResult>(TransferResult.Cancelled), results)
        assertTrue(first.single().closed)
    }

    @Test
    fun aBatchFailsWhenTheRequestFailsOrTheDesktopDisconnects() = runTest {
        val transfers = start(home)
        val session = welcome("Mac")
        transfers.sendToCurrentDesktop(listOf(outgoingFile("a.txt", "hello")))
        nextRequest().fail()
        runCurrent()

        transfers.sendToCurrentDesktop(listOf(outgoingFile("b.txt", "world")))
        nextRequest()
        session.drop()
        runCurrent()
        assertEquals(listOf<TransferResult>(TransferResult.SendFailed("Mac"), TransferResult.SendFailed("Mac")), results)
        assertNull(transfers.progress.value)
    }

    @Test
    fun offeredFilesFromAnyDesktopAreDownloadedOneByOne() = runTest {
        val transfers = start(home, office)
        welcome("Mac")
        val officeSession = welcome("Office")
        client.offered += mapOf(("offer-1" to 0) to "png", ("offer-1" to 1) to "pdf!")
        officeSession.reply(ServerMessage(file_offer = FileOffer("offer-1", listOf(OfferedFile("a.png", 3), OfferedFile("b.pdf", 4)))))

        val first = nextRequest()
        assertEquals(office, first.desktop)
        assertEquals(TransferProgress(sending = false, "Office", index = 0, count = 2, transferred = 0, total = 7), transfers.progress.value)
        first.succeed()
        nextRequest().succeed()
        runCurrent()

        assertEquals(listOf("a.png" to "png", "b.pdf" to "pdf!"), incoming.files.map { it.name to it.content })
        assertTrue(incoming.files.all { it.state == "published" })
        val saved = listOf(SavedFile("content://saved/0", SavedFolder.Album), SavedFile("content://saved/1", SavedFolder.Downloads))
        assertEquals(listOf<TransferResult>(TransferResult.Saved(saved)), results)
        assertEquals(listOf(result(FileOfferResult.Outcome.OUTCOME_SAVED, 2)), reports(officeSession))
    }

    @Test
    fun aFailedDownloadKeepsTheFinishedFilesAndDeletesThePartialOne() = runTest {
        start(home)
        val session = welcome("Mac")
        client.offered += mapOf(("offer-1" to 0) to "png", ("offer-1" to 1) to "pdf!")
        session.reply(ServerMessage(file_offer = FileOffer("offer-1", listOf(OfferedFile("a.png", 3), OfferedFile("b.pdf", 4)))))

        nextRequest().succeed()
        nextRequest().fail()
        runCurrent()

        assertEquals(listOf("published", "discarded"), incoming.files.map { it.state })
        assertEquals(listOf<TransferResult>(TransferResult.ReceiveFailed(listOf(SavedFile("content://saved/0", SavedFolder.Album)))), results)
        assertEquals(listOf(result(FileOfferResult.Outcome.OUTCOME_FAILED, 1)), reports(session))
    }

    @Test
    fun cancellingADownloadTellsTheDesktopWithoutAResultToShow() = runTest {
        val transfers = start(home)
        val session = welcome("Mac")
        client.offered += mapOf(("offer-1" to 0) to "png")
        session.reply(ServerMessage(file_offer = FileOffer("offer-1", listOf(OfferedFile("a.png", 3)))))
        nextRequest()

        transfers.cancel()
        runCurrent()
        assertEquals(listOf("discarded"), incoming.files.map { it.state })
        assertEquals(listOf<TransferResult>(TransferResult.Cancelled), results)
        assertEquals(listOf(result(FileOfferResult.Outcome.OUTCOME_CANCELLED, 0)), reports(session))
    }

    @Test
    fun aPhoneThatCannotReceiveFilesSaysSo() = runTest {
        start(home, canReceive = false)
        val session = welcome("Mac")
        session.reply(ServerMessage(file_offer = FileOffer("offer-1", listOf(OfferedFile("a.png", 3)))))

        assertNoRequest()
        assertEquals(listOf<TransferResult>(TransferResult.CannotReceive), results)
        assertEquals(listOf(result(FileOfferResult.Outcome.OUTCOME_UNSUPPORTED, 0)), reports(session))
    }

    @Test
    fun savedFilesAreGroupedByWhereTheyWent() {
        val photos = listOf("content://photo/1", "content://photo/2").map { SavedFile(it, SavedFolder.Album) }
        val document = SavedFile("content://doc/1", SavedFolder.Downloads)
        assertEquals(
            listOf(
                SavedGroup(SavedFolder.Album, listOf("content://photo/1", "content://photo/2"), "已保存 2 个文件到相册"),
                SavedGroup(SavedFolder.Downloads, listOf("content://doc/1"), "已保存 1 个文件到下载"),
            ),
            TransferResult.Saved(photos + document).savedGroups,
        )
        assertEquals("已保存 3 个文件", TransferResult.Saved(photos + document).message)
        assertEquals(listOf("接收中断，已保存 1 个文件到下载"), TransferResult.ReceiveFailed(listOf(document)).savedGroups.map { it.message })
        assertEquals(emptyList(), TransferResult.ReceiveFailed(emptyList()).savedGroups)
        assertEquals(emptyList(), TransferResult.Sent(2, "Mac").savedGroups)
    }

    @Test
    fun progressAndQuestionsReadLikeTheDesign() {
        assertEquals("第 2 个，共 3 个 · 4.1 MB / 12.4 MB", TransferProgress(true, "Mac", 1, 3, 4_100_000, 12_400_000).detail)
        assertEquals("512 字节", TransferProgress(false, "Mac", 0, 1, 512, null).detail)
        val pending = PendingFiles(listOf(outgoingFile("a", "x".repeat(1_500)), OutgoingFile("b", null, TestSource(""))), "Mac")
        assertEquals("把 2 个文件发送到 Mac？", pending.question)
        assertEquals("把 1 个文件（1.5 KB）发送到 Mac？", PendingFiles(pending.files.take(1), "Mac").question)
    }
}
