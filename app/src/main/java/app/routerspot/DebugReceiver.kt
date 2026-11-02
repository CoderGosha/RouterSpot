package app.routerspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.routerspot.adb.AdbDiscovery
import app.routerspot.adb.AdbManager
import app.routerspot.adb.PairingService
import app.routerspot.adb.ServerLauncher
import kotlin.concurrent.thread

/**
 * Управление без интерфейса для проверок на эмуляторе. Отправить может только shell
 * (receiver защищён разрешением DUMP):
 *   adb shell am broadcast -n app.routerspot/.DebugReceiver -a app.routerspot.debug.PAIR --es code 123456
 * Действия: PAIR (code), PAIR_WATCH (сопряжение через уведомление), LAUNCH, PROFILE (link), START (link), HOTSPOT (on: true|false), STOP, STATUS.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        thread(name = "debug") {
            val result = runCatching { handle(app, intent) }
            Log.i(TAG, "debug ${intent.action?.substringAfterLast('.')}: ${result.getOrElse { "error: $it" }}")
            pending.finish()
        }
    }

    private fun handle(context: Context, intent: Intent): String? {
        val server = { ServerConnection.server ?: error("server is not attached") }
        return when (intent.action?.substringAfterLast('.')) {
            "PAIR" -> {
                val endpoint = AdbDiscovery.find(context, AdbDiscovery.PAIRING, 10_000) ?: error("pairing service not found")
                AdbManager.get(context).pair(endpoint.host, endpoint.port, intent.getStringExtra("code")!!).toString()
            }
            "PAIR_WATCH" -> PairingService.start(context).let { "watching" }
            "LAUNCH" -> ServerLauncher.launch(context).also { AppController.onPaired() }
            "PROFILE" -> AppController.profiles.add(intent.getStringExtra("link")!!).proxy.name
            "START" -> server().start(intent.getStringExtra("link")!!) ?: "ok"
            "HOTSPOT" -> server().setHotspot(intent.getStringExtra("on") == "true") ?: "ok"
            "STOP" -> server().stop().let { "ok" }
            "STATUS" -> server().status()
            "SPEED" -> server().speedtest(intent.getStringExtra("url")!!)
            "DIAG" -> server().diagnose(intent.getStringExtra("link"))
            else -> "unknown action"
        }
    }

    private companion object {
        const val TAG = "RouterSpot"
    }
}
