package com.wbbb.echopad.mobile.files

import com.wbbb.echopad.mobile.pairing.PairedDesktop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.io.writeString

/** 手机上要发的一个文件，记下有没有关掉。 */
class TestSource(content: String) : RawSource {
    private val buffer = Buffer().apply { writeString(content) }
    var closed = false
        private set

    override fun readAtMostTo(sink: Buffer, byteCount: Long) = buffer.readAtMostTo(sink, byteCount)

    override fun close() {
        closed = true
    }
}

fun outgoingFile(name: String, content: String) = OutgoingFile(name, content.encodeToByteArray().size.toLong(), TestSource(content))

val OutgoingFile.closed get() = (source as TestSource).closed

/** 每个请求都交给测试控制：测试看请求的内容，并决定它什么时候结束、成功还是失败。 */
class FakeFileClient : FileClient {
    val requests = Channel<Request>(Channel.UNLIMITED)

    /** 电脑端清单里的文件内容，按清单标识和序号。 */
    val offered = mutableMapOf<Pair<String, Int>, String>()

    class Request(val desktop: PairedDesktop, val files: List<Pair<String, String>>) {
        private val result = CompletableDeferred<Unit>()

        suspend fun await() = result.await()

        fun succeed() {
            result.complete(Unit)
        }

        fun fail() {
            result.completeExceptionally(Exception("the request failed"))
        }
    }

    /** 上传时读完每个文件，进度随之更新。 */
    override suspend fun upload(desktop: PairedDesktop, files: List<OutgoingFile>) {
        val request = Request(desktop, files.map { it.name to it.source.buffered().readString() })
        requests.send(request)
        request.await()
    }

    /** 下载时请求成功后才写进去。 */
    override suspend fun download(desktop: PairedDesktop, offerId: String, index: Int, sink: RawSink) {
        val content = offered.getValue(offerId to index)
        val request = Request(desktop, listOf("$offerId/$index" to content))
        requests.send(request)
        request.await()
        val buffer = Buffer().apply { writeString(content) }
        sink.write(buffer, buffer.size)
    }
}

/** 收到的文件存在内存里，记下每个是存好了还是删掉了；.png 和 .mp4 算进相册，其余进"下载"。 */
class FakeIncomingFiles : IncomingFiles {
    val files = mutableListOf<FakeIncomingFile>()

    override fun create(name: String): FakeIncomingFile {
        val folder = if (name.endsWith(".png") || name.endsWith(".mp4")) SavedFolder.Album else SavedFolder.Downloads
        return FakeIncomingFile(name, "content://saved/${files.size}", folder).also(files::add)
    }
}

class FakeIncomingFile(val name: String, override val uri: String, override val folder: SavedFolder) : IncomingFile {
    private val buffer = Buffer()
    override val sink: RawSink = buffer

    /** 正在写、已经存好、已经删掉。 */
    var state = "writing"
        private set

    val content get() = buffer.copy().readString()

    override fun publish() {
        state = "published"
    }

    override fun discard() {
        state = "discarded"
    }
}
