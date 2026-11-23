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
import com.wbbb.echopad.mobile.files.FileTransferFeedback
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 配过电脑时让 EchoPad 在后台一直运行，和所有配过的电脑端保持连接（ADR-0019）：前台服务带一条常驻通知，
 * 标题是在线的电脑；有电脑在线时，通知上有"发送剪贴板"按钮。一台电脑都不剩时自己停下。
 * Android 14 起用户可以划掉通知：有电脑在线时马上显示回来，不用去打开 EchoPad 找；没有电脑在线时，
 * 等有电脑上线、或者下次启动服务时（打开 EchoPad、在 EchoPad 以外同步剪贴板）再显示。
 * 发送文件的进度和结果也由它提示（docs/prd/send-files.md）。
 */
class ConnectionService : Service() {
    private val scope = MainScope()
    private var onlineDesktops = emptyList<String>()

    override fun onCreate() {
        super.onCreate()
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW).setName("后台连接").build(),
        )
        val app = application as EchoPadApplication
        scope.launch {
            val manager = getSystemService(NotificationManager::class.java)
            app.core.connection.desktops.collect { desktops ->
                onlineDesktops = desktops.filter { it.online }.map { it.name }
                // 有电脑在线时一定显示，被划掉的也显示回来；没有时只更新还显示着的。
                if (onlineDesktops.isNotEmpty() || manager.activeNotifications.any { it.id == NOTIFICATION_ID }) {
                    manager.notify(NOTIFICATION_ID, notification())
                }
            }
        }
        scope.launch {
            app.core.connection.hasDesktops.first { it == false }
            stopSelf()
        }
        FileTransferFeedback(this, app.core.fileTransfers, serviceIntent(ACTION_CANCEL_TRANSFER)).start(scope)
    }

    /**
     * 用户划掉通知时：有电脑在线就马上显示回来。其余每次启动都显示通知：被划掉的通知重新出现；
     * 刚授予通知权限时，之前的通知还没显示出来。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISSED -> if (onlineDesktops.isNotEmpty()) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            ACTION_CANCEL_TRANSFER -> (application as EchoPadApplication).core.fileTransfers.cancel()
            else -> {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
            }
        }
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
            .setSmallIcon(R.drawable.ic_stat_echopad)
            .setContentTitle(onlineDesktops.joinToString("、").ifEmpty { "未连接电脑" })
            .setContentIntent(open)
            .setDeleteIntent(serviceIntent(ACTION_DISMISSED))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // 没有电脑在线时同步不出去，不放按钮；剪贴板同步的开关只管自动同步，不影响这个按钮。
        if (onlineDesktops.isNotEmpty()) {
            val sync = PendingIntent.getActivity(this, 0, SyncClipboardActivity.intent(this), PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "发送剪贴板", sync)
        }
        return builder.build()
    }

    /** 交给这个服务处理 [action] 的 PendingIntent。 */
    private fun serviceIntent(action: String) =
        PendingIntent.getService(this, 0, Intent(this, ConnectionService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1

        /** 用户划掉了通知。 */
        private const val ACTION_DISMISSED = "com.wbbb.echopad.mobile.action.NOTIFICATION_DISMISSED"

        /** 用户在发送文件的进度通知上点了"取消"。 */
        private const val ACTION_CANCEL_TRANSFER = "com.wbbb.echopad.mobile.action.CANCEL_TRANSFER"

        /** 要在系统允许启动前台服务时调用：EchoPad 的页面看得见时，或者开机、更新广播里。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }
    }
}
