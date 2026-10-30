package com.wbbb.echopad.mobile.connection

import io.ktor.network.tls.TLSConfigBuilder
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.io.encoding.Base64

internal actual fun TLSConfigBuilder.trustOnly(fingerprint: String) {
    trustManager = PinnedTrustManager(fingerprint)
}

/** 只信任配对码里指纹对应的那张证书（ADR-0014），冒充的电脑端握手就会失败，拿不到设备密钥。 */
private class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val certificate = chain.firstOrNull() ?: throw CertificateException("电脑端没有提供证书")
        val actual = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
        if (actual != fingerprint) throw CertificateException("电脑端的证书和配对时的不一致")
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("手机端不接受连接")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
