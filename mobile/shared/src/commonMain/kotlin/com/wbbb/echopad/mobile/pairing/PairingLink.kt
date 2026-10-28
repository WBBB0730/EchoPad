package com.wbbb.echopad.mobile.pairing

import io.ktor.http.parseUrl

/** 电脑端配对码里的链接：`echopad://pair?host=…&port=…&token=…`。 */
data class PairingLink(val host: String, val port: Int, val token: String) {
    companion object {
        fun parse(text: String): PairingLink? {
            val url = parseUrl(text.trim()) ?: return null
            if (url.protocol.name != "echopad" || url.host != "pair") return null
            val host = url.parameters["host"]?.takeIf { it.isNotBlank() } ?: return null
            val port = url.parameters["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val token = url.parameters["token"]?.takeIf { it.isNotBlank() } ?: return null
            return PairingLink(host, port, token)
        }
    }
}
