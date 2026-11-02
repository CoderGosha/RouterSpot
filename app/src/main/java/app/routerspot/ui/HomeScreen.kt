package app.routerspot.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.routerspot.R
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.routerspot.UiState
import app.routerspot.data.Profiles
import app.routerspot.data.ServerStatus
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.foundation.text.BasicText

@Composable
private fun chainNodes() = listOf(
    Triple(Icons.wifi, "", stringResource(R.string.node_hotspot)),
    Triple(null, "TUN", stringResource(R.string.node_tunnel)),
    Triple(Icons.shield, "", stringResource(R.string.node_vpn)),
)

/** Вкладка «Раздача»: выключено / активно / обрыв туннеля. */
@Composable
fun HomeScreen(
    state: UiState,
    profiles: Profiles,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenSystem: () -> Unit,
) {
    when {
        state.alert -> AlertContent(state, onReconnect, onStop)
        state.stalled -> StalledContent(state, onReconnect, onStop)
        state.sharing -> ActiveContent(state, onStop)
        else -> IdleContent(state, profiles, onStart, onOpenProfiles, onOpenSystem)
    }
}

/** Компактная отметка защиты: точка, название и состояние в одну строку. */
@Composable
private fun Guard(title: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(Dot.ON)
        Text(title, Type.small.copy(fontWeight = FontWeight.SemiBold))
        Text(value.uppercase(), Type.mono, colors.mute)
    }
}

@Composable
private fun IdleContent(state: UiState, profiles: Profiles, onStart: () -> Unit, onOpenProfiles: () -> Unit, onOpenSystem: () -> Unit) {
    val c = colors
    Screen(
        footer = {
            when {
                !state.paired -> Cta(stringResource(R.string.cta_setup_pairing), Icons.arrow, onClick = onOpenSystem)
                profiles.selected == null -> Cta(stringResource(R.string.cta_add_profile), Icons.arrow, onClick = onOpenProfiles)
                else -> Cta(state.busy ?: stringResource(R.string.cta_share), Icons.arrow, enabled = state.busy == null, onClick = onStart)
            }
            state.error?.let { Text(it, Type.small, c.alarm) }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(stringResource(R.string.home_idle_label))
            Text(stringResource(R.string.home_idle_title).replace('\n', ' '), Type.title, maxLines = 1)
        }
        Chain(chainNodes(), List(3) { NodeState.OFF }, List(2) { NodeState.OFF })
        StatusRows {
            StatusRow("VPN", stringResource(R.string.val_disconnected), Dot.OFF)
            StatusRow(stringResource(R.string.row_hotspot), if (state.status.hotspot) stringResource(R.string.val_on) else stringResource(R.string.val_off), if (state.status.hotspot) Dot.ON else Dot.OFF)
            StatusRow(
                stringResource(R.string.row_service),
                when { state.serverAttached -> stringResource(R.string.val_ready); state.paired -> stringResource(R.string.val_not_running); else -> stringResource(R.string.val_not_paired) },
                if (state.serverAttached) Dot.ON else Dot.OFF,
            )
        }
        val profile = profiles.selected
        Card(onClick = onOpenProfiles) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Label(stringResource(R.string.profile_label), modifier = Modifier)
                    Text(profile?.proxy?.name ?: stringResource(R.string.no_profile), Type.bodyBold)
                    profile?.let { Text(it.proxy.tags.joinToString(" · "), Type.mono.copy(fontSize = 11.sp), c.mute) }
                }
                Icon(Icons.chevron, c.ink)
            }
        }
    }
}

@Composable
private fun ActiveContent(state: UiState, onStop: () -> Unit) {
    val c = colors
    val s = state.status
    var showPassword by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    Screen(
        footer = {
            Cta(state.busy ?: stringResource(R.string.cta_stop), Icons.stop, CtaStyle.SECONDARY, enabled = state.busy == null, onClick = onStop)
            state.error?.let { Text(it, Type.small, c.alarm) }
        },
        spacing = 16.dp,
    ) {
        // Экран раздачи должен помещаться без прокрутки: заголовок в одну строку, аптайм в подписи.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(stringResource(R.string.home_active_label).removeSuffix(" ▮") + " · " + stringResource(R.string.home_uptime, uptime(s.startedAt)) + " ▮")
            Text(stringResource(R.string.home_active_title).replace('\n', ' '), Type.title, maxLines = 1)
        }
        Chain(
            listOf(
                Triple(Icons.wifi, "", pluralStringResource(R.plurals.clients, s.clients, s.clients)),
                Triple(null, "TUN", "TCP·UDP·DNS"),
                Triple(Icons.shield, "", if (s.pingMs >= 0) stringResource(R.string.ping_ms, s.pingMs.toInt()) else stringResource(R.string.node_vpn)),
            ),
            List(3) { NodeState.ON }, List(2) { NodeState.ON },
        )
        Row(Modifier.fillMaxWidth().background(c.line).border(1.dp, c.line), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            Stat(stringResource(R.string.stat_down), state.downSpeed, Modifier.weight(1f))
            Stat(stringResource(R.string.stat_up), state.upSpeed, Modifier.weight(1f))
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Label(stringResource(R.string.wifi_label))
                    Text(s.ssid ?: "—", Type.bodyBold)
                    val pass = s.passphrase
                    if (pass != null) {
                        Text(
                            if (showPassword) stringResource(R.string.wifi_pass_shown, pass) else stringResource(R.string.wifi_pass_hidden),
                            Type.mono, c.mute,
                            Modifier.clickable(role = Role.Button) { showPassword = !showPassword },
                        )
                    }
                }
                if (s.ssid != null) {
                    Box(
                        Modifier.size(44.dp).background(c.card).border(1.dp, c.line).clickable(role = Role.Button) { showQr = true },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.qr, c.ink, 22.dp) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Guard(stringResource(R.string.row_killswitch), stringResource(R.string.val_on))
            Guard(stringResource(R.string.row_ipv6), stringResource(R.string.val_blocked))
        }
    }
    if (showQr && s.ssid != null) WifiQrDialog(s.ssid, s.passphrase) { showQr = false }
}

/** Раздача идёт, но сервер не пропускает данные: показываем это, а не зелёный статус. */
@Composable
private fun StalledContent(state: UiState, onReconnect: () -> Unit, onStop: () -> Unit) {
    val c = colors
    val s = state.status
    Screen(
        header = { Header(alarm = true) },
        footer = {
            Cta(state.busy ?: stringResource(R.string.cta_reconnect), Icons.refresh, enabled = state.busy == null, onClick = onReconnect)
            Cta(stringResource(R.string.cta_stop_sharing), Icons.stop, CtaStyle.SECONDARY, enabled = state.busy == null, onClick = onStop)
            state.error?.let { Text(it, Type.small, c.alarm) }
        },
        spacing = 20.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(stringResource(R.string.stalled_label) + " · " + stringResource(R.string.home_uptime, uptime(s.startedAt)), c.alarm)
            Text(stringResource(R.string.stalled_title), Type.title, maxLines = 1)
            Text(stringResource(R.string.stalled_text), Type.body.copy(fontSize = 14.sp), c.mute)
        }
        Chain(
            listOf(
                Triple(Icons.wifi, "", pluralStringResource(R.plurals.clients, s.clients, s.clients)),
                Triple(null, "TUN", "TCP·UDP·DNS"),
                // Пинг меряет только установку соединения — при блокировке он «в норме» и вводит в заблуждение.
                Triple(Icons.alert, "", stringResource(R.string.node_vpn)),
            ),
            listOf(NodeState.ON, NodeState.ON, NodeState.BAD),
            listOf(NodeState.ON, NodeState.BAD),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Guard(stringResource(R.string.row_killswitch), stringResource(R.string.val_on))
            Guard(stringResource(R.string.row_ipv6), stringResource(R.string.val_blocked))
        }
    }
}

@Composable
private fun AlertContent(state: UiState, onReconnect: () -> Unit, onStop: () -> Unit) {
    val c = colors
    val s = state.status
    val serviceLost = state.serviceLost
    Screen(
        header = { Header(alarm = true) },
        footer = {
            Cta(state.busy ?: stringResource(R.string.cta_reconnect), Icons.refresh, enabled = state.busy == null, onClick = onReconnect)
            Cta(stringResource(R.string.cta_stop_sharing), Icons.stop, CtaStyle.SECONDARY, enabled = state.busy == null, onClick = onStop)
            state.error?.let { Text(it, Type.small, c.alarm) }
        },
        spacing = 20.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(stringResource(R.string.alert_label), c.alarm)
            Text(stringResource(if (serviceLost) R.string.alert_service_title else R.string.alert_tunnel_title), Type.display)
            Text(
                when {
                    serviceLost -> stringResource(R.string.alert_service_text)
                    s.state == ServerStatus.State.FAILED -> stringResource(R.string.alert_failed_text, s.failure ?: stringResource(R.string.alert_xray_error))
                    else -> stringResource(R.string.alert_tunnel_text)
                },
                Type.body.copy(fontSize = 14.sp), c.mute,
            )
        }
        Chain(
            listOf(Triple(Icons.wifi, "", pluralStringResource(R.plurals.clients, s.clients, s.clients)), Triple(null, "TUN", stringResource(R.string.node_block)), Triple(Icons.alert, "", stringResource(R.string.node_vpn))),
            listOf(NodeState.OFF, NodeState.OFF, NodeState.BAD),
            listOf(NodeState.ON, NodeState.BAD),
        )
        if (s.log.isNotEmpty()) {
            Card {
                Column {
                    // По строке на запись: длинные переносы раздували карточку и уводили её под кнопки.
                    s.log.takeLast(4).forEach { Text(it, Type.mono.copy(fontSize = 11.5.sp, lineHeight = 19.sp), c.mute, maxLines = 1) }
                    Text("▮", Type.mono, c.ink)
                }
            }
        }
    }
}

@Composable
private fun Stat(title: String, bytesPerSecond: Long, modifier: Modifier) {
    val c = colors
    val (value, unitRes) = speed(bytesPerSecond)
    val unit = stringResource(unitRes)
    Column(modifier.background(c.card).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(title)
        BasicText(buildAnnotatedString {
            withStyle(SpanStyle(color = c.ink)) { append(value) }
            withStyle(SpanStyle(color = c.mute, fontSize = 12.sp)) { append(" $unit") }
        }, style = Type.stat)
    }
}

@Composable
private fun WifiQrDialog(ssid: String, passphrase: String?, onDismiss: () -> Unit) {
    val c = colors
    val bitmap = remember(ssid, passphrase) { wifiQr(ssid, passphrase) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.background(c.card).border(1.5.dp, c.ink).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label(stringResource(R.string.qr_label))
            Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.qr_desc, ssid), Modifier.size(240.dp))
            Text(ssid, Type.bodyBold)
        }
    }
}

/** QR в формате Wi-Fi: камера телефона клиента подключается без ввода пароля. */
private fun wifiQr(ssid: String, passphrase: String?): Bitmap {
    fun esc(s: String) = s.replace(Regex("([\\\\;,:\"])"), "\\\\$1")
    val text = if (passphrase.isNullOrEmpty()) "WIFI:T:nopass;S:${esc(ssid)};;" else "WIFI:T:WPA;S:${esc(ssid)};P:${esc(passphrase)};;"
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
        bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    return bitmap
}

private fun speed(bps: Long): Pair<String, Int> = when {
    bps >= 1_000_000 -> "%.1f".format(bps / 1_000_000.0) to R.string.unit_mbps
    bps >= 1_000 -> "%.0f".format(bps / 1_000.0) to R.string.unit_kbps
    else -> "$bps" to R.string.unit_bps
}

private fun uptime(startedAt: Long): String {
    if (startedAt <= 0) return "00:00:00"
    val s = (System.currentTimeMillis() - startedAt) / 1000
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}

