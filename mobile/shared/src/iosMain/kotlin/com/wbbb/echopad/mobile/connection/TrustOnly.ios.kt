package com.wbbb.echopad.mobile.connection

import io.ktor.network.tls.TLSConfigBuilder

// 第一版只发布 Android（docs/prd/pairing.md），iOS 的证书校验之后实现。
internal actual fun TLSConfigBuilder.trustOnly(fingerprint: String): Unit = TODO("iOS 版的证书校验尚未实现")
