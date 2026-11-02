package app.routerspot.core

import org.json.JSONObject
import java.net.URI

/** Ссылка на сервер: vless:// или naive (https://user:pass@host). */
sealed interface ProxyLink {
    val name: String
    val host: String
    val port: Int

    /** Протокол и транспорт для подписи профиля: «vless · reality · tcp». */
    val tags: List<String>

    /** Пропускает ли протокол UDP; если нет, DNS идёт через сервер по TCP, остальной UDP режется. */
    val udp: Boolean

    /** Исходящее подключение Xray с тегом proxy; [address] — заранее разрешённый IP сервера. */
    fun toOutbound(address: String = host): JSONObject

    companion object {
        fun parse(link: String): ProxyLink = when (URI(link.trim()).scheme?.lowercase()) {
            "vless" -> VlessLink.parse(link)
            "https", "naive+https" -> NaiveLink.parse(link)
            else -> throw IllegalArgumentException("unsupported link")
        }
    }
}
