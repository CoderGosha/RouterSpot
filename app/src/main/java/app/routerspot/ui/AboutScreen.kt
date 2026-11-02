package app.routerspot.ui

import androidx.compose.ui.res.stringResource
import app.routerspot.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Страница «Как это работает»: схема пути трафика и пояснения. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val c = colors
    Screen(
        header = {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(c.card).border(1.dp, c.line).clickable(role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.back, c.ink) }
                Label(stringResource(R.string.about_title_bar), modifier = Modifier.padding(start = 14.dp))
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(stringResource(R.string.about_label))
            Text(stringResource(R.string.about_title), Type.display)
        }

        Column {
            Node(stringResource(R.string.node_clients), stringResource(R.string.about_clients_sub), external = true)
            Link(stringResource(R.string.about_link_wifi))
            Box(
                Modifier.fillMaxWidth().drawBehind {
                    drawRect(
                        c.mute, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    )
                }.padding(12.dp),
            ) {
                Column {
                    Label(stringResource(R.string.about_phone), c.ink, Modifier.padding(bottom = 10.dp))
                    Node(stringResource(R.string.node_hotspot), stringResource(R.string.about_hotspot_sub))
                    Link(stringResource(R.string.about_link_all))
                    Node(stringResource(R.string.about_test_net), stringResource(R.string.about_test_net_sub), tag = "TUN", note = stringResource(R.string.about_test_net_note))
                    Link("TCP · UDP · DNS")
                    Node("tun2socks", "hev-socks5-tunnel")
                    Link("SOCKS5 · 127.0.0.1")
                    Node("Xray", stringResource(R.string.about_xray_sub), tag = "XR")
                }
            }
            Link(stringResource(R.string.about_link_reality))
            Node(stringResource(R.string.about_server), stringResource(R.string.about_server_sub), external = true, accent = true)
        }

        Section(
            stringResource(R.string.about_noroot_title),
            stringResource(R.string.about_noroot_text),
        )
        Section(
            stringResource(R.string.about_leak_title),
            stringResource(R.string.about_leak_text),
        )
        Section(
            stringResource(R.string.about_limits_title),
            stringResource(R.string.about_limits_text),
        )
    }
}

@Composable
private fun Node(title: String, subtitle: String, tag: String? = null, note: String? = null, external: Boolean = false, accent: Boolean = false) {
    val c = colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).background(if (accent) c.accent else c.card)
                .border(if (external) 1.5.dp else 1.dp, if (external) c.ink else c.line)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tag != null) {
                Box(Modifier.size(36.dp).border(1.5.dp, if (accent) c.onAccent else c.ink), contentAlignment = Alignment.Center) {
                    Text(tag, Type.mono.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp), if (accent) c.onAccent else c.ink)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, Type.bodyBold, if (accent) c.onAccent else c.ink)
                Text(subtitle, Type.mono.copy(fontSize = 11.sp), if (accent) c.onAccent else c.mute)
            }
        }
        if (note != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(10.dp).height(1.5.dp).background(c.alarm))
                Text(note, Type.mono.copy(fontSize = 10.sp, lineHeight = 13.sp), c.alarm, Modifier.width(92.dp).padding(start = 4.dp))
            }
        }
    }
}

/** Вертикальная связь со стрелкой и подписью: что именно идёт по этому участку. */
@Composable
private fun Link(label: String) {
    val c = colors
    Row(Modifier.height(36.dp).padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(12.dp).height(36.dp).drawBehind {
                val x = size.width / 2
                drawLine(c.ink, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
                val a = 4.dp.toPx()
                drawLine(c.ink, Offset(x - a, size.height - a), Offset(x, size.height), 1.5.dp.toPx())
                drawLine(c.ink, Offset(x + a, size.height - a), Offset(x, size.height), 1.5.dp.toPx())
            },
        )
        Label(label, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun Section(title: String, body: String) {
    val c = colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("// $title")
        Text(body, Type.body.copy(fontSize = 14.sp, lineHeight = 21.sp), c.ink)
    }
}
