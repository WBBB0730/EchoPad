package com.wbbb.echopad.mobile

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    private val pairingLinks = Channel<String>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 当作触控板使用时屏幕不能自动熄灭。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 界面重建时 intent 会再次送达，只在首次创建时处理，避免重复配对。
        if (savedInstanceState == null) intent?.dataString?.let(pairingLinks::trySend)

        val platform = (application as EchoPadApplication).platform
        setContent {
            App(platform, pairingLinks.receiveAsFlow())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let(pairingLinks::trySend)
    }
}
