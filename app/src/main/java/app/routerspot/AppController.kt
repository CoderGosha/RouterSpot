package app.routerspot

import android.content.Context
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import android.provider.Settings
import app.routerspot.adb.AdbDiscovery
import app.routerspot.adb.AdbManager
import app.routerspot.adb.ServerLauncher
import app.routerspot.core.TetherGuard
import app.routerspot.data.ProfileStore
import app.routerspot.data.ServerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class UiState(
    val paired: Boolean = false,
    val devMode: Boolean = false,
    val wifi: Boolean = false,
    val wirelessDebugging: Boolean = false,
    val serverAttached: Boolean = false,
    val status: ServerStatus = ServerStatus(),
    val downSpeed: Long = 0,
    val upSpeed: Long = 0,
    /** Длительная операция в процессе: текст для кнопки/статуса. */
    val busy: String? = null,
    val error: String? = null,
    /** Служебный процесс погиб во время раздачи: сторож уже выключил хотспот. */
    val serviceLost: Boolean = false,
    /** Выбранный способ раздачи: USB вместо Wi-Fi. */
    val usbMode: Boolean = false,
    /** Телефон подключён кабелем к другому устройству. */
    val usbCable: Boolean = false,
) {
    val sharing get() = status.state == ServerStatus.State.RUNNING || status.state == ServerStatus.State.RECONNECTING
    /** Первый незавершённый шаг настройки (1–4) или 5, если всё готово. */
    val setupStep get() = when {
        serverAttached -> 5
        paired -> 4
        !devMode -> 1
        !wirelessDebugging -> 2
        else -> 3
    }
    /** Раздача идёт, но через сервер не проходят данные (подключение есть, ответа нет). */
    val stalled get() = sharing && !status.trafficOk
    val alert get() = serviceLost || status.state != ServerStatus.State.IDLE && status.state != ServerStatus.State.RUNNING
}

/** Единое состояние приложения для интерфейса и действия над служебным процессом. */
object AppController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutable

    lateinit var profiles: ProfileStore
        private set
    private lateinit var app: Context
    private var lastSample: Triple<Long, Long, Long>? = null // время, down, up

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        profiles = ProfileStore(app)
        mutable.update { it.copy(paired = prefs().getBoolean(KEY_PAIRED, false), usbMode = uiPrefs().getBoolean(KEY_USB_MODE, false)) }
        ServerConnection.onDied = { wasActive -> mutable.update { it.copy(serviceLost = wasActive, serverAttached = false) } }
        scope.launch {
            while (true) {
                refresh()
                delay(1000)
            }
        }
    }

    private fun refresh() {
        val server = ServerConnection.server
        val status = server?.let { runCatching { ServerStatus.parse(it.status()) }.getOrNull() }
        val wireless = Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val dev = Settings.Global.getInt(app.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        // Служба от прошлой версии приложения: пока раздачи нет, тихо перезапускаем новой.
        if (status != null && status.state == ServerStatus.State.IDLE && wireless &&
            status.apk != null && status.apk != app.applicationInfo.sourceDir && mutable.value.busy == null
        ) {
            action(app.getString(R.string.busy_updating)) { ServerLauncher.launch(app) }
        }
        mutable.update { s ->
            var down = 0L
            var up = 0L
            if (status != null && status.state == ServerStatus.State.RUNNING) {
                val now = System.currentTimeMillis()
                lastSample?.let { (t, d, u) ->
                    val dt = (now - t).coerceAtLeast(1)
                    down = ((status.downBytes - d).coerceAtLeast(0) * 1000) / dt
                    up = ((status.upBytes - u).coerceAtLeast(0) * 1000) / dt
                }
                lastSample = Triple(now, status.downBytes, status.upBytes)
            } else {
                lastSample = null
            }
            s.copy(
                serverAttached = server != null,
                devMode = dev,
                wifi = wifi,
                wirelessDebugging = wireless,
                usbCable = usbConnected(),
                status = status ?: if (server == null) ServerStatus() else s.status,
                downSpeed = down,
                upSpeed = up,
            )
        }
    }

    /** Кнопка «Раздавать VPN»: при необходимости запускает службу, затем туннель и хотспот. */
    fun startSharing() = action(app.getString(R.string.busy_starting)) { share() }

    /** Туннель и раздача выбранным способом. */
    private suspend fun share() {
        val profile = profiles.profiles.value.selected ?: error(app.getString(R.string.err_no_profile))
        val usb = mutable.value.usbMode
        if (usb && !usbConnected()) error(app.getString(R.string.err_usb_cable))
        val server = if (usb) ensureUsbHost() else ensureServer()
        server.start(profile.startLink)?.let { error(it) }
        // Сторож должен быть готов раньше, чем появится раздача.
        if (usb) UsbGuardService.arm(app, server.asBinder())
        server.setTethering(if (usb) TetherGuard.TETHERING_USB else TetherGuard.TETHERING_WIFI, true)?.let { error(it) }
        RouterSpotService.start(app)
        mutable.update { it.copy(serviceLost = false) }
    }

    fun setUsbMode(usb: Boolean) {
        uiPrefs().edit().putBoolean(KEY_USB_MODE, usb).apply()
        mutable.update { it.copy(usbMode = usb) }
    }

    /** Sticky-рассылка USB_STATE: connected — кабель подключён к хосту, а не просто к зарядке. */
    private fun usbConnected(): Boolean = runCatching {
        app.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))?.getBooleanExtra("connected", false)
    }.getOrNull() == true

    fun stopSharing() = action(app.getString(R.string.busy_stopping)) {
        ServerConnection.server?.stop()
        mutable.update { it.copy(serviceLost = false) }
    }

    /** «Переподключить» на экране обрыва: перезапуск туннеля с тем же профилем. */
    fun reconnect() = action(app.getString(R.string.busy_reconnecting)) {
        ServerConnection.server?.stop()
        share()
    }

    /** Выбор профиля; во время раздачи сервер меняется на ходу, при неудаче выбор откатывается. */
    fun selectProfile(id: String) {
        val previous = profiles.profiles.value.selected?.id
        if (id == previous) return
        profiles.select(id)
        applyLive { previous?.let(profiles::select) }
    }

    /** TLS-отпечаток профиля; у активного профиля во время раздачи применяется сразу. */
    fun setFingerprint(id: String, fp: String?) {
        val previous = profiles.profiles.value.items.firstOrNull { it.id == id }?.fp
        profiles.setFingerprint(id, fp)
        if (id == profiles.profiles.value.selected?.id) applyLive { profiles.setFingerprint(id, previous) }
    }

    /** Перезапуск Xray с выбранным профилем, если раздача идёт; [revert] — откат выбора при ошибке. */
    private fun applyLive(revert: () -> Unit) {
        if (!state.value.sharing) return
        action(app.getString(R.string.busy_switching)) {
            val profile = profiles.profiles.value.selected ?: error(app.getString(R.string.err_no_profile))
            val failure = (ServerConnection.server ?: return@action).start(profile.startLink)
            if (failure != null) {
                revert()
                error(failure)
            }
        }
    }

    fun launchServer() = action(app.getString(R.string.busy_launching)) { ensureServer() }

    /** Сопряжение кодом, введённым в приложении (диалог в настройках должен быть открыт). */
    fun pair(code: String) = action(app.getString(R.string.busy_pairing)) {
        val endpoint = AdbDiscovery.find(app, AdbDiscovery.PAIRING, 10_000)
            ?: error(app.getString(R.string.err_open_pairing))
        check(AdbManager.get(app).pair(endpoint.host, endpoint.port, code)) { app.getString(R.string.err_wrong_code) }
        onPaired()
        ensureServer()
    }

    fun onPaired() {
        prefs().edit().putBoolean(KEY_PAIRED, true).apply()
        mutable.update { it.copy(paired = true) }
    }

    /** Язык выбирается на первом экране; до выбора показываем экран выбора. */
    var languageChosen: Boolean
        get() = uiPrefs().getBoolean("language", false)
        set(value) = uiPrefs().edit().putBoolean("language", value).apply()

    /** Гайд при первом запуске показывается один раз. */
    var onboarded: Boolean
        get() = uiPrefs().getBoolean("onboarded", false)
        set(value) = uiPrefs().edit().putBoolean("onboarded", value).apply()

    /** Предупреждение о неполной защите в режиме USB показывается один раз. */
    var usbWarned: Boolean
        get() = uiPrefs().getBoolean("usb_warned", false)
        set(value) = uiPrefs().edit().putBoolean("usb_warned", value).apply()

    fun dismissError() = mutable.update { it.copy(error = null, serviceLost = false) }

    /** USB-служба: при необходимости её запускает служба shell, та после этого уже не нужна. */
    private suspend fun ensureUsbHost(): IRouterSpotServer {
        if (ServerConnection.usbHosted) return ServerConnection.server!!
        ensureServer().startUsbHost(Process.myUid())?.let { error(it) }
        return withTimeoutOrNull(USB_HOST_TIMEOUT_MS) {
            while (!ServerConnection.usbHosted) delay(100)
            ServerConnection.server
        } ?: error(app.getString(R.string.err_no_answer))
    }

    /** Служба shell. USB-служба её не заменяет: при подключении новая служба сменит USB-службу. */
    private suspend fun ensureServer(): IRouterSpotServer {
        ServerConnection.server?.takeUnless { ServerConnection.usbHosted }?.let { server ->
            if (!isStale(server)) return server
            // Служба от прошлой версии ищет Xray в удалённом APK — её нужно перезапустить.
            if (!mutable.value.wirelessDebugging) error(app.getString(R.string.err_service_outdated))
        }
        mutable.update { it.copy(busy = app.getString(R.string.busy_launching)) }
        ServerLauncher.launch(app)
        onPaired() // подключение по ADB удалось — значит, сопряжение есть
        return withTimeoutOrNull(10_000) {
            var server = ServerConnection.server
            while (server == null || ServerConnection.usbHosted || isStale(server)) {
                delay(100)
                server = ServerConnection.server
            }
            server
        } ?: error(app.getString(R.string.err_no_answer))
    }

    private fun isStale(server: IRouterSpotServer): Boolean {
        val apk = runCatching { ServerStatus.parse(server.status()).apk }.getOrNull()
        return apk != null && apk != app.applicationInfo.sourceDir
    }

    private fun action(busy: String, block: suspend () -> Unit) {
        if (mutable.value.busy != null) return
        mutable.update { it.copy(busy = busy, error = null) }
        scope.launch {
            val result = runCatching { block() }
            refresh()
            mutable.update { it.copy(busy = null, error = result.exceptionOrNull()?.let(::describe)) }
        }
    }

    private fun describe(t: Throwable): String = when {
        t.message?.contains("wireless debugging is off") == true -> app.getString(R.string.err_wireless_off)
        t.message?.contains("not paired") == true -> app.getString(R.string.err_not_paired)
        t.message?.contains("passes no traffic") == true -> app.getString(R.string.err_no_traffic)
        else -> t.message ?: t.toString()
    }

    private fun prefs() = app.getSharedPreferences("adb", Context.MODE_PRIVATE)
    private fun uiPrefs() = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private const val KEY_PAIRED = "paired"
    private const val KEY_USB_MODE = "usb_mode"
    private const val USB_HOST_TIMEOUT_MS = 20_000L
}
