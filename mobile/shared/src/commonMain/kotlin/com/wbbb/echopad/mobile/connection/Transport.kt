package com.wbbb.echopad.mobile.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.URLProtocol
import io.ktor.http.path
import io.ktor.network.tls.TLSConfigBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.cancel

/** 与电脑端之间的一条连接。`receive` 在连接断开时抛出异常。 */
interface Session {
    suspend fun send(bytes: ByteArray)
    suspend fun receive(): ByteArray
}

interface Transport {
    /**
     * 连接电脑端并在连接上执行 [block]；[block] 结束或出错时关闭连接。
     * 只连接证书指纹为 [fingerprint] 的电脑端（ADR-0014），对不上时连接失败。
     */
    suspend fun <T> connect(host: String, port: Int, fingerprint: String, block: suspend (Session) -> T): T
}

/** 电脑端证书里的名字，用作 TLS 的服务器名。电脑的 IP 地址会变，所以证书里不放 IP。 */
private const val CERTIFICATE_NAME = "echopad"

/** 只信任 SHA-256 指纹为 [fingerprint]（Base64URL，不补齐）的那张证书。 */
internal expect fun TLSConfigBuilder.trustOnly(fingerprint: String)

/**
 * 基于 Ktor CIO 引擎、TLS 上的 WebSocket 连接（ADR-0003、ADR-0014）。CIO 在 Android 和 iOS 上都能使用心跳设置，
 * 发现断线靠心跳：每 2 秒一次，5 秒内收不到回应就断开（ADR-0004）。每条连接只认自己那台电脑端的证书。
 */
class KtorTransport : Transport {
    override suspend fun <T> connect(host: String, port: Int, fingerprint: String, block: suspend (Session) -> T): T {
        val client = HttpClient(CIO) {
            engine {
                // 长连接不受单次请求超时限制。
                requestTimeout = 0
                endpoint.connectTimeout = 3_000
                endpoint.connectAttempts = 1
                https {
                    serverName = CERTIFICATE_NAME
                    trustOnly(fingerprint)
                }
            }
            install(WebSockets) {
                pingIntervalMillis = 2_000
            }
        }
        return try {
            val socket = client.webSocketSession {
                url {
                    protocol = URLProtocol.WSS
                    this.host = host
                    this.port = port
                    path("ws")
                }
            }
            socket.timeoutMillis = 5_000
            try {
                block(KtorSession(socket))
            } finally {
                socket.cancel()
            }
        } finally {
            client.close()
        }
    }
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
