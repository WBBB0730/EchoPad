package com.wbbb.echopad.mobile

import com.wbbb.echopad.mobile.clipboard.ClipboardSync
import com.wbbb.echopad.mobile.connection.DesktopConnection
import com.wbbb.echopad.mobile.connection.KtorTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Clock

/**
 * 跟随整个进程的连接和剪贴板同步（docs/prd/clipboard-sync.md）：界面、Android 的前台服务和透明页面共用，
 * 界面关掉以后也还在。一个进程只创建一个。
 */
class EchoPadCore(val platform: Platform) {
    /** 跟随整个进程，从不取消。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val storage = platform.storage

    /** 剪贴板同步的开关，还没读出来时为 null。 */
    private val clipboardSetting: StateFlow<Boolean?> = storage.clipboardSync.stateIn(scope, SharingStarted.Eagerly, null)

    val connection = DesktopConnection(
        storage.pairedDesktops,
        platform.deviceName,
        KtorTransport(),
        scope,
        platform.desktopFinder,
        merge(platform.networkChanges, platform.screenTurnedOn),
        greeting = { ClipboardSync.greeting(enabled = platform.clipboard != null && clipboardSetting.value == true) },
    )

    /** 平台不支持剪贴板时（iOS 版还没有）为 null。 */
    val clipboardSync = platform.clipboard?.let {
        ClipboardSync(connection, clipboardSetting, it, scope, now = { Clock.System.now().toEpochMilliseconds() })
    }
}
