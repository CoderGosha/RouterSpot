package app.routerspot.ui

import androidx.compose.ui.res.stringResource
import app.routerspot.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.routerspot.UiState

/** Вкладка «Система»: состояние службы и ссылки. Сама настройка — в отдельном мастере [SetupScreen]. */
@Composable
fun SystemScreen(state: UiState, onSetup: () -> Unit, onAbout: () -> Unit, onGuide: () -> Unit, onLanguage: () -> Unit, onDiagnostics: () -> Unit) {
    val c = colors
    val ready = state.setupStep > 4

    Screen(
        footer = {
            if (!ready) Cta(stringResource(R.string.sys_setup_continue), Icons.arrow, onClick = onSetup)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(stringResource(R.string.sys_label))
            Text(stringResource(if (ready) R.string.sys_title_4 else R.string.sys_setup_todo), Type.title)
            if (ready) Text(stringResource(R.string.sys_text_4), Type.body.copy(fontSize = 14.sp), c.mute)
            else Label(stringResource(R.string.setup_progress, state.setupStep), c.ink)
        }
        StatusRows {
            StatusRow(stringResource(R.string.sys_service), stringResource(if (state.serverAttached) R.string.sys_service_on else R.string.sys_service_off), if (state.serverAttached) Dot.ON else Dot.OFF)
            StatusRow(stringResource(R.string.diag_wireless), stringResource(if (state.wirelessDebugging) R.string.val_on_f else R.string.val_off_f), if (state.wirelessDebugging) Dot.ON else Dot.OFF)
        }
        LinkButton(stringResource(R.string.sys_diagnostics), Modifier.fillMaxWidth(), onDiagnostics, strong = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LinkButton(stringResource(R.string.sys_how_it_works), Modifier.weight(1f), onAbout)
            LinkButton(stringResource(R.string.sys_guide), Modifier.weight(1f), onGuide)
        }
        LinkButton(stringResource(R.string.sys_language), Modifier.fillMaxWidth(), onLanguage)
        if (ready && state.status.log.isNotEmpty()) {
            Card {
                Column {
                    Label(stringResource(R.string.sys_log), modifier = Modifier.padding(bottom = 8.dp))
                    state.status.log.takeLast(8).forEach { Text(it, Type.mono.copy(fontSize = 11.sp, lineHeight = 18.sp), c.mute) }
                }
            }
        }
    }
}

@Composable
private fun LinkButton(text: String, modifier: Modifier, onClick: () -> Unit, strong: Boolean = false) {
    val c = colors
    Row(
        modifier.height(48.dp).background(c.card).border(if (strong) 1.5.dp else 1.dp, if (strong) c.ink else c.line).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Type.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
        Icon(Icons.chevron, c.ink, 18.dp)
    }
}
