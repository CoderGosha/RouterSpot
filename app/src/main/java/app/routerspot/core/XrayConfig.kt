package app.routerspot.core

import org.json.JSONArray
import org.json.JSONObject

/** Конфиг Xray: SOCKS5 на localhost для tun2socks, весь трафик — на сервер профиля. */
object XrayConfig {
    const val SOCKS_PORT = 10808

    fun build(link: ProxyLink, serverIp: String, verbose: Boolean = false): String = JSONObject()
        .put("log", JSONObject().put("loglevel", if (verbose) "debug" else "warning"))
        .put("inbounds", JSONArray().put(JSONObject()
            .put("tag", "socks")
            .put("listen", "127.0.0.1")
            .put("port", SOCKS_PORT)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("ip", "127.0.0.1"))))
        .put("outbounds", JSONArray()
            .put(link.toOutbound(serverIp))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
            .apply { if (!link.udp) put(dnsOverTcp()) })
        .apply {
            // A/AAAA из dns-выхода Xray разрешает сам. Без настройки — системным DNS, а домашний
            // роутер может отдавать свои fake-IP (198.18.x), бесполезные на сервере: спрашиваем через туннель.
            if (!link.udp) put("dns", JSONObject()
                .put("servers", JSONArray().put("https://1.1.1.1/dns-query"))
                .put("queryStrategy", "UseIPv4"))
        }
        .put("routing", JSONObject().put("rules", JSONArray()
            // Всё, что не IPv4/доменное, режем: прямого выхода нет в принципе.
            .put(JSONObject().put("ip", JSONArray().put("::/0")).put("outboundTag", "block"))
            .apply {
                if (link.udp) return@apply
                // Протокол без UDP: DNS-запросы клиентов уходят на сервер по TCP, прочий UDP
                // (QUIC и т.п.) режем — приложения сами откатываются на TCP.
                put(JSONObject().put("network", "udp").put("port", "53").put("outboundTag", "dns"))
                put(JSONObject().put("network", "udp").put("outboundTag", "block"))
            }))
        .toString(2)

    /** DNS по UDP → DNS по TCP к тому же резолверу, соединение идёт через прокси. */
    private fun dnsOverTcp() = JSONObject()
        .put("tag", "dns")
        .put("protocol", "dns")
        .put("settings", JSONObject().put("network", "tcp"))
        .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("dialerProxy", "proxy")))

    /** Конфиг hev-socks5-tunnel: пакеты из TUN → SOCKS5 Xray, UDP через UDP ASSOCIATE. */
    fun tunnel(mtu: Int = 1500, verbose: Boolean = false): String = """
        |tunnel:
        |  mtu: $mtu
        |socks5:
        |  port: $SOCKS_PORT
        |  address: 127.0.0.1
        |  udp: 'udp'
        |misc:
        |  task-stack-size: 24576
        |  log-level: ${if (verbose) "debug" else "warn"}
        |""".trimMargin()
}
