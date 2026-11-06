package app.routerspot

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import app.routerspot.core.TetherGuard

/**
 * Связь приложения со служебным процессом. Главная задача здесь — безопасность:
 * если служебный процесс умирает при включённой раздаче VPN, туннеля больше нет,
 * и хотспот выключается сразу, не дожидаясь, пока система выберет прямой выход.
 */
object ServerConnection {
    private const val TAG = "RouterSpot"

    private val main = Handler(Looper.getMainLooper())
    private var guard: TetherGuard? = null

    @Volatile var server: IRouterSpotServer? = null
        private set
    @Volatile var vpnActive = false
        private set

    /** Вызывается на главном потоке после гибели служебного процесса; аргумент — шла ли раздача. */
    var onDied: ((wasActive: Boolean) -> Unit)? = null

    private val client = object : IRouterSpotClient.Stub() {
        override fun onVpnStateChanged(active: Boolean) {
            vpnActive = active
            Log.i(TAG, "vpn active=$active")
        }
    }

    /** Вызывается из [ServerProvider], когда служебный процесс передал свой binder. */
    fun attach(context: Context, binder: IBinder) {
        val remote = IRouterSpotServer.Stub.asInterface(binder)
        binder.linkToDeath({ main.post { onServerDied(binder) } }, 0)
        server = remote
        ensureGuard(context.applicationContext)
        remote.attachClient(client)
        Log.i(TAG, "server attached")
    }

    private fun onServerDied(binder: IBinder) {
        if (server?.asBinder() != binder) return
        server = null
        val wasActive = vpnActive
        Log.w(TAG, "server died, vpnActive=$wasActive")
        if (wasActive) {
            vpnActive = false
            guard?.stopSharing()
        }
        onDied?.invoke(wasActive)
    }

    private fun ensureGuard(context: Context) {
        if (guard != null) return
        guard = TetherGuard(
            tethering = context.getSystemService("tethering")!!,
            connectivity = context.getSystemService(ConnectivityManager::class.java),
            executor = context.mainExecutor,
            log = { Log.w(TAG, it) },
            armed = { vpnActive },
        ).also { it.register() }
    }
}
