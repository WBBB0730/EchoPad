package com.wbbb.echopad.mobile.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wbbb.echopad.mobile.EchoPadApplication
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 手机开机、EchoPad 更新以后，配过电脑就启动前台服务（ADR-0019），KDE Connect 也这么做。
 * 系统允许在这两种广播里启动前台服务；有的系统（例如小米）要用户允许 EchoPad 自启动才会收到。
 */
class ConnectionStarter : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val core = (context.applicationContext as EchoPadApplication).core
        // 配对要从本机存储里读出来，读完再结束这次广播。
        val pending = goAsync()
        core.scope.launch {
            try {
                if (core.connection.hasDesktops.filterNotNull().first()) ConnectionService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
