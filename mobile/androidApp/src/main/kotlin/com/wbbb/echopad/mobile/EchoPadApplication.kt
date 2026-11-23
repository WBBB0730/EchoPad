package com.wbbb.echopad.mobile

import android.app.Application
import android.os.Build
import android.provider.Settings
import com.wbbb.echopad.mobile.clipboard.AndroidPhoneClipboard
import com.wbbb.echopad.mobile.clipboard.ClipboardSync
import com.wbbb.echopad.mobile.connection.NsdDesktopFinder
import com.wbbb.echopad.mobile.connection.networkChanges
import com.wbbb.echopad.mobile.connection.screenTurnedOn
import com.wbbb.echopad.mobile.files.MediaStoreIncomingFiles
import com.wbbb.echopad.mobile.update.AndroidAppPackages

class EchoPadApplication : Application() {

    /** 本机存储和连接在一个进程里只能创建一次，界面、前台服务和透明页面共用，所以放在 Application 里。 */
    val core: EchoPadCore by lazy {
        EchoPadCore(
            Platform(
                // 用户在系统设置里起的设备名，读不到时用型号。
                deviceName = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL,
                storage = LocalStorage.create(filesDir.resolve("echopad.preferences_pb").absolutePath),
                desktopFinder = NsdDesktopFinder(this),
                networkChanges = networkChanges(this),
                screenTurnedOn = screenTurnedOn(this),
                packages = AndroidAppPackages(this),
                clipboard = AndroidPhoneClipboard(this),
                // 安卓 10 起存文件不需要存储权限；更早的系统不能接收文件（docs/prd/send-files.md）。
                incomingFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreIncomingFiles(this) else null,
            ),
        )
    }

    /** Android 上一定有剪贴板同步。 */
    val clipboardSync: ClipboardSync get() = checkNotNull(core.clipboardSync)
}
