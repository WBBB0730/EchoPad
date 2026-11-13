package com.wbbb.echopad.mobile.connection

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wbbb.echopad.mobile.EchoPadApplication
import com.wbbb.echopad.mobile.MainActivity
import com.wbbb.echopad.mobile.R
import com.wbbb.echopad.mobile.clipboard.SyncClipboardActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 配过电脑时让 EchoPad 在后台一直运行，和所有配过的电脑端保持连接（ADR-0019）：前台服务带一条常驻通知，
 * 标题是在线的电脑；开着剪贴板同步时，通知上有"同步剪贴板"按钮。一台电脑都不剩时自己停下。
 * Android 14 起用户可以划掉通知，服务照常运行；下次启动服务时（打开 EchoPad、在 EchoPad 以外同步剪贴板）重新显示。
 */
class ConnectionService : Service() {
    private val scope = MainScope()
    private var onlineDesktops = emptyList<String>()
    private var clipboardSync = false

    override fun onCreate() {
        super.onCreate()
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW).setName("后台连接").build(),
        )
        val app = application as EchoPadApplication
        scope.launch {
            val manager = getSystemService(NotificationManager::class.java)
            combine(app.core.connection.desktops, app.clipboardSync.enabled, ::Pair).collect { (desktops, enabled) ->
                onlineDesktops = desktops.filter { it.online }.map { it.name }
                clipboardSync = enabled
                // 只更新还显示着的通知，用户划掉的不在这里弹回来。
                if (manager.activeNotifications.any { it.id == NOTIFICATION_ID }) manager.notify(NOTIFICATION_ID, notification())
            }
        }
        scope.launch {
            app.core.connection.hasDesktops.first { it == false }
            stopSelf()
        }
    }

    /** 每次启动都显示通知：被划掉的通知重新出现；刚授予通知权限时，之前的通知还没显示出来。 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(onlineDesktops.joinToString("、").ifEmpty { "未连接电脑" })
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (clipboardSync) {
            val sync = PendingIntent.getActivity(this, 0, SyncClipboardActivity.intent(this), PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "同步剪贴板", sync)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1

        /** 要在系统允许启动前台服务时调用：EchoPad 的页面看得见时，或者开机、更新广播里。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }
    }
}
