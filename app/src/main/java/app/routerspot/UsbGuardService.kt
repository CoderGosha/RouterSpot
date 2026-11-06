package app.routerspot

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.os.IBinder
import android.util.Log
import app.routerspot.core.TetherGuard
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Второй сторож USB-раздачи. USB-служба живёт в процессе интерфейса, и если он упадёт, TUN исчезнет,
 * а раздача тут же перейдёт на обычную сеть телефона. Этот сервис в отдельном процессе (:guard)
 * сразу её выключает. От force-stop он не спасает: система убивает все процессы приложения.
 */
class UsbGuardService : Service() {
    private var target: IBinder? = null
    private val death = IBinder.DeathRecipient {
        Log.w(TAG, "usb service died, stopping sharing")
        TetherGuard(
            tethering = getSystemService("tethering")!!,
            connectivity = getSystemService(ConnectivityManager::class.java),
            executor = mainExecutor,
            log = { Log.w(TAG, it) },
            armed = { false },
        ).stopSharing()
        target = null
    }

    private val binder = object : IUsbGuard.Stub() {
        override fun watch(t: IBinder) {
            unwatch()
            t.linkToDeath(death, 0)
            target = t
        }

        override fun unwatch() {
            target?.unlinkToDeath(death, 0)
            target = null
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Сторона приложения. Вызовы блокирующие, не на главном потоке. */
    companion object {
        private const val TAG = "RouterSpot"
        private const val CONNECT_TIMEOUT_S = 5L
        private var connection: ServiceConnection? = null
        private var guard: IUsbGuard? = null

        @Synchronized
        fun arm(context: Context, target: IBinder) {
            disarm(context)
            val connected = CountDownLatch(1)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    guard = IUsbGuard.Stub.asInterface(service)
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    Log.w(TAG, "usb guard process died")
                }
            }
            connection = conn
            val intent = Intent(context, UsbGuardService::class.java)
            check(context.bindService(intent, conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) { "usb guard did not start" }
            check(connected.await(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)) { "usb guard did not start" }
            guard!!.watch(target)
        }

        @Synchronized
        fun disarm(context: Context) {
            runCatching { guard?.unwatch() }
            connection?.let { runCatching { context.unbindService(it) } }
            connection = null
            guard = null
        }
    }
}
