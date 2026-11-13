package com.wbbb.echopad.mobile.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 手机的屏幕亮了时发出，还没连上的电脑立即重连（ADR-0004、ADR-0019），KDE Connect 也这么做。
 * 亮屏广播只能在运行时注册，整个进程只需要注册一次。
 */
fun screenTurnedOn(context: Context): SharedFlow<Unit> {
    val turnedOn = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    ContextCompat.registerReceiver(
        context,
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                turnedOn.tryEmit(Unit)
            }
        },
        IntentFilter(Intent.ACTION_SCREEN_ON),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    return turnedOn.asSharedFlow()
}
