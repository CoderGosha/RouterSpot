package app.routerspot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/** Что делать с проблемой: экран диагностики превращает это в кнопку. */
enum class Fix { NOTIFICATIONS, BATTERY, WRITE_SETTINGS, DEV_OPTIONS, WIFI, WIRELESS_DEBUGGING, PAIR, LAUNCH, PROFILES }

enum class CheckState { PENDING, RUNNING, OK, WARN, FAIL, SKIP }

enum class CheckGroup(val title: Int) {
    PHONE(R.string.diag_group_phone),
    PERMISSIONS(R.string.diag_group_permissions),
    SERVICE(R.string.diag_group_service),
    TUNNEL(R.string.diag_group_tunnel),
}

data class Check(
    val id: String,
    val group: CheckGroup,
    val title: Int,
    val state: CheckState = CheckState.PENDING,
    val detail: String? = null,
    val fix: Fix? = null,
)

/** Проверка «по одной кнопке»: телефон, разрешения, служба и сквозной тест туннеля. */
object Diagnostics {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(initial())
    val checks: StateFlow<List<Check>> = mutable
    private val runningFlow = MutableStateFlow(false)
    val running: StateFlow<Boolean> = runningFlow

    private fun initial() = listOf(
        Check("android", CheckGroup.PHONE, R.string.diag_android),
        Check("dev", CheckGroup.PHONE, R.string.diag_dev),
        Check("wifi", CheckGroup.PHONE, R.string.diag_wifi),
        Check("wireless", CheckGroup.PHONE, R.string.diag_wireless),
        Check("notifications", CheckGroup.PERMISSIONS, R.string.diag_notifications),
        Check("battery", CheckGroup.PERMISSIONS, R.string.diag_battery),
        Check("writeSettings", CheckGroup.PERMISSIONS, R.string.diag_write_settings),
        Check("paired", CheckGroup.SERVICE, R.string.diag_paired),
        Check("service", CheckGroup.SERVICE, R.string.diag_service),
        Check("version", CheckGroup.SERVICE, R.string.diag_version),
        Check("shell", CheckGroup.SERVICE, R.string.diag_shell),
        Check("testNetwork", CheckGroup.SERVICE, R.string.diag_test_network),
        Check("tethering", CheckGroup.SERVICE, R.string.diag_tethering),
        Check("softAp", CheckGroup.SERVICE, R.string.diag_softap),
        Check("usb", CheckGroup.SERVICE, R.string.diag_usb),
        Check("xray", CheckGroup.SERVICE, R.string.diag_xray),
        Check("tun2socks", CheckGroup.SERVICE, R.string.diag_tun2socks),
        Check("profile", CheckGroup.TUNNEL, R.string.diag_profile),
        Check("reachable", CheckGroup.TUNNEL, R.string.diag_reachable),
        Check("tunnel", CheckGroup.TUNNEL, R.string.diag_tunnel),
    )

    fun run(context: Context) {
        if (runningFlow.value) return
        runningFlow.value = true
        mutable.value = initial()
        scope.launch {
            runCatching { runAll(context.applicationContext) }
            runningFlow.value = false
        }
    }

    private fun set(id: String, state: CheckState, detail: String? = null, fix: Fix? = null) =
        mutable.update { list -> list.map { if (it.id == id) it.copy(state = state, detail = detail, fix = fix) else it } }

    private fun running(id: String) = set(id, CheckState.RUNNING)

    private fun runAll(app: Context) {
        val ui = AppController.state.value
        val str = { res: Int -> app.getString(res) }

        // Телефон
        set("android", if (Build.VERSION.SDK_INT >= 33) CheckState.OK else CheckState.FAIL, "Android ${Build.VERSION.RELEASE}")
        val dev = Settings.Global.getInt(app.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        set("dev", if (dev) CheckState.OK else CheckState.FAIL, null, Fix.DEV_OPTIONS.takeUnless { dev })
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        set("wifi", if (wifi) CheckState.OK else CheckState.WARN, str(R.string.diag_wifi_hint).takeUnless { wifi }, Fix.WIFI.takeUnless { wifi })
        val wireless = Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1
        set(
            "wireless",
            when { wireless -> CheckState.OK; ui.serverAttached -> CheckState.WARN; else -> CheckState.FAIL },
            str(R.string.diag_wireless_hint).takeUnless { wireless },
            Fix.WIRELESS_DEBUGGING.takeUnless { wireless },
        )

        // Разрешения
        val notifications = app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        set("notifications", if (notifications) CheckState.OK else CheckState.FAIL, null, Fix.NOTIFICATIONS.takeUnless { notifications })
        val battery = app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
        set("battery", if (battery) CheckState.OK else CheckState.WARN, str(R.string.diag_battery_hint).takeUnless { battery }, Fix.BATTERY.takeUnless { battery })
        val write = Settings.System.canWrite(app)
        set("writeSettings", if (write) CheckState.OK else CheckState.FAIL, str(R.string.diag_write_settings_hint).takeUnless { write }, Fix.WRITE_SETTINGS.takeUnless { write })

        // Служба
        set("paired", if (ui.paired) CheckState.OK else CheckState.FAIL, null, Fix.PAIR.takeUnless { ui.paired })
        val server = ServerConnection.server
        set("service", if (server != null) CheckState.OK else CheckState.FAIL, null, Fix.LAUNCH.takeIf { server == null })
        val serverChecks = listOf("version", "shell", "testNetwork", "tethering", "softAp", "usb", "xray", "tun2socks", "tunnel")
        var current = true
        if (server == null) {
            serverChecks.forEach { set(it, CheckState.SKIP, str(R.string.diag_needs_service)) }
        } else {
            val apk = ui.status.apk
            current = apk == null || apk == app.applicationInfo.sourceDir
            set("version", if (current) CheckState.OK else CheckState.FAIL, str(R.string.diag_version_hint).takeUnless { current }, Fix.LAUNCH.takeUnless { current })
            // Устаревшая служба отвечает старым кодом — её результаты только запутают.
            if (!current) serverChecks.drop(1).forEach { set(it, CheckState.SKIP, str(R.string.diag_needs_update)) }
        }

        // Профиль и сервер — до сквозной проверки, она самая долгая.
        val profile = AppController.profiles.profiles.value.selected
        set("profile", if (profile != null) CheckState.OK else CheckState.FAIL, profile?.proxy?.name, Fix.PROFILES.takeIf { profile == null })
        if (profile == null) {
            set("reachable", CheckState.SKIP, str(R.string.diag_needs_profile))
        } else {
            running("reachable")
            val started = System.nanoTime()
            val ok = runCatching {
                Socket().use { it.connect(InetSocketAddress(profile.proxy.host, profile.proxy.port), 3000) }
            }.isSuccess
            val ms = (System.nanoTime() - started) / 1_000_000
            set("reachable", if (ok) CheckState.OK else CheckState.FAIL,
                if (ok) app.getString(R.string.ping_ms, ms.toInt()) else "${profile.proxy.host}:${profile.proxy.port}")
        }

        if (server != null && current) {
            serverChecks.drop(1).forEach(::running)
            if (profile == null) set("tunnel", CheckState.SKIP, str(R.string.diag_needs_profile))
            val json = runCatching { JSONObject(server.diagnose(profile?.startLink)) }
            json.onFailure { e -> serverChecks.drop(1).forEach { set(it, CheckState.FAIL, e.message) } }
            json.onSuccess { o ->
                serverChecks.drop(1).forEach { id ->
                    val r = o.optJSONObject(id) ?: return@forEach
                    val detail = r.optString("detail")
                    val shown = if (id == "tunnel" && r.optBoolean("ok")) app.getString(R.string.diag_exit_ip, detail) else detail
                    // Без USB работает раздача по Wi-Fi, поэтому это предупреждение, а не ошибка.
                    val bad = if (id == "usb") CheckState.WARN else CheckState.FAIL
                    set(id, if (r.optBoolean("ok")) CheckState.OK else bad, shown)
                }
            }
        }
    }
}
