package app.routerspot.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.routerspot.R
import app.routerspot.UiState
import app.routerspot.adb.PairingService
import kotlinx.coroutines.delay

/**
 * Мастер настройки: один шаг на экран (режим разработчика → отладка по Wi-Fi → сопряжение → служба).
 * Шаг берётся из состояния телефона, поэтому мастер сам идёт дальше, когда пользователь возвращается из настроек.
 */
@Composable
fun SetupScreen(state: UiState, onPair: (String) -> Unit, onLaunch: () -> Unit, onClose: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val step = state.setupStep
    fun open(intent: Intent) = context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    Screen(
        header = { SetupHeader(step, onClose) },
        footer = {
            when (step) {
                1 -> Cta(stringResource(R.string.setup_dev_cta), Icons.arrow) { open(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
                2 -> Cta(stringResource(R.string.setup_wireless_cta), Icons.arrow) { open(PairingService.settingsIntent()) }
                3 -> PairFooter(state, onPair) {
                    PairingService.start(context)
                    open(PairingService.settingsIntent())
                }
                4 -> if (!state.wirelessDebugging && state.busy == null) {
                    Cta(stringResource(R.string.setup_wireless_cta), Icons.arrow) { open(PairingService.settingsIntent()) }
                } else {
                    Cta(state.busy ?: stringResource(R.string.sys_launch), Icons.arrow, CtaStyle.SECONDARY, enabled = state.busy == null, onClick = onLaunch)
                }
                else -> Cta(stringResource(R.string.setup_done_cta), Icons.arrow, onClick = onDone)
            }
            if (step < 5) state.error?.let { Text(it, Type.small, colors.alarm) }
        },
    ) {
        when (step) {
            1 -> DevStep()
            2 -> WirelessStep(state)
            3 -> PairStep()
            4 -> LaunchStep(state, onLaunch)
            else -> DoneStep()
        }
    }
}

@Composable
private fun SetupHeader(step: Int, onClose: () -> Unit) {
    val c = colors
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(c.card).border(1.dp, c.line).clickable(role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.back, c.ink) }
            Box(Modifier.weight(1f))
            Label(stringResource(R.string.setup_progress, step.coerceAtMost(4)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..4).forEach { i ->
                Box(Modifier.weight(1f).height(4.dp).background(when { i < step -> c.ink; i == step -> c.accent; else -> c.line }))
            }
        }
    }
}

@Composable
private fun StepTitle(label: Int, title: Int, lead: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(stringResource(label))
        Text(stringResource(title), Type.title)
        Text(stringResource(lead), Type.body, colors.mute)
    }
}

/** Путь по настройкам «крошками»; последний пункт — то, что нужно сделать. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Crumbs(vararg items: String) {
    val c = colors
    Panel(Modifier.fillMaxWidth()) {
        FlowRow(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEachIndexed { i, item ->
                val last = i == items.lastIndex
                Text(
                    item, Type.small.copy(fontWeight = FontWeight.SemiBold), if (last) c.onAccent else c.ink,
                    Modifier.background(if (last) c.accent else c.card).border(if (last) 1.5.dp else 1.dp, if (last) c.ink else c.line)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
                if (!last) Text("→", Type.mono, c.mute, Modifier.align(Alignment.CenterVertically))
            }
        }
    }
}

@Composable
private fun DevStep() {
    StepTitle(R.string.setup_dev_label, R.string.setup_dev_title, R.string.setup_dev_lead)
    Crumbs(
        stringResource(R.string.setup_crumb_settings), stringResource(R.string.setup_crumb_about),
        stringResource(R.string.setup_crumb_software), stringResource(R.string.setup_crumb_build),
    )
    Text(stringResource(R.string.setup_dev_hint), Type.small, colors.mute)
}

@Composable
private fun WirelessStep(state: UiState) {
    StepTitle(R.string.setup_wireless_label, R.string.setup_wireless_title, R.string.setup_wireless_lead)
    StatusRows {
        StatusRow(stringResource(R.string.diag_wifi), onOff(state.wifi), if (state.wifi) Dot.ON else Dot.BAD)
        StatusRow(stringResource(R.string.diag_dev), onOff(state.devMode), if (state.devMode) Dot.ON else Dot.OFF)
        StatusRow(stringResource(R.string.diag_wireless), stringResource(if (state.wirelessDebugging) R.string.val_on_f else R.string.val_off_f), if (state.wirelessDebugging) Dot.ON else Dot.OFF)
    }
    Crumbs(stringResource(R.string.setup_crumb_dev), stringResource(R.string.setup_crumb_wireless))
    Text(stringResource(R.string.setup_wireless_hint), Type.small, colors.mute)
}

@Composable
private fun onOff(on: Boolean) = stringResource(if (on) R.string.val_on else R.string.val_off)

@Composable
private fun PairStep() {
    StepTitle(R.string.setup_pair_label, R.string.setup_pair_title, R.string.setup_pair_lead)
    Column {
        Hairline()
        NumberedRow(1, R.string.setup_pair_1, R.string.setup_pair_1_hint)
        NumberedRow(2, R.string.setup_pair_2, R.string.setup_pair_2_hint)
        NumberedRow(3, R.string.setup_pair_3, R.string.setup_pair_3_hint)
    }
}

@Composable
private fun NumberedRow(n: Int, title: Int, hint: Int) {
    val c = colors
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("0$n", Type.mono.copy(fontSize = 13.sp), c.mute, Modifier.padding(top = 2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(title), Type.bodyBold.copy(fontSize = 16.sp))
            Text(stringResource(hint), Type.small, c.mute)
        }
    }
    Hairline()
}

/** Основной путь — уведомление; ввод кода в приложении оставлен для разделённого экрана. */
@Composable
private fun PairFooter(state: UiState, onPair: (String) -> Unit, onStart: () -> Unit) {
    var inApp by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    if (inApp) {
        CodeInput(code) { code = it }
        Cta(state.busy ?: stringResource(R.string.sys_connect), Icons.arrow, enabled = code.length == 6 && state.busy == null) { onPair(code) }
    } else {
        Cta(stringResource(R.string.setup_pair_cta), Icons.arrow, onClick = onStart)
    }
    Text(
        stringResource(if (inApp) R.string.setup_pair_notification else R.string.setup_pair_in_app),
        Type.small.copy(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline, textAlign = TextAlign.Center),
        colors.mute,
        Modifier.fillMaxWidth().clickable(role = Role.Button) { inApp = !inApp }.padding(vertical = 6.dp),
    )
}

@Composable
private fun LaunchStep(state: UiState, onLaunch: () -> Unit) {
    // PairingService уже запускает службу после сопряжения; если она так и не появилась — запускаем сами.
    LaunchedEffect(Unit) {
        delay(4_000)
        val s = app.routerspot.AppController.state.value
        if (!s.serverAttached && s.busy == null && s.error == null && s.wirelessDebugging) onLaunch()
    }
    StepTitle(R.string.setup_launch_label, R.string.setup_launch_title, R.string.setup_launch_lead)
    val launching = state.busy != null
    Column {
        Hairline()
        CheckLine(R.string.setup_launch_paired, Mark.OK)
        CheckLine(R.string.diag_wireless, if (state.wirelessDebugging) Mark.OK else Mark.FAIL)
        CheckLine(R.string.setup_launch_service, when { state.error != null -> Mark.FAIL; launching -> Mark.RUN; else -> Mark.WAIT })
    }
    if (!state.wirelessDebugging) Text(stringResource(R.string.setup_launch_needs_wireless), Type.small, colors.mute)
}

private enum class Mark { OK, RUN, WAIT, FAIL }

@Composable
private fun CheckLine(title: Int, mark: Mark) {
    val c = colors
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), Type.bodyBold, if (mark == Mark.WAIT) c.mute else c.ink, Modifier.weight(1f))
        Box(
            Modifier.size(26.dp)
                .background(when (mark) { Mark.OK -> c.accent; Mark.FAIL -> c.alarm; else -> c.card })
                .border(1.5.dp, when (mark) { Mark.WAIT -> c.line; Mark.FAIL -> c.alarm; else -> c.ink }),
            contentAlignment = Alignment.Center,
        ) {
            when (mark) {
                Mark.OK -> Icon(Icons.check, c.onAccent, 16.dp)
                Mark.RUN -> Text("…", Type.mono.copy(fontWeight = FontWeight.Bold), c.ink)
                Mark.FAIL -> Text("×", Type.mono.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp), androidx.compose.ui.graphics.Color.White)
                Mark.WAIT -> Unit
            }
        }
    }
    Hairline()
}

@Composable
private fun DoneStep() {
    val c = colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(stringResource(R.string.setup_done_label))
        Text(stringResource(R.string.setup_done_title), Type.display)
        Text(stringResource(R.string.setup_done_lead), Type.body, c.mute)
    }
    Panel(Modifier.fillMaxWidth().height(150.dp)) {
        Box(Modifier.align(Alignment.Center).size(64.dp).background(c.accent).border(1.5.dp, c.ink), contentAlignment = Alignment.Center) {
            Icon(Icons.check, c.onAccent, 34.dp)
        }
    }
}

/** Шесть ячеек кода поверх обычного текстового поля (цифровая клавиатура, вставка работает). */
@Composable
private fun CodeInput(code: String, onChange: (String) -> Unit) {
    val c = colors
    BasicTextField(
        value = code,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(6)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until 6).forEach { i ->
                    val filled = i < code.length
                    val cursor = i == code.length
                    Box(
                        Modifier.weight(1f).height(52.dp).background(c.card)
                            .border(if (filled || cursor) 1.5.dp else 1.dp, if (filled || cursor) c.ink else c.line)
                            .drawBehind {
                                if (cursor) drawRect(c.accent, Offset(0f, size.height - 3.dp.toPx()), size.copy(height = 3.dp.toPx()))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(code.getOrNull(i)?.toString() ?: "", Type.stat.copy(fontSize = 22.sp))
                    }
                }
            }
        },
    )
}
