package app.routerspot.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.routerspot.AppController
import app.routerspot.Check
import app.routerspot.CheckGroup
import app.routerspot.CheckState
import app.routerspot.Diagnostics
import app.routerspot.Fix
import app.routerspot.R

/** Экран «Диагностика»: одна кнопка, проверки по группам, у проблем — кнопка исправления. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, onOpenProfiles: () -> Unit, onOpenSystem: () -> Unit) {
    val c = colors
    val context = LocalContext.current
    val checks by Diagnostics.checks.collectAsState()
    val running by Diagnostics.running.collectAsState()
    val finished = !running && checks.none { it.state == CheckState.PENDING }
    val failures = checks.count { it.state == CheckState.FAIL }
    val warnings = checks.count { it.state == CheckState.WARN }

    fun fix(fix: Fix) {
        val pkg = Uri.parse("package:${context.packageName}")
        val intent = when (fix) {
            Fix.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            Fix.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            Fix.WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
            Fix.DEV_OPTIONS -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            Fix.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
            Fix.WIRELESS_DEBUGGING -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            Fix.PAIR -> return onOpenSystem()
            Fix.PROFILES -> return onOpenProfiles()
            Fix.LAUNCH -> return AppController.launchServer()
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    Screen(
        header = {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(c.card).border(1.dp, c.line).clickable(role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.back, c.ink) }
                Label(stringResource(R.string.diag_title_bar), modifier = Modifier.padding(start = 14.dp))
            }
        },
        footer = {
            Cta(
                stringResource(if (running) R.string.diag_running else R.string.diag_run),
                Icons.refresh, enabled = !running,
            ) { Diagnostics.run(context) }
        },
        spacing = 20.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(stringResource(R.string.diag_label), if (finished && failures > 0) c.alarm else c.mute)
            Text(
                when {
                    !finished -> stringResource(R.string.diag_title)
                    failures + warnings == 0 -> stringResource(R.string.diag_summary_ok)
                    else -> stringResource(R.string.diag_summary_issues, failures, warnings)
                },
                if (finished) Type.title else Type.display,
            )
            if (!finished) Text(stringResource(R.string.diag_intro), Type.body.copy(fontSize = 14.sp), c.mute)
        }
        CheckGroup.entries.forEach { group ->
            Column {
                Label("// " + stringResource(group.title), modifier = Modifier.padding(bottom = 6.dp))
                Hairline()
                checks.filter { it.group == group }.forEach { CheckRow(it, ::fix) }
            }
        }
    }
}

@Composable
private fun CheckRow(check: Check, onFix: (Fix) -> Unit) {
    val c = colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(check.title), Type.body.copy(fontWeight = if (check.state == CheckState.FAIL) FontWeight.Bold else FontWeight.Medium))
            check.detail?.let {
                Text(it, Type.mono.copy(fontSize = 11.sp, lineHeight = 15.sp), if (check.state == CheckState.FAIL) c.alarm else c.mute)
            }
        }
        val fix = check.fix
        if (fix != null && (check.state == CheckState.FAIL || check.state == CheckState.WARN)) {
            Text(
                stringResource(if (fix == Fix.LAUNCH) R.string.diag_fix_launch else R.string.diag_fix).uppercase(),
                Type.label, c.ink,
                Modifier.padding(end = 10.dp).border(1.dp, c.ink).clickable(role = Role.Button) { onFix(fix) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        StateMark(check.state)
    }
    Hairline()
}

/** Отметка состояния: лаймовая галочка, малиновый крест, «!» для предупреждения, многоточие во время проверки. */
@Composable
private fun StateMark(state: CheckState) {
    val c = colors
    val (bg, border, content) = when (state) {
        CheckState.OK -> Triple(c.accent, c.ink, "✓")
        CheckState.FAIL -> Triple(c.alarm, c.alarm, "×")
        CheckState.WARN -> Triple(c.card, c.ink, "!")
        CheckState.RUNNING -> Triple(c.card, c.ink, "…")
        CheckState.SKIP -> Triple(c.card, c.line, "–")
        CheckState.PENDING -> Triple(c.card, c.line, "")
    }
    Box(Modifier.size(28.dp).background(bg).border(1.5.dp, border), contentAlignment = Alignment.Center) {
        if (state == CheckState.OK) Icon(Icons.check, c.onAccent, 18.dp)
        else Text(content, Type.mono.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp), if (state == CheckState.FAIL) Color.White else c.ink)
    }
}
