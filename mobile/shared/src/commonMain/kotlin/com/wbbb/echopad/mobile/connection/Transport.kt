package com.wbbb.echopad.mobile.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.cancel

/** 与电脑端之间的一条连接。`receive` 在连接断开时抛出异常。 */
interface Session {
    suspend fun send(bytes: ByteArray)
    suspend fun receive(): ByteArray
}

interface Transport {
    /** 连接电脑端并在连接上执行 [block]；[block] 结束或出错时关闭连接。 */
    suspend fun <T> connect(host: String, port: Int, block: suspend (Session) -> T): T
}

/**
 * 基于 Ktor CIO 引擎的 WebSocket 连接（ADR-0003）。CIO 在 Android 和 iOS 上都能使用心跳设置，
 * 发现断线靠心跳：每 2 秒一次，5 秒内收不到回应就断开（ADR-0004）。
 */
class KtorTransport : Transport, AutoCloseable {
    private val client = HttpClient(CIO) {
        engine {
            // 长连接不受单次请求超时限制。
            requestTimeout = 0
            endpoint.connectTimeout = 3_000
            endpoint.connectAttempts = 1
        }
        install(WebSockets) {
            pingIntervalMillis = 2_000
        }
    }

    override suspend fun <T> connect(host: String, port: Int, block: suspend (Session) -> T): T {
        val socket = client.webSocketSession(host = host, port = port, path = "/ws")
        socket.timeoutMillis = 5_000
        return try {
            block(KtorSession(socket))
        } finally {
            socket.cancel()
        }
    }

    override fun close() = client.close()
}

private class KtorSession(private val socket: DefaultClientWebSocketSession) : Session {
    override suspend fun send(bytes: ByteArray) = socket.send(Frame.Binary(true, bytes))

    override suspend fun receive(): ByteArray {
        while (true) {
            val frame = socket.incoming.receive()
            if (frame is Frame.Binary) return frame.readBytes()
        }
    }
}
