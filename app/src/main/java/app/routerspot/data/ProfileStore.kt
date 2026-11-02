package app.routerspot.data

import android.content.Context
import app.routerspot.core.ProxyLink
import app.routerspot.core.VlessLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** [fp] — выбранный TLS-отпечаток: null — по умолчанию safari, [FP_LINK] — как в ссылке. */
data class Profile(val id: String, val link: String, val fp: String? = null) {
    val proxy: ProxyLink by lazy { ProxyLink.parse(link) }

    /** Есть ли у профиля TLS-отпечаток, который можно выбирать (VLESS с reality/tls). */
    val hasFingerprint get() = (proxy as? VlessLink)?.fingerprint != null

    val fingerprint get() = fp ?: VlessLink.SAFARI

    /** Ссылка для службы: с выбранным отпечатком вместо указанного в ссылке. */
    val startLink: String get() =
        if (hasFingerprint && fingerprint != FP_LINK) VlessLink.withFingerprint(link, fingerprint) else link

    companion object {
        const val FP_LINK = "link"
    }
}

data class Profiles(val items: List<Profile> = emptyList(), val selectedId: String? = null) {
    val selected: Profile? get() = items.firstOrNull { it.id == selectedId } ?: items.firstOrNull()
}

/** Профили серверов в SharedPreferences: список ссылок и выбранный профиль. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val profiles: StateFlow<Profiles> = state

    /** Добавляет профиль из ссылки; бросает исключение, если ссылка не vless:// и не naive. */
    fun add(link: String): Profile {
        val trimmed = link.trim()
        ProxyLink.parse(trimmed)
        val profile = state.value.items.firstOrNull { it.link == trimmed } ?: Profile(UUID.randomUUID().toString(), trimmed)
        val items = state.value.items.filterNot { it.id == profile.id } + profile
        save(Profiles(items, state.value.selectedId ?: profile.id))
        return profile
    }

    fun select(id: String) = save(state.value.copy(selectedId = id))

    fun setFingerprint(id: String, fp: String?) =
        save(state.value.copy(items = state.value.items.map { if (it.id == id) it.copy(fp = fp) else it }))

    fun remove(id: String) {
        val items = state.value.items.filterNot { it.id == id }
        save(Profiles(items, state.value.selectedId.takeIf { it != id } ?: items.firstOrNull()?.id))
    }

    private fun save(profiles: Profiles) {
        val array = JSONArray()
        profiles.items.forEach { array.put(JSONObject().put("id", it.id).put("link", it.link).put("fp", it.fp)) }
        prefs.edit().putString("items", array.toString()).putString("selected", profiles.selectedId).apply()
        state.value = profiles
    }

    private fun load(): Profiles {
        val array = JSONArray(prefs.getString("items", "[]"))
        val items = (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            Profile(o.getString("id"), o.getString("link"), o.optString("fp").ifEmpty { null }).takeIf { runCatching { it.proxy }.isSuccess }
        }
        return Profiles(items, prefs.getString("selected", null))
    }
}
