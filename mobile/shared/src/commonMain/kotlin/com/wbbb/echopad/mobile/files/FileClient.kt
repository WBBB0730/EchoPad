package com.wbbb.echopad.mobile.files

import com.wbbb.echopad.mobile.connection.desktopHttpClient
import com.wbbb.echopad.mobile.pairing.PairedDesktop
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.contentLength
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import io.ktor.http.path
import io.ktor.utils.io.readBuffer
import kotlinx.io.RawSink
import kotlinx.io.buffered

/** 发送文件的 HTTPS 请求（ADR-0021）：和连接一样只认配对时那张证书，请求头带上设备密钥。 */
interface FileClient {
    /** 把一批文件放进一个上传请求发给 [desktop]，边读边传。 */
    suspend fun upload(desktop: PairedDesktop, files: List<OutgoingFile>)

    /** 下载 [desktop] 发来的清单 [offerId] 里的第 [index] 个文件（从 0 数），边收边写进 [sink]。 */
    suspend fun download(desktop: PairedDesktop, offerId: String, index: Int, sink: RawSink)
}

class KtorFileClient : FileClient {
    override suspend fun upload(desktop: PairedDesktop, files: List<OutgoingFile>) {
        desktopHttpClient(desktop.fingerprint).use { client ->
            val form = formData {
                for (file in files) {
                    // 电脑端按 UTF-8 原样读引号里的文件名，文件名里的引号前加反斜杠。
                    val disposition = "filename=\"${file.name.replace("\"", "\\\"")}\""
                    append("file", InputProvider(file.size) { file.source.buffered() }, headersOf(HttpHeaders.ContentDisposition, disposition))
                }
            }
            val response = client.post {
                at(desktop, "files")
                setBody(MultiPartFormDataContent(form))
            }
            check(response.status.isSuccess()) { "the desktop rejected the upload: ${response.status}" }
        }
    }

    override suspend fun download(desktop: PairedDesktop, offerId: String, index: Int, sink: RawSink) {
        desktopHttpClient(desktop.fingerprint).use { client ->
            client.prepareGet { at(desktop, "files", offerId, index.toString()) }.execute { response ->
                check(response.status.isSuccess()) { "the desktop rejected the download: ${response.status}" }
                val body = response.bodyAsChannel()
                var received = 0L
                while (!body.isClosedForRead) received += body.readBuffer(CHUNK_BYTES).transferTo(sink)
                // 连接断在半路时，收到的比电脑端说的少。
                check(received == response.contentLength()) { "the download ended early" }
                sink.flush()
            }
        }
    }

    /** 发给电脑端的 [path]，带上设备密钥。 */
    private fun HttpRequestBuilder.at(desktop: PairedDesktop, vararg path: String) {
        url {
            protocol = URLProtocol.HTTPS
            host = desktop.host
            port = desktop.port
            path(*path)
        }
        bearerAuth(desktop.deviceKey)
    }

    private companion object {
        /** 下载时每次最多读这么多字节。 */
        const val CHUNK_BYTES = 64 * 1024L
    }
}
