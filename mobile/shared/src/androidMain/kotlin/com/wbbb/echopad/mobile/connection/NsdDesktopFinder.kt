package com.wbbb.echopad.mobile.connection

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 用 Android 的网络服务发现（NSD）在局域网里留意电脑端（docs/prd/pairing.md）。
 * 电脑端以电脑名作为服务实例名广播，电脑端标识放在附加信息里；找到的服务逐个解析，
 * 认出标识对得上的那台，取它的 IPv4 地址（连接服务只监听 IPv4）。
 * Android 14 起能一直收到服务的更新，电脑换了地址马上知道；更早的系统每个服务只解析一次。
 */
class NsdDesktopFinder(private val context: Context) : DesktopFinder {
    private val nsd = context.getSystemService(NsdManager::class.java)

    /** Android 14 以前的系统同一时间只能解析一个服务。 */
    private val legacyResolving = Mutex()

    override fun addresses(desktopId: String): Flow<Set<DesktopAddress>> =
        instances(desktopId)
            .runningFold(emptyMap<String, Set<DesktopAddress>>()) { found, (name, addresses) ->
                if (addresses.isEmpty()) found - name else found + (name to addresses)
            }
            .map { found -> found.values.flatten().toSet() }
            .distinctUntilChanged()
            // 还什么都没找到时不发出。
            .drop(1)

    /** 每个服务实例（按实例名）的最新地址；实例消失了、或者不是这台电脑端时为空。 */
    private fun instances(desktopId: String): Flow<Pair<String, Set<DesktopAddress>>> = channelFlow {
        val watchers = mutableMapOf<String, Job>()
        discover().collect { event ->
            val name = event.service.serviceName
            watchers.remove(name)?.cancelAndJoin()
            when (event) {
                is Discovery.Found -> watchers[name] = launch {
                    watch(event.service, desktopId).collect { send(name to it) }
                }
                is Discovery.Lost -> send(name to emptySet())
            }
        }
    }

    private sealed interface Discovery {
        val service: NsdServiceInfo

        class Found(override val service: NsdServiceInfo) : Discovery
        class Lost(override val service: NsdServiceInfo) : Discovery
    }

    /** 局域网里的 EchoPad 电脑端出现和消失，一直找下去；无法开始查找时直接结束。 */
    private fun discover(): Flow<Discovery> = callbackFlow {
        val failed = AtomicBoolean(false)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                log.d { "found ${service.describe()}" }
                trySend(Discovery.Found(service))
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                log.d { "lost ${service.describe()}" }
                trySend(Discovery.Lost(service))
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log.w { "discovery failed to start: $errorCode" }
                failed.set(true)
                channel.close()
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        // 开始查找失败时，系统已经注销了这个监听器，不能再停止。
        awaitClose { if (!failed.get()) nsd.stopServiceDiscovery(listener) }
    }.buffer(Channel.UNLIMITED)

    /** 一个服务是这台电脑端时，它广播的地址。 */
    private fun watch(service: NsdServiceInfo, desktopId: String): Flow<Set<DesktopAddress>> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            watchWithCallback(service, desktopId)
        } else {
            flow { resolveLegacy(service, desktopId)?.let { emit(it) } }
        }

    /** Android 14 起，服务的地址每次变化都会收到。 */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun watchWithCallback(service: NsdServiceInfo, desktopId: String): Flow<Set<DesktopAddress>> = callbackFlow {
        val failed = AtomicBoolean(false)
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                log.d { "updated ${serviceInfo.describe()}: ${serviceInfo.hostAddresses}" }
                trySend(addressesOf(serviceInfo, serviceInfo.hostAddresses, desktopId))
            }

            override fun onServiceLost() {
                log.d { "lost while watching ${service.describe()}" }
                trySend(emptySet())
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                log.w { "failed to watch ${service.describe()}: $errorCode" }
                failed.set(true)
                channel.close()
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        nsd.registerServiceInfoCallback(service, context.mainExecutor, callback)
        awaitClose { if (!failed.get()) nsd.unregisterServiceInfoCallback(callback) }
    }.buffer(Channel.UNLIMITED)

    /**
     * Android 14 以前的解析方式，解析失败时返回 null。系统不支持中途取消，
     * 所以取消后仍等它解析完再放开，免得下一个服务的解析因为"已在解析"而失败。
     */
    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(service: NsdServiceInfo, desktopId: String): Set<DesktopAddress>? =
        legacyResolving.withLock {
            withContext(NonCancellable) {
                suspendCancellableCoroutine { continuation ->
                    nsd.resolveService(
                        service,
                        object : NsdManager.ResolveListener {
                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                log.d { "resolved ${serviceInfo.describe()}: ${serviceInfo.host}" }
                                continuation.resume(addressesOf(serviceInfo, listOfNotNull(serviceInfo.host), desktopId))
                            }

                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                log.w { "failed to resolve ${serviceInfo.describe()}: $errorCode" }
                                continuation.resume(null)
                            }
                        },
                    )
                }
            }
        }

    /** 是这台电脑端时，取它的 IPv4 地址；不是时为空。 */
    private fun addressesOf(service: NsdServiceInfo, hosts: List<InetAddress>, desktopId: String): Set<DesktopAddress> {
        if (service.attributes[DESKTOP_ID_KEY]?.decodeToString() != desktopId) return emptySet()
        return hosts.filterIsInstance<Inet4Address>()
            .mapNotNull { address -> address.hostAddress?.let { DesktopAddress(it, service.port) } }
            .toSet()
    }

    /** 日志里区分同名服务：同一台电脑在不同的网络上各报一次，手机热点上的没有网络（为 null）。 */
    private fun NsdServiceInfo.describe(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "$serviceName (network $network)" else serviceName

    private companion object {
        val log = Logger.withTag("EchoPad.Discovery")

        /** 电脑端广播的服务类型。 */
        const val SERVICE_TYPE = "_echopad._tcp"

        /** 附加信息里电脑端标识的键。 */
        const val DESKTOP_ID_KEY = "id"
    }
}
