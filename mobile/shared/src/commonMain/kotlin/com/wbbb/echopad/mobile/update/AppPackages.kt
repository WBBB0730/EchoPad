package com.wbbb.echopad.mobile.update

/** 手机端在各平台上自己更新自己的能力（ADR-0009），由平台的入口提供；iOS 版还没有。 */
interface AppPackages {
    /** 当前安装的版本号。 */
    val currentVersion: String

    /** debug 构建的签名和发版不同，装不上发版的 APK：只提示，不下载安装（同 DevCube ADR-0017）。 */
    val installable: Boolean

    /** 读取 [url] 的文本内容，失败时抛出异常。 */
    suspend fun fetchText(url: String): String

    /** 把 [url] 指向的安装包下载到本机，供之后 [install]；失败时抛出异常。 */
    suspend fun download(url: String)

    /** 交给系统安装已下载的安装包，等到有结果为止。装好时 App 通常已被系统关闭，等不到结果。 */
    suspend fun install(): InstallResult

    fun openUrl(url: String)
}

enum class InstallResult {
    Installed,
    /** 用户在系统确认里取消，或者还没允许"安装未知应用"、刚被带去系统设置。 */
    Cancelled,
    Failed,
}
