package com.wbbb.echopad.mobile

import com.wbbb.echopad.mobile.clipboard.PhoneClipboard
import com.wbbb.echopad.mobile.connection.DesktopFinder
import com.wbbb.echopad.mobile.files.IncomingFiles
import com.wbbb.echopad.mobile.update.AppPackages
import kotlinx.coroutines.flow.SharedFlow

/** 各平台提供给共享代码的能力，由 Android、iOS 的入口创建。 */
class Platform(
    val deviceName: String,
    val storage: LocalStorage,
    val desktopFinder: DesktopFinder,
    /** 手机的网络变了时发出。 */
    val networkChanges: SharedFlow<Unit>,
    /** 手机的屏幕亮了时发出。 */
    val screenTurnedOn: SharedFlow<Unit>,
    /** 应用内更新；iOS 版还没有，为 null。 */
    val packages: AppPackages? = null,
    /** 剪贴板同步用的系统剪贴板；iOS 版还没有，为 null。 */
    val clipboard: PhoneClipboard? = null,
    /** 收到的文件存到哪里；安卓 9 及以下、iOS 版不能接收文件，为 null。 */
    val incomingFiles: IncomingFiles? = null,
)
