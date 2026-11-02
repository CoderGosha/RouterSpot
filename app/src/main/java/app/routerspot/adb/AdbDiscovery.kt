package app.routerspot.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Поиск сервисов беспроводной отладки через mDNS. Порт сопряжения случайный и живёт,
 * пока в настройках открыт диалог «Подключить по коду», поэтому ищем его каждый раз.
 */
object AdbDiscovery {
    private const val TAG = "RouterSpot"
    const val PAIRING = "_adb-tls-pairing._tcp"
    const val CONNECT = "_adb-tls-connect._tcp"

    data class Endpoint(val host: String, val port: Int)

    /** Сервис [type] на этом устройстве или null, если он не появился за [timeoutMs]. */
    fun find(context: Context, type: String, timeoutMs: Long): Endpoint? {
        val nsd = context.getSystemService(NsdManager::class.java)
        val result = CompletableFuture<Endpoint>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                Log.d(TAG, "mdns found ${info.serviceName} ${info.serviceType}")
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        Log.d(TAG, "mdns resolved ${resolved.serviceName} ${resolved.host}:${resolved.port}")
                        @Suppress("DEPRECATION")
                        if (isLocal(resolved)) result.complete(Endpoint(resolved.host.hostAddress!!, resolved.port))
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.d(TAG, "mdns resolve failed ${info.serviceName}: $errorCode")
                    }
                })
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                result.completeExceptionally(IllegalStateException("mDNS discovery failed: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        return try {
            result.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    /**
     * Следит за сервисом [type] на этом устройстве, пока не вызван возвращённый stop:
     * [onFound] — сервис появился (диалог с кодом открыт), [onLost] — пропал.
     */
    fun watch(context: Context, type: String, onFound: (Endpoint) -> Unit, onLost: () -> Unit): () -> Unit {
        val nsd = context.getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                Log.d(TAG, "mdns watch found ${info.serviceName}")
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        if (isLocal(resolved)) onFound(Endpoint(resolved.host.hostAddress!!, resolved.port))
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.d(TAG, "mdns watch resolve failed ${info.serviceName}: $errorCode")
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                Log.d(TAG, "mdns watch lost ${info.serviceName}")
                onLost()
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mdns watch failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        return { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    /** В сети могут быть и чужие устройства с отладкой; нужен сервис именно этого телефона. */
    @Suppress("DEPRECATION")
    private fun isLocal(info: NsdServiceInfo): Boolean {
        val host = info.host ?: return false
        return runCatching { java.net.NetworkInterface.getByInetAddress(host) != null }.getOrDefault(false)
    }
}
