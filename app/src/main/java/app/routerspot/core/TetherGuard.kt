package app.routerspot.core

import android.net.ConnectivityManager
import android.net.Network
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Сторож выхода раздачи. Пока раздача VPN включена, выходом хотспота может быть только
 * тестовая сеть RouterSpot; любая другая сеть означает прямой выход клиентов в интернет,
 * и тогда хотспот выключается немедленно. Отсутствие выхода (null) безопасно: трафик некуда отдать.
 *
 * TetheringManager — @SystemApi, поэтому работаем с ним через рефлексию; [tethering] получает
 * приложение через getSystemService("tethering"), служебный процесс — конструктором.
 */
class TetherGuard(
    private val tethering: Any,
    private val connectivity: ConnectivityManager,
    private val executor: Executor,
    private val log: (String) -> Unit,
    /** Включена ли сейчас раздача VPN (сторож срабатывает только в этом режиме). */
    private val armed: () -> Boolean,
    /** Состояние хотспота и число подключённых клиентов — для статуса в интерфейсе. */
    private val onTetheringChanged: (active: Boolean, clients: Int) -> Unit = { _, _ -> },
) {
    private var tetheredActive = false
    private var clientCount = 0
    private var upstream: Network? = null

    private var callback: Any? = null

    fun register() {
        if (callback != null) return
        val callbackClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
        // Методы колбэка ничего не возвращают; Object-методы прокси обслуживаем сами.
        val proxy = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { self, method, args ->
            when (method.name) {
                "onUpstreamChanged" -> onUpstreamChanged(args?.get(0) as Network?).let { null }
                "onTetheredInterfacesChanged" -> {
                    tetheredActive = (args?.get(0) as Collection<*>).isNotEmpty()
                    onTetheringChanged(tetheredActive, clientCount)
                    null
                }
                "onClientsChanged" -> {
                    clientCount = (args?.get(0) as Collection<*>).size
                    onTetheringChanged(tetheredActive, clientCount)
                    if (clientCount > 0) checkUpstream("client connected while upstream is not the tunnel")
                    null
                }
                "hashCode" -> System.identityHashCode(self)
                "equals" -> args?.get(0) === self
                "toString" -> "TetherGuard"
                else -> null
            }
        }
        tethering.javaClass.getMethod("registerTetheringEventCallback", Executor::class.java, callbackClass)
            .invoke(tethering, executor, proxy)
        callback = proxy
    }

    fun unregister() {
        val cb = callback ?: return
        val callbackClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
        runCatching { tethering.javaClass.getMethod("unregisterTetheringEventCallback", callbackClass).invoke(tethering, cb) }
        callback = null
    }

    /**
     * Сразу после старта раздачи Tethering может на мгновение выбрать обычную сеть, а затем
     * туннель. Пока клиентов нет, утечь нечему: ждём [GRACE_MS], и только если выход так и не
     * стал туннелем (или подключился клиент) — выключаем хотспот.
     */
    private fun onUpstreamChanged(network: Network?) {
        upstream = network
        if (network == null || !armed() || isTestNetwork(network)) return
        if (clientCount == 0) {
            log("guard: upstream is $network with no clients yet, waiting for the tunnel")
            Thread {
                Thread.sleep(GRACE_MS)
                executor.execute { checkUpstream("upstream is still not the tunnel") }
            }.start()
            return
        }
        log("guard: upstream switched to non-tunnel network $network, stopping hotspot")
        stopHotspot()
    }

    private fun checkUpstream(reason: String) {
        val network = upstream ?: return
        if (!armed() || isTestNetwork(network)) return
        log("guard: $reason ($network), stopping hotspot")
        stopHotspot()
    }

    fun isTestNetwork(network: Network): Boolean {
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(TRANSPORT_TEST)
    }

    fun stopHotspot() {
        runCatching {
            tethering.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tethering, TETHERING_WIFI)
        }.onFailure { log("guard: stopTethering failed: ${it.cause ?: it}") }
    }

    companion object {
        const val TRANSPORT_TEST = 7 // NetworkCapabilities.TRANSPORT_TEST, @SystemApi
        const val TETHERING_WIFI = 0
        private const val GRACE_MS = 5_000L
    }
}
