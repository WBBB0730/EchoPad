package com.wbbb.echopad.mobile.clipboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.wbbb.echopad.mobile.EchoPadApplication
import com.wbbb.echopad.mobile.connection.ConnectionService
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 在 EchoPad 以外同步剪贴板的透明页面（ADR-0017）：浮在原来的应用上方，只占一个像素，触摸照常交给下面的应用。
 * 拿到焦点的那一刻同步，结果用 Toast 提示（docs/prd/clipboard-sync.md），然后关闭。
 */
abstract class SyncActivity : ComponentActivity() {
    /** 拿着焦点时调用。 */
    protected abstract suspend fun sync(clipboardSync: ClipboardSync): SyncResult

    /** 焦点可能来回变化，只同步一次。 */
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this), ViewGroup.LayoutParams(1, 1))
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started) return
        started = true
        val app = application as EchoPadApplication
        lifecycleScope.launch {
            val result = sync(app.clipboardSync)
            // 前台服务没在运行时（例如被系统结束过）趁页面还看得见把它开起来：系统只允许看得见的页面启动前台服务。
            if (app.core.connection.hasDesktops.filterNotNull().first()) ConnectionService.start(this@SyncActivity)
            app.toast(result.message)
            finish()
        }
    }
}

/** 通知上的"同步剪贴板"按钮和下拉菜单里的磁贴打开它：读剪贴板并发出。不对其他应用开放。 */
class SyncClipboardActivity : SyncActivity() {
    override suspend fun sync(clipboardSync: ClipboardSync) = clipboardSync.syncClipboard()

    companion object {
        fun intent(context: Context) =
            Intent(context, SyncClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** 文字选择菜单和分享菜单里的"同步到 EchoPad"：系统直接交来选中的文字，不用读剪贴板。 */
class SyncTextActivity : SyncActivity() {
    override suspend fun sync(clipboardSync: ClipboardSync): SyncResult {
        val text = (intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
            ?.toString()
            ?.ifEmpty { null }
        return text?.let { clipboardSync.syncText(it) } ?: SyncResult.NoText
    }
}

private val SyncResult.message: String
    get() = when (this) {
        is SyncResult.Sent -> desktopNames.singleOrNull()?.let { "已同步到 $it" } ?: "已同步到 ${desktopNames.size} 台电脑"
        SyncResult.NoDesktopOnline -> "没有在线的电脑"
        SyncResult.NoText -> "剪贴板里没有文字"
        SyncResult.TooLong -> "文字太长，没有同步"
        SyncResult.Disabled -> "剪贴板同步已关闭"
    }

private fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
