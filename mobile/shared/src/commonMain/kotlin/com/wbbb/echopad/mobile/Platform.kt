package com.wbbb.echopad.mobile

import com.wbbb.echopad.mobile.connection.DesktopFinder
import kotlinx.coroutines.flow.SharedFlow

/** 各平台提供给共享代码的能力，由 Android、iOS 的入口创建。 */
class Platform(
    val deviceName: String,
    val storage: LocalStorage,
    val desktopFinder: DesktopFinder,
    /** 手机的网络变了时发出。 */
    val networkChanges: SharedFlow<Unit>,
)
