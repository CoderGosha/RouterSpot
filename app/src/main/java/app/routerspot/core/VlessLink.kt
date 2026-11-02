package app.routerspot.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Разобранная ссылка vless://uuid@host:port?params#name. */
data class VlessLink(
    override val name: String,
    val uuid: String,
    override val host: String,
    override val port: Int,
    val params: Map<String, String>,
) : ProxyLink {
    val security get() = params["security"] ?: "none"
    val network get() = params["type"] ?: "tcp"
    override val tags get() = listOfNotNull("vless", security, network, params["flow"]?.takeIf { it.isNotEmpty() })
    override val udp get() = true

    /** TLS-отпечаток uTLS; null — у профиля нет TLS. */
    val fingerprint: String? get() = if (security == "reality" || security == "tls") params["fp"] ?: "chrome" else null

    fun withFingerprint(fp: String) = copy(params = params + ("fp" to fp))

    override fun toOutbound(address: String): JSONObject {
        val user = JSONObject()
            .put("id", uuid)
            .put("encryption", params["encryption"] ?: "none")
        params["flow"]?.takeIf { it.isNotEmpty() }?.let { user.put("flow", it) }

        val stream = JSONObject().put("network", network).put("security", security)
        val serverName = params["sni"] ?: params["host"] ?: host
        when (security) {
            "reality" -> stream.put("realitySettings", JSONObject()
                .put("serverName", serverName)
                .put("fingerprint", fingerprint)
                .put("publicKey", params["pbk"] ?: error("reality: pbk missing"))
                .put("shortId", params["sid"] ?: "")
                .put("spiderX", params["spx"] ?: ""))
            "tls" -> stream.put("tlsSettings", JSONObject()
                .put("serverName", serverName)
                .put("fingerprint", fingerprint)
                .put("allowInsecure", params["allowInsecure"] == "1")
                .apply { params["alpn"]?.let { put("alpn", JSONArray(it.split(','))) } })
        }
        when (network) {
            "ws" -> stream.put("wsSettings", JSONObject()
                .put("path", params["path"] ?: "/")
                .put("host", params["host"] ?: ""))
            "grpc" -> stream.put("grpcSettings", JSONObject()
                .put("serviceName", params["serviceName"] ?: "")
                .put("multiMode", params["mode"] == "multi"))
            "xhttp", "splithttp" -> stream.put("xhttpSettings", JSONObject()
                .put("path", params["path"] ?: "/")
                .put("host", params["host"] ?: "")
                .put("mode", params["mode"] ?: "auto"))
            "tcp" -> if (params["headerType"] == "http") stream.put("tcpSettings", JSONObject()
                .put("header", JSONObject().put("type", "http")))
        }

        return JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", address)
                .put("port", port)
                .put("users", JSONArray().put(user)))))
            .put("streamSettings", stream)
    }

    companion object {
        const val SAFARI = "safari"

        /** Ссылка с заменённым параметром fp (остальное как было, вместе с #именем). */
        fun withFingerprint(link: String, fp: String): String {
            val body = link.trim().substringBefore('#')
            val fragment = link.trim().substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
            val base = body.substringBefore('?')
            val query = body.substringAfter('?', "").split('&').filter { it.isNotEmpty() && !it.startsWith("fp=") } + "fp=$fp"
            return "$base?${query.joinToString("&")}$fragment"
        }

        fun parse(link: String): VlessLink {
            val uri = URI(link.trim())
            require(uri.scheme.equals("vless", ignoreCase = true)) { "not a vless:// link" }
            val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                val (k, v) = (it.split('=', limit = 2) + "").take(2)
                decode(k) to decode(v)
            }
            return VlessLink(
                name = uri.rawFragment?.let(::decode) ?: uri.host,
                uuid = decode(uri.rawUserInfo ?: error("uuid missing")),
                host = uri.host?.removeSurrounding("[", "]") ?: error("host missing"),
                port = uri.port.takeIf { it > 0 } ?: 443,
                params = params,
            )
        }

        private fun decode(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}
