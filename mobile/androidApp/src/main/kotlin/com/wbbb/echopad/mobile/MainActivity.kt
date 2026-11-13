package com.wbbb.echopad.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wbbb.echopad.mobile.connection.ConnectionService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val pairingLinks = Channel<String>(Channel.BUFFERED)
    private val app get() = application as EchoPadApplication

    /** 刚授予通知权限时，再启动一次前台服务，把常驻通知显示出来。 */
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ConnectionService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 只有深色界面，系统栏一律用浅色图标；默认会跟随系统的浅色模式用深色图标，和背景混在一起。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // 当作触控板使用时屏幕不能自动熄灭。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 界面重建时 intent 会再次送达，只在首次创建时处理，避免重复配对。
        if (savedInstanceState == null) intent?.dataString?.let(pairingLinks::trySend)

        setContent {
            App(app.core, pairingLinks.receiveAsFlow())
        }

        // 配过电脑时，用前台服务在后台一直保持连接（ADR-0019）。只能在页面看得见时启动，一台电脑都不剩时服务自己停下。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.core.connection.hasDesktops.filterNotNull().collect { paired ->
                    if (paired) startConnectionService()
                }
            }
        }
    }

    /** 回到 EchoPad 时，手机剪贴板有了新内容就发出，不提示结果（docs/prd/clipboard-sync.md）。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) app.core.scope.launch { app.clipboardSync.syncOnReturn() }
    }

    /** 第一次需要常驻通知时请求通知权限，只请求这一次；拒绝了连接也照常保持，只是看不到通知。 */
    private suspend fun startConnectionService() {
        ConnectionService.start(this)
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted && app.core.storage.firstNotificationRequest()) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let(pairingLinks::trySend)
    }
}
