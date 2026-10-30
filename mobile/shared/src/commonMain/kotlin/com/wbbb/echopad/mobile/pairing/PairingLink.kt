package com.wbbb.echopad.mobile.pairing

import io.ktor.http.parseUrl

/**
 * 电脑端配对码里的链接：`echopad://pair?host=…&port=…&token=…&id=…&fp=…`，
 * 带着电脑端的地址、一次性口令、电脑端标识和证书指纹（ADR-0014）。
 */
data class PairingLink(
    val host: String,
    val port: Int,
    val token: String,
    val desktopId: String,
    val fingerprint: String,
) {
    companion object {
        fun parse(text: String): PairingLink? {
            val url = parseUrl(text.trim()) ?: return null
            if (url.protocol.name != "echopad" || url.host != "pair") return null
            fun parameter(name: String) = url.parameters[name]?.takeIf { it.isNotBlank() }
            return PairingLink(
                host = parameter("host") ?: return null,
                port = parameter("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null,
                token = parameter("token") ?: return null,
                desktopId = parameter("id") ?: return null,
                fingerprint = parameter("fp") ?: return null,
            )
        }
    }
}
