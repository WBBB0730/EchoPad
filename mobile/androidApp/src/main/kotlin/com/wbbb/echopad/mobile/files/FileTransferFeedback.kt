package com.wbbb.echopad.mobile.files

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.wbbb.echopad.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * 发送文件的提示（docs/prd/send-files.md、DESIGN.md）：传输中是一条安静的进度通知，带"取消"按钮，点了发出 [cancel]；
 * 结束后换成结果通知，同时弹一条 Toast：结果通知在系统允许时才弹横幅（澎湃 OS 只给白名单里的应用弹），Toast 哪里都看得到。
 * 收到的文件按存放的地方各一条结果通知：相册里只有一个时点了打开它，其余点了打开所在的文件夹。用户取消的都不提示。
 * 通知类别在系统设置里叫"发送文件"，和常驻通知分开，重要、不响铃。
 */
class FileTransferFeedback(private val context: Context, private val transfers: FileTransfers, private val cancel: PendingIntent) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun start(scope: CoroutineScope) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("发送文件")
                .setSound(null, null)
                .build(),
        )
        scope.launch {
            // 进度变得很快，隔一会儿才更新一次通知，每次拿到的都是最新的进度；这一批结束后是 null，结果通知不会被盖掉。
            transfers.progress.collect { progress ->
                progress?.let { manager.notify(NOTIFICATION_ID, progress(it)) }
                delay(PROGRESS_INTERVAL)
            }
        }
        scope.launch {
            transfers.results.collect { result ->
                val message = result.message
                if (message == null) {
                    manager.cancel(NOTIFICATION_ID)
                    return@collect
                }
                val groups = result.savedGroups
                if (groups.isEmpty()) {
                    manager.notify(NOTIFICATION_ID, result(message, opens = null))
                } else {
                    manager.cancel(NOTIFICATION_ID)
                    for (group in groups) manager.notify(group.folder.notificationId, result(group.message, opens(group)))
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun progress(progress: TransferProgress): Notification {
        val total = progress.total
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_echopad)
            .setContentTitle(progress.title)
            .setContentText(progress.detail)
            .setProgress(PROGRESS_MAX, if (total == null || total == 0L) 0 else (progress.transferred * PROGRESS_MAX / total).toInt(), total == null)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "取消", cancel)
            .build()
    }

    private fun result(message: String, opens: Intent?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_echopad)
            .setContentTitle(message)
            .setAutoCancel(true)
        if (opens != null) {
            val intent = opens.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.setContentIntent(PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return builder.build()
    }

    /** 点一组收到的文件的通知时打开什么：相册里只有一个时打开它；其余打开所在的文件夹，反正都在一起。 */
    private fun opens(group: SavedGroup): Intent {
        val single = group.files.singleOrNull()
        if (group.folder == SavedFolder.Album && single != null) {
            val file = single.toUri()
            return Intent(Intent.ACTION_VIEW)
                .setDataAndType(file, context.contentResolver.getType(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val folder = Intent(Intent.ACTION_VIEW).setDataAndType(group.folder.documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
        // WPS、网盘这些应用也声称能打开文件夹，不指定就会先弹选择框；用系统自带的"文件"应用打开。
        val filesApp = context.packageManager.queryIntentActivities(folder, PackageManager.MATCH_SYSTEM_ONLY).firstOrNull()
        filesApp?.activityInfo?.let { folder.setClassName(it.packageName, it.name) }
        return folder
    }

    private val SavedFolder.notificationId: Int
        get() = when (this) {
            SavedFolder.Album -> ALBUM_NOTIFICATION_ID
            SavedFolder.Downloads -> DOWNLOADS_NOTIFICATION_ID
        }

    private companion object {
        const val CHANNEL_ID = "file_transfers"
        /** 进度通知，以及没有存好文件时的结果通知。 */
        const val NOTIFICATION_ID = 2
        /** 收到的文件按存放的地方各一条。 */
        const val ALBUM_NOTIFICATION_ID = 3
        const val DOWNLOADS_NOTIFICATION_ID = 4
        const val PROGRESS_MAX = 1000
        val PROGRESS_INTERVAL = 500.milliseconds
    }
}
