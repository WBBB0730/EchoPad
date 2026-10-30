package com.wbbb.echopad.mobile.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 手机的默认网络变了时发出，例如换了 Wi-Fi、从流量切回 Wi-Fi（docs/prd/pairing.md）。
 * 整个进程只需要注册一次。
 */
fun networkChanges(context: Context): SharedFlow<Unit> {
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    connectivity.registerDefaultNetworkCallback(
        object : ConnectivityManager.NetworkCallback() {
            /** 注册后系统会先告知一次当前的默认网络，那不算变化，不该打断正在进行的连接。 */
            private var current: Network? = connectivity.activeNetwork

            override fun onAvailable(network: Network) {
                if (network != current) {
                    Logger.withTag("EchoPad.Network").i { "default network changed to $network" }
                    current = network
                    changes.tryEmit(Unit)
                }
            }

            override fun onLost(network: Network) {
                if (network == current) current = null
            }
        },
    )
    return changes.asSharedFlow()
}
