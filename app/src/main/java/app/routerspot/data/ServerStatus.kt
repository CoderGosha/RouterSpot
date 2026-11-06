package app.routerspot.data

import org.json.JSONObject

/** Снимок состояния служебного процесса (см. ShellServer.statusJson). */
data class ServerStatus(
    val state: State = State.IDLE,
    val startedAt: Long = 0,
    val downBytes: Long = 0,
    val upBytes: Long = 0,
    val pingMs: Long = -1,
    /** Проходит ли трафик через VLESS по последним проверкам службы. */
    val trafficOk: Boolean = true,
    /** Есть активный интерфейс раздачи: поднятый хотспот или USB с подключённым кабелем. */
    val hotspot: Boolean = false,
    /** Раздача запущена через USB, а не Wi-Fi. */
    val usb: Boolean = false,
    val clients: Int = 0,
    val ssid: String? = null,
    val passphrase: String? = null,
    val failure: String? = null,
    val log: List<String> = emptyList(),
    /** APK, из которого запущен служебный процесс: после обновления приложения он устаревает. */
    val apk: String? = null,
) {
    enum class State { IDLE, RUNNING, RECONNECTING, FAILED }

    companion object {
        fun parse(json: String): ServerStatus {
            val o = JSONObject(json)
            val log = o.optJSONArray("log")
            return ServerStatus(
                state = State.valueOf(o.getString("state").uppercase()),
                startedAt = o.optLong("startedAt"),
                downBytes = o.optLong("downBytes"),
                upBytes = o.optLong("upBytes"),
                pingMs = o.optLong("pingMs", -1),
                trafficOk = o.optBoolean("traffic", true),
                hotspot = o.optBoolean("hotspot"),
                usb = o.optBoolean("usb"),
                clients = o.optInt("clients"),
                ssid = o.optString("ssid").takeIf { o.has("ssid") && !o.isNull("ssid") },
                passphrase = o.optString("passphrase").takeIf { o.has("passphrase") && !o.isNull("passphrase") },
                failure = o.optString("failure").takeIf { o.has("failure") && !o.isNull("failure") },
                log = log?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                apk = o.optString("apk").takeIf { o.has("apk") && !o.isNull("apk") },
            )
        }
    }
}
