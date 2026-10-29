package com.wbbb.echopad.mobile.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Android 上自己更新自己：从 GitHub Release 下载 APK，交给系统的 PackageInstaller 安装（ADR-0009）。 */
class AndroidAppPackages(private val context: Context) : AppPackages {
    private val client = HttpClient(OkHttp) { expectSuccess = true }
    private val apk = File(context.cacheDir, "update.apk")

    override val currentVersion: String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    override val installable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0

    override suspend fun fetchText(url: String): String = client.get(url).bodyAsText()

    override suspend fun download(url: String) {
        client.prepareGet(url).execute { response ->
            withContext(Dispatchers.IO) {
                response.bodyAsChannel().toInputStream().use { input -> apk.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    override suspend fun install(): InstallResult {
        val packageManager = context.packageManager
        // 还没允许"安装未知应用"：先带用户去系统设置，允许之后回来再点一次更新。
        if (!packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return InstallResult.Cancelled
        }
        val installer = packageManager.packageInstaller
        return try {
            val sessionId = withContext(Dispatchers.IO) { writeSession(installer) }
            awaitInstall(installer, sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            InstallResult.Failed
        }
    }

    override fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun writeSession(installer: PackageInstaller): Int {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("EchoPad.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
        }
        return sessionId
    }

    /** 提交安装并等系统送回结果。系统要用户再确认一次时，打开系统的安装确认，确认后结果会再送达一次。 */
    private suspend fun awaitInstall(installer: PackageInstaller, sessionId: Int): InstallResult =
        suspendCancellableCoroutine { continuation ->
            val action = "${context.packageName}.INSTALL_RESULT"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) return
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                            ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return
                    }
                    context.unregisterReceiver(this)
                    continuation.resume(
                        when (status) {
                            PackageInstaller.STATUS_SUCCESS -> InstallResult.Installed
                            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Cancelled
                            else -> InstallResult.Failed
                        },
                    )
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

            // 系统要往结果里填内容，PendingIntent 必须可变；指定了包名，也就不是隐式 Intent
            val result = Intent(action).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, result, flags)
            installer.openSession(sessionId).use { it.commit(pending.intentSender) }
        }
}
