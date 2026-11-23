package com.wbbb.echopad.mobile.files

import co.touchlab.kermit.Logger
import com.wbbb.echopad.mobile.SyncResult
import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.protocol.ClientMessage
import com.wbbb.echopad.mobile.protocol.FileOffer
import com.wbbb.echopad.mobile.protocol.FileOfferResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource

/** 收到的文件存到哪里（docs/prd/send-files.md），由各平台实现：图片和视频进相册，其他文件进"下载"。 */
interface IncomingFiles {
    /** 开始存一个文件。 */
    fun create(name: String): IncomingFile
}

/** 收到的文件存放的地方，[label] 写在结果通知里。 */
enum class SavedFolder(val label: String) {
    Album("相册"),
    Downloads("下载"),
}

/** 存好的一个文件：[uri] 用来打开它。 */
data class SavedFile(val uri: String, val folder: SavedFolder)

/** 正在存的一个文件：写完之前不出现在相册和"下载"里。 */
interface IncomingFile {
    /** 打开这个文件用的标识。 */
    val uri: String
    val folder: SavedFolder
    val sink: RawSink

    /** 写完了，放出来。 */
    fun publish()

    /** 没写完，删掉。 */
    fun discard()
}

/** 正在传的一批：第 [index] 个（从 0 数），共 [count] 个；已传 [transferred] 字节，共 [total] 字节，有大小不明的文件时为 null。 */
data class TransferProgress(
    val sending: Boolean,
    val desktopName: String,
    val index: Int,
    val count: Int,
    val transferred: Long,
    val total: Long?,
) {
    /** 进度通知的标题（DESIGN.md）。 */
    val title: String get() = if (sending) "正在发送到 $desktopName" else "正在接收"

    /** 进度通知的正文，例如"第 2 个，共 3 个 · 4.1 MB / 12.4 MB"；只有一个文件时只写大小。 */
    val detail: String
        get() {
            val size = formatSize(transferred) + total?.let { " / ${formatSize(it)}" }.orEmpty()
            return if (count == 1) size else "第 ${index + 1} 个，共 $count 个 · $size"
        }
}

/** 一批文件传完、失败或取消的结果。 */
sealed interface TransferResult {
    data class Sent(val count: Int, val desktopName: String) : TransferResult

    data class SendFailed(val desktopName: String) : TransferResult

    data class Saved(val files: List<SavedFile>) : TransferResult

    /** 已经存好的 [saved] 保留。 */
    data class ReceiveFailed(val saved: List<SavedFile>) : TransferResult

    data object Cancelled : TransferResult

    /** 电脑端发来了文件，这部手机不能接收（安卓 9 及以下）。 */
    data object CannotReceive : TransferResult
}

/** 一批的结果给用户的提示（DESIGN.md），用在 Toast 和没有存好文件时的结果通知上；用户取消的不提示，为 null。 */
val TransferResult.message: String?
    get() = when (this) {
        is TransferResult.Sent -> "已发送 $count 个文件到 $desktopName"
        is TransferResult.SendFailed -> "没能发送到 $desktopName"
        is TransferResult.Saved -> "已保存 ${files.size} 个文件"
        is TransferResult.ReceiveFailed -> if (saved.isEmpty()) "没能接收文件" else "接收中断，已保存 ${saved.size} 个文件"
        TransferResult.Cancelled -> null
        TransferResult.CannotReceive -> "这部手机的系统版本太低，不能接收文件"
    }

/** 存在同一个地方的几个收到的文件，一组一条结果通知，点开就去那里（DESIGN.md）。 */
data class SavedGroup(val folder: SavedFolder, val files: List<String>, val message: String)

/** 存好的文件按存放的地方分组；没有存好的文件时为空，结果通知只有一条 [message]。 */
val TransferResult.savedGroups: List<SavedGroup>
    get() {
        val (saved, done) = when (this) {
            is TransferResult.Saved -> files to "已保存"
            is TransferResult.ReceiveFailed -> saved to "接收中断，已保存"
            else -> return emptyList()
        }
        return saved.groupBy { it.folder }.map { (folder, files) ->
            SavedGroup(folder, files.map { it.uri }, "$done ${files.size} 个文件到${folder.label}")
        }
    }

/**
 * 发送文件（docs/prd/send-files.md、ADR-0021）：一次只传一批，后发的排队。手机端发的文件只给当前电脑；
 * 任何一台电脑端发来的文件清单，都逐个下载存进 [incoming]。进度和结果交给界面，用户可以取消正在传的一批。
 * 电脑端断线或者任何一端出错，这一批就失败：已经传完的保留，没传完的删掉，不断点续传。
 * 处理完电脑端发来的一份清单，把结果告诉它，它据此提示用户。
 *
 * [incoming] 在不能接收文件时为 null：安卓 9 及以下，以及 iOS 版。
 */
class FileTransfers(
    private val connection: DesktopConnection,
    private val client: FileClient,
    private val incoming: IncomingFiles?,
    scope: CoroutineScope,
) {
    private val queue = Channel<Batch>(Channel.UNLIMITED)
    private val _progress = MutableStateFlow<TransferProgress?>(null)

    /** 正在传的一批，没有在传时为 null。 */
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    private val _results = MutableSharedFlow<TransferResult>(extraBufferCapacity = 8)
    val results: SharedFlow<TransferResult> = _results.asSharedFlow()

    /** 正在传的一批，只在 scope 的线程上读写。 */
    private var current: Job? = null

    init {
        scope.launch {
            connection.fileOffers.collect { (desktopId, offer) ->
                if (incoming == null) {
                    report(desktopId, offer.offer_id, FileOfferResult.Outcome.OUTCOME_UNSUPPORTED, saved = 0)
                    _results.emit(TransferResult.CannotReceive)
                } else {
                    queue.send(Batch.Receive(desktopId, offer))
                }
            }
        }
        scope.launch {
            for (batch in queue) {
                var result: TransferResult = TransferResult.Cancelled
                val job = launch { result = transfer(batch) }
                current = job
                job.join()
                current = null
                _progress.value = null
                if (batch is Batch.Receive) {
                    val outcome = when (result) {
                        is TransferResult.Saved -> FileOfferResult.Outcome.OUTCOME_SAVED
                        TransferResult.Cancelled -> FileOfferResult.Outcome.OUTCOME_CANCELLED
                        else -> FileOfferResult.Outcome.OUTCOME_FAILED
                    }
                    report(batch.desktopId, batch.offer.offer_id, outcome, batch.saved.size)
                }
                _results.emit(result)
            }
        }
    }

    /**
     * 发给当前电脑，排在已有的后面：分享菜单、"发送文件"按钮直接发，"发送剪贴板"先用 [confirm] 问用户。当前电脑不在线时不发。
     * 这些文件交给这里，传完、出错或不发时关掉。返回要提示的结果：发出时马上提示"正在发送到 某某"，不靠横幅
     * （澎湃 OS 只给白名单里的应用弹横幅），之后的进度和结果在通知里；用户不发时不提示，为 null。
     */
    suspend fun sendToCurrentDesktop(files: List<OutgoingFile>, confirm: suspend (PendingFiles) -> Boolean = { true }): SyncResult? {
        var queued = false
        try {
            val desktop = connection.desktops.value.find { it.id == connection.activeId.value } ?: return SyncResult.NoDesktopOnline
            if (!desktop.online) return SyncResult.DesktopOffline(desktop.name)
            if (!confirm(PendingFiles(files, desktop.name))) return null
            queue.send(Batch.Send(desktop.id, desktop.name, files))
            queued = true
            return SyncResult.Sending(desktop.name)
        } finally {
            if (!queued) files.forEach { it.close() }
        }
    }

    /** 取消正在传的一批，排着的照常传。 */
    fun cancel() {
        current?.cancel()
    }

    /** 传一批，返回结果；出错时也返回结果，只有取消时抛出。 */
    private suspend fun transfer(batch: Batch): TransferResult = try {
        when (batch) {
            is Batch.Send -> send(batch)
            is Batch.Receive -> receive(batch, checkNotNull(incoming))
        }
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        log.w(e) { "file transfer failed" }
        when (batch) {
            is Batch.Send -> TransferResult.SendFailed(batch.desktopName)
            is Batch.Receive -> TransferResult.ReceiveFailed(batch.saved.toList())
        }
    } finally {
        if (batch is Batch.Send) batch.files.forEach { it.close() }
    }

    private suspend fun send(batch: Batch.Send): TransferResult {
        val files = batch.files
        _progress.value = TransferProgress(sending = true, batch.desktopName, 0, files.size, 0, files.totalSize())
        val counted = files.mapIndexed { index, file -> file.copy(source = CountingSource(file.source) { read -> advance(index, read) }) }
        connection.whileConnected(batch.desktopId) { desktop -> client.upload(desktop, counted) }
        return TransferResult.Sent(files.size, batch.desktopName)
    }

    private suspend fun receive(batch: Batch.Receive, incoming: IncomingFiles): TransferResult {
        val offered = batch.offer.files
        val desktopName = connection.desktops.value.find { it.id == batch.desktopId }?.name.orEmpty()
        _progress.value = TransferProgress(sending = false, desktopName, 0, offered.size, 0, offered.sumOf { it.size })
        connection.whileConnected(batch.desktopId) { desktop ->
            offered.forEachIndexed { index, file ->
                advance(index, 0)
                val saving = incoming.create(file.name)
                try {
                    client.download(desktop, batch.offer.offer_id, index, CountingSink(saving.sink) { written -> advance(index, written) })
                    saving.publish()
                } catch (e: Throwable) {
                    saving.discard()
                    throw e
                }
                batch.saved += SavedFile(saving.uri, saving.folder)
            }
        }
        return TransferResult.Saved(batch.saved.toList())
    }

    /** 告诉发来清单 [offerId] 的电脑端结果：它据此提示用户，清单也随之作废。电脑端已经断线时就不告诉了。 */
    private fun report(desktopId: String, offerId: String, outcome: FileOfferResult.Outcome, saved: Int) {
        val result = FileOfferResult(offer_id = offerId, outcome = outcome, saved = saved)
        connection.sendTo(desktopId, ClientMessage(file_offer_result = result))
    }

    /** 正在传第 [index] 个，又传了 [bytes] 字节。传输在别的线程上读写文件，这里可能从那些线程调用。 */
    private fun advance(index: Int, bytes: Long) {
        _progress.update { it?.copy(index = index, transferred = it.transferred + bytes) }
    }

    private sealed interface Batch {
        class Send(val desktopId: String, val desktopName: String, val files: List<OutgoingFile>) : Batch

        class Receive(val desktopId: String, val offer: FileOffer) : Batch {
            /** 已经存好的文件。 */
            val saved = mutableListOf<SavedFile>()
        }
    }

    private companion object {
        val log = Logger.withTag("EchoPad.Files")
    }
}

/** 读多少报告多少，进度据此更新。 */
private class CountingSource(private val source: RawSource, private val counted: (Long) -> Unit) : RawSource by source {
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = source.readAtMostTo(sink, byteCount).also { if (it > 0) counted(it) }
}

/** 写多少报告多少，进度据此更新。 */
private class CountingSink(private val sink: RawSink, private val counted: (Long) -> Unit) : RawSink by sink {
    override fun write(source: Buffer, byteCount: Long) {
        sink.write(source, byteCount)
        counted(byteCount)
    }
}
