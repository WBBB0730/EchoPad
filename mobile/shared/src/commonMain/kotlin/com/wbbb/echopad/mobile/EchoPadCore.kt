package com.wbbb.echopad.mobile

import com.wbbb.echopad.mobile.clipboard.ClipboardSync
import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.connection.KtorTransport
import com.wbbb.echopad.mobile.files.FileTransfers
import com.wbbb.echopad.mobile.files.KtorFileClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Clock

/**
 * 跟随整个进程的连接、剪贴板同步和发送文件（docs/prd/clipboard-sync.md、docs/prd/send-files.md）：
 * 界面、Android 的前台服务和透明页面共用，界面关掉以后也还在。一个进程只创建一个。
 */
class EchoPadCore(val platform: Platform) {
    /** 跟随整个进程，从不取消。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val storage = platform.storage

    val connection = DesktopConnection(
        storage.pairedDesktops,
        platform.deviceName,
        KtorTransport(),
        scope,
        platform.desktopFinder,
        merge(platform.networkChanges, platform.screenTurnedOn),
    )

    val fileTransfers = FileTransfers(connection, KtorFileClient(), platform.incomingFiles, scope)

    /** 平台不支持剪贴板时（iOS 版还没有）为 null。 */
    val clipboardSync = platform.clipboard?.let {
        val settings = storage.clipboardSettings.stateIn(scope, SharingStarted.Eagerly, null)
        ClipboardSync(connection, settings, it, fileTransfers, scope, now = { Clock.System.now().toEpochMilliseconds() })
    }
}
