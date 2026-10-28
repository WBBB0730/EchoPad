package com.wbbb.echopad.mobile

import android.app.Application
import android.os.Build
import android.provider.Settings

class EchoPadApplication : Application() {
    /** 本机存储在一个进程里只能创建一次，所以放在 Application 里。 */
    val platform: Platform by lazy {
        Platform(
            // 用户在系统设置里起的设备名，读不到时用型号。
            deviceName = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL,
            storage = LocalStorage.create(filesDir.resolve("echopad.preferences_pb").absolutePath),
        )
    }
}
