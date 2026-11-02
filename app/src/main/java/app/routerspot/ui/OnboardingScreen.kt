package app.routerspot.ui

import androidx.compose.ui.res.stringResource
import app.routerspot.R
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGES = 4

/** Короткий гайд при первом запуске: что это, что нужно (с живой проверкой), сопряжение, защита. */
@Composable
fun OnboardingScreen(hasProfile: Boolean, onOpenProfiles: () -> Unit, onAbout: () -> Unit, onDone: () -> Unit) {
    val c = colors
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES - 1

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(c.accent).border(1.5.dp, c.ink))
            Text("RouterSpot", Type.bodyBold.copy(fontSize = 18.sp), modifier = Modifier.padding(start = 10.dp).weight(1f))
            Text(stringResource(R.string.ob_skip), Type.small, c.mute, Modifier.clickable(role = Role.Button, onClick = onDone).padding(8.dp))
        }
        Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0 until PAGES).forEach { i ->
                Box(
                    Modifier.weight(1f).height(4.dp).background(
                        when { i < pager.currentPage -> c.ink; i == pager.currentPage -> c.accent; else -> c.line },
                    ),
                )
            }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                when (page) {
                    0 -> IntroPage()
                    1 -> RequirementsPage(hasProfile, onOpenProfiles)
                    2 -> PairingPage()
                    else -> ProtectionPage(onAbout)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(24.dp, 0.dp, 24.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Cta(stringResource(if (last) R.string.ob_start else R.string.ob_next), Icons.arrow) {
                if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }
        }
    }
}

@Composable
private fun PageTitle(label: String, title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(label)
        Text(title, Type.display)
        Text(text, Type.body.copy(fontSize = 14.sp), colors.mute)
    }
}

@Composable
private fun IntroPage() {
    PageTitle(
        stringResource(R.string.ob1_label),
        stringResource(R.string.ob1_title),
        stringResource(R.string.ob1_text),
    )
    Chain(
        listOf(Triple(Icons.wifi, "", stringResource(R.string.node_clients)), Triple(null, "RS", stringResource(R.string.node_phone)), Triple(Icons.shield, "", stringResource(R.string.node_vpn))),
        List(3) { NodeState.ON }, List(2) { NodeState.ON },
    )
}

/** Список требований проверяется на лету и обновляется, когда пользователь возвращается из настроек. */
@Composable
private fun RequirementsPage(hasProfile: Boolean, onOpenProfiles: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            tick++
        }
    }
    val devOptions = remember(tick) {
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    }
    val wifi = remember(tick) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    PageTitle(stringResource(R.string.ob2_label), stringResource(R.string.ob2_title), stringResource(R.string.ob2_text))
    Column {
        Hairline()
        Requirement(stringResource(R.string.req_android), stringResource(R.string.req_android_hint, Build.VERSION.RELEASE), Build.VERSION.SDK_INT >= 33)
        Requirement(
            stringResource(R.string.req_dev), stringResource(R.string.req_dev_hint), devOptions,
            action = stringResource(R.string.req_open) to { context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        )
        Requirement(
            stringResource(R.string.req_wifi), stringResource(R.string.req_wifi_hint), wifi,
            action = stringResource(R.string.req_open) to { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        )
        Requirement(stringResource(R.string.req_link), stringResource(R.string.req_link_hint), hasProfile, action = stringResource(R.string.req_add) to onOpenProfiles)
    }
}

@Composable
private fun Requirement(title: String, hint: String, ok: Boolean, action: Pair<String, () -> Unit>? = null) {
    val c = colors
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, Type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            Text(hint, Type.small, c.mute)
        }
        when {
            ok -> Box(Modifier.size(28.dp).background(c.accent).border(1.5.dp, c.ink), contentAlignment = Alignment.Center) {
                Icon(Icons.check, c.onAccent, 18.dp)
            }
            action != null -> Text(
                action.first.uppercase(), Type.label, c.ink,
                Modifier.border(1.dp, c.ink).clickable(role = Role.Button, onClick = action.second).padding(horizontal = 10.dp, vertical = 8.dp),
            )
            else -> StatusDot(Dot.BAD)
        }
    }
    Hairline()
}

@Composable
private fun PairingPage() {
    val c = colors
    PageTitle(
        stringResource(R.string.ob3_label),
        stringResource(R.string.ob3_title),
        stringResource(R.string.ob3_text),
    )
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(stringResource(R.string.ob3_reboot_label))
            Text(
                stringResource(R.string.ob3_reboot_text),
                Type.small, c.mute,
            )
        }
    }
}

@Composable
private fun ProtectionPage(onAbout: () -> Unit) {
    val c = colors
    PageTitle(
        stringResource(R.string.ob4_label),
        stringResource(R.string.ob4_title),
        stringResource(R.string.ob4_text),
    )
    Chain(
        listOf(Triple(Icons.wifi, "", stringResource(R.string.node_clients)), Triple(null, "TUN", stringResource(R.string.node_block)), Triple(Icons.alert, "", stringResource(R.string.node_vpn))),
        listOf(NodeState.OFF, NodeState.OFF, NodeState.BAD),
        listOf(NodeState.ON, NodeState.BAD),
    )
    Text(
        stringResource(R.string.ob4_how), Type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), c.ink,
        Modifier.clickable(role = Role.Button, onClick = onAbout).padding(vertical = 8.dp),
    )
}
