package com.wbbb.echopad.mobile

import androidx.compose.ui.window.ComposeUIViewController
import com.wbbb.echopad.mobile.connection.DesktopFinder
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice

/** 本机存储和连接在一个进程里只能创建一次。 */
private val core: EchoPadCore by lazy {
    EchoPadCore(
        Platform(
            deviceName = UIDevice.currentDevice.name,
            storage = LocalStorage.create("${documentsDirectory()}/echopad.preferences_pb"),
            // 第一版只发布 Android（docs/prd/pairing.md），iOS 的局域网查找和网络变化之后用 Network 框架实现。
            desktopFinder = DesktopFinder { emptyFlow() },
            networkChanges = MutableSharedFlow(),
            screenTurnedOn = MutableSharedFlow(),
            // iOS 的剪贴板同步不做（docs/prd/clipboard-sync.md），设置里不显示这一项。
        ),
    )
}

fun MainViewController() = ComposeUIViewController { App(core) }

@OptIn(ExperimentalForeignApi::class)
private fun documentsDirectory(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(NSDocumentDirectory, NSUserDomainMask, null, false, null)
    return requireNotNull(url?.path) { "documents directory is unavailable" }
}
