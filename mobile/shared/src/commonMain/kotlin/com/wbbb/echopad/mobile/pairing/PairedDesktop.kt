package com.wbbb.echopad.mobile.pairing

/**
 * 配过的电脑端（ADR-0015）。设备密钥是配对成功时电脑端发放的，之后每次连接都用它验证；
 * 证书指纹来自配对码，之后只连接证书对得上的电脑端（ADR-0014）。
 */
data class PairedDesktop(
    /** 电脑端标识，电脑的地址变了以后据此在局域网里找到它。 */
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val deviceKey: String,
    val fingerprint: String,
)

/** 手机端保存的配对：配过的电脑端，以及当前电脑。 */
data class SavedPairing(val desktops: List<PairedDesktop>, val activeId: String?)

interface PairedDesktopStore {
    suspend fun load(): SavedPairing

    /** 按电脑端标识新增或替换。 */
    suspend fun save(desktop: PairedDesktop)

    suspend fun remove(id: String)

    suspend fun setActive(id: String?)

    /** 这部手机端的固定标识，同一部手机再次扫码时，电脑端据此替换原来的配对。 */
    suspend fun phoneId(): String
}
