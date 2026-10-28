package com.wbbb.echopad.mobile.pairing

/** 已配对的电脑端。设备密钥是配对成功时电脑端发放的，之后每次连接都用它验证。 */
data class PairedDesktop(
    val host: String,
    val port: Int,
    val deviceKey: String,
    val name: String,
)

interface PairedDesktopStore {
    suspend fun load(): PairedDesktop?
    suspend fun save(desktop: PairedDesktop)
    suspend fun clear()
}
