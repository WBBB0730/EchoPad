package com.wbbb.echopad.mobile.clipboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.wbbb.echopad.mobile.EchoPadApplication
import com.wbbb.echopad.mobile.SyncResult
import com.wbbb.echopad.mobile.connection.ConnectionService
import com.wbbb.echopad.mobile.files.SendFilesDialog
import com.wbbb.echopad.mobile.files.SendFilesPrompt
import com.wbbb.echopad.mobile.files.openOutgoingFiles
import com.wbbb.echopad.mobile.message
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 在 EchoPad 以外同步剪贴板的透明页面（ADR-0017）：浮在原来的应用上方，只占一个像素，触摸照常交给下面的应用。
 * 拿到焦点的那一刻同步，结果用 Toast 提示（docs/prd/clipboard-sync.md），然后关闭。
 */
abstract class SyncActivity : ComponentActivity() {
    /** 拿着焦点时调用，返回要提示的结果；用户不发剪贴板里的文件时不提示，为 null。 */
    protected abstract suspend fun sync(app: EchoPadApplication): SyncResult?

    /** 页面的内容，只占一个像素。 */
    protected open fun content(): View = View(this)

    /** 焦点可能来回变化，只同步一次。 */
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(content(), ViewGroup.LayoutParams(1, 1))
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started) return
        started = true
        val app = application as EchoPadApplication
        lifecycleScope.launch {
            val result = sync(app)
            // 前台服务没在运行时（例如被系统结束过）趁页面还看得见把它开起来：系统只允许看得见的页面启动前台服务。
            if (app.core.connection.hasDesktops.filterNotNull().first()) ConnectionService.start(this@SyncActivity)
            result?.let { app.toast(it.message) }
            finish()
        }
    }
}

/**
 * 通知上的"发送剪贴板"按钮和下拉菜单里的磁贴打开它：读剪贴板并发出；剪贴板里是文件时，在这个页面上弹确认框
 * （docs/prd/send-files.md）。不对其他应用开放。
 */
class SyncClipboardActivity : SyncActivity() {
    private val prompt = SendFilesPrompt()

    override fun content(): View = ComposeView(this).apply {
        setContent {
            val asking by prompt.asking.collectAsState()
            asking?.let { SendFilesDialog(it) }
        }
    }

    override suspend fun sync(app: EchoPadApplication) = app.clipboardSync.syncClipboard(prompt::ask)

    companion object {
        fun intent(context: Context) =
            Intent(context, SyncClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * 文字选择菜单和分享菜单里的"同步到 EchoPad"：系统直接交来选中的文字，不用读剪贴板；分享的是文件时，
 * 不用确认，直接发给当前电脑（docs/prd/send-files.md）。
 */
class SyncToEchoPadActivity : SyncActivity() {
    override suspend fun sync(app: EchoPadApplication): SyncResult? {
        val shared = sharedFiles()
        if (shared.isNotEmpty()) {
            // 分享来的文件只在这个页面开着时读得到，趁现在打开，页面关掉以后照样能读完。
            val files = try {
                contentResolver.openOutgoingFiles(shared)
            } catch (e: Exception) {
                return SyncResult.FilesUnreadable
            }
            return app.core.fileTransfers.sendToCurrentDesktop(files)
        }
        val text = (intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
            ?.toString()
            ?.ifEmpty { null }
        return text?.let { app.clipboardSync.syncText(it) } ?: SyncResult.NoText
    }

    /** 分享来的文件：一个时是 EXTRA_STREAM，多个时是 EXTRA_STREAM 里的列表。 */
    private fun sharedFiles(): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}

private fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
