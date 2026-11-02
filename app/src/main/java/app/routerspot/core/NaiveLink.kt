package app.routerspot.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * NaiveProxy-сервер: https://user:pass@host:port?peer=sni#name (или naive+https://).
 * Xray ходит к нему как к HTTPS-прокси — HTTP/2 CONNECT поверх TLS с отпечатком Chrome.
 */
data class NaiveLink(
    override val name: String,
    val user: String,
    val pass: String,
    override val host: String,
    override val port: Int,
    val sni: String,
) : ProxyLink {
    override val tags get() = listOf("naive", "https")
    override val udp get() = false

    override fun toOutbound(address: String): JSONObject = JSONObject()
        .put("tag", "proxy")
        .put("protocol", "http")
        .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject()
            .put("address", address)
            .put("port", port)
            .put("users", JSONArray().put(JSONObject().put("user", user).put("pass", pass))))))
        .put("streamSettings", JSONObject()
            .put("network", "tcp")
            .put("security", "tls")
            .put("tlsSettings", JSONObject()
                .put("serverName", sni)
                .put("fingerprint", "chrome")
                .put("alpn", JSONArray().put("h2").put("http/1.1"))))

    companion object {
        fun parse(link: String): NaiveLink {
            val uri = URI(link.trim())
            val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                val (k, v) = (it.split('=', limit = 2) + "").take(2)
                decode(k) to decode(v)
            }
            val (user, pass) = (uri.rawUserInfo ?: error("credentials missing")).split(':', limit = 2)
                .let { decode(it[0]) to decode(it.getOrElse(1) { "" }) }
            val host = uri.host?.removeSurrounding("[", "]") ?: error("host missing")
            return NaiveLink(
                name = uri.rawFragment?.let(::decode) ?: host,
                user = user,
                pass = pass,
                host = host,
                port = uri.port.takeIf { it > 0 } ?: 443,
                sni = params["peer"] ?: params["sni"] ?: host,
            )
        }

        private fun decode(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}
