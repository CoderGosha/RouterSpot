package app.routerspot.ui

import androidx.compose.ui.res.stringResource
import app.routerspot.AppController
import app.routerspot.R
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.routerspot.core.VlessLink
import app.routerspot.data.Profile
import app.routerspot.data.ProfileStore
import app.routerspot.data.Profiles
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Вкладка «Профили»: импорт ссылок vless:// и naive из буфера или QR, выбор и проверка. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(store: ProfileStore, profiles: Profiles) {
    val c = colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val pings = remember { mutableStateMapOf<String, Long>() } // мс, -1 — недоступен
    var checking by remember { mutableStateOf(false) }

    val badLink = stringResource(R.string.profiles_bad_link)
    val scanPrompt = stringResource(R.string.profiles_scan_prompt)

    fun import(text: String?) {
        message = runCatching { store.add(text ?: error("empty")) }
            .fold({ null }, { badLink })
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(::import) }

    Screen(
        footer = {
            Cta(stringResource(if (checking) R.string.profiles_checking else R.string.profiles_check_all), Icons.refresh, CtaStyle.SECONDARY, enabled = !checking && profiles.items.isNotEmpty()) {
                checking = true
                scope.launch {
                    profiles.items.map { p -> async(Dispatchers.IO) { p.id to tcpPing(p) } }.forEach { pings += it.await() }
                    checking = false
                }
            }
        },
        spacing = 20.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(stringResource(R.string.profiles_label, profiles.items.size))
            Text(stringResource(R.string.profiles_title), Type.title)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ImportButton(stringResource(R.string.profiles_from_clipboard), Icons.clipboard, Modifier.weight(1f)) {
                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                import(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString())
            }
            ImportButton(stringResource(R.string.profiles_qr), Icons.qr, Modifier.weight(1f)) {
                scanner.launch(ScanOptions().setPrompt(scanPrompt).setBeepEnabled(false).setOrientationLocked(false))
            }
        }
        message?.let { Text(it, Type.small, c.alarm) }

        if (profiles.items.isEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label(stringResource(R.string.profiles_empty_label))
                    Text(stringResource(R.string.profiles_empty_text), Type.small, c.mute)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            profiles.items.forEach { profile ->
                val selected = profile.id == profiles.selected?.id
                ProfileCard(profile, selected, pings[profile.id], onSelect = { AppController.selectProfile(profile.id) }, onDelete = { store.remove(profile.id) })
            }
        }
    }
}

@Composable
private fun ImportButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val c = colors
    Row(
        modifier.height(56.dp).background(c.card).border(1.dp, c.line).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, c.ink)
        Text(text, Type.body.copy(fontSize = 14.sp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(profile: Profile, selected: Boolean, ping: Long?, onSelect: () -> Unit, onDelete: () -> Unit) {
    val c = colors
    val v = profile.proxy
    val pingText = ping?.let { if (it >= 0) stringResource(R.string.ping_ms, it.toInt()) else stringResource(R.string.profiles_unreachable) }
    Card(border = if (selected) c.ink else c.line, onClick = onSelect) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(v.name, Type.bodyBold)
                    Text("${v.host}:${v.port}", Type.mono.copy(fontSize = 11.sp), if (ping == -1L) c.alarm else c.mute)
                }
                if (selected) Chip(stringResource(R.string.profiles_active), on = true) else Icon(Icons.chevron, c.ink)
            }
            if (selected) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    v.tags.drop(1).forEach { Chip(it) }
                    pingText?.let { Chip(it) }
                }
                if (profile.hasFingerprint) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Label(stringResource(R.string.profiles_fp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FINGERPRINTS.forEach { fp ->
                                val text = if (fp == Profile.FP_LINK) stringResource(R.string.profiles_fp_link) else fp
                                Chip(text, on = profile.fingerprint == fp,
                                    modifier = Modifier.clickable(role = Role.Button) { AppController.setFingerprint(profile.id, fp.takeIf { it != VlessLink.SAFARI }) })
                            }
                        }
                    }
                }
                Text(stringResource(R.string.profiles_delete), Type.small, c.alarm, Modifier.clickable(role = Role.Button, onClick = onDelete))
            } else if (pingText != null) {
                Text((v.tags.drop(1) + pingText).joinToString(" · "), Type.mono.copy(fontSize = 11.sp), if (ping == -1L) c.alarm else c.mute)
            }
        }
    }
}

/** Время TCP-подключения к серверу; -1 — недоступен. Рукопожатие VLESS не проверяется. */
private suspend fun tcpPing(profile: Profile): Long = withContext(Dispatchers.IO) {
    val started = System.nanoTime()
    runCatching { Socket().use { it.connect(InetSocketAddress(profile.proxy.host, profile.proxy.port), 3000) } }
        .fold({ (System.nanoTime() - started) / 1_000_000 }, { -1L })
}

/** safari первым: короткий ClientHello без постквантового ключа проходит DPI, где chrome режут. */
private val FINGERPRINTS = listOf(VlessLink.SAFARI, Profile.FP_LINK, "chrome", "firefox", "ios")
