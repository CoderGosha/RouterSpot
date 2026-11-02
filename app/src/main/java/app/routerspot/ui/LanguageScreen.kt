package app.routerspot.ui

import android.app.LocaleManager
import android.os.LocaleList
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val languages = listOf(
    Triple("ru", "Русский", "Интерфейс на русском"),
    Triple("en", "English", "English interface"),
)

/**
 * Выбор языка. Экран двуязычный: при первом запуске язык ещё не выбран.
 * Язык ставится через системный LocaleManager — он же виден в настройках «Язык приложения».
 */
@Composable
fun LanguageScreen(onBack: (() -> Unit)?, onChosen: () -> Unit) {
    val c = colors
    val context = LocalContext.current
    val localeManager = context.getSystemService(LocaleManager::class.java)
    val current = localeManager.applicationLocales.get(0)?.language
        ?: context.resources.configuration.locales.get(0).language

    Screen(
        header = {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    Box(
                        Modifier.size(44.dp).background(c.card).border(1.dp, c.line).clickable(role = Role.Button, onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.back, c.ink) }
                } else {
                    Box(Modifier.size(12.dp).background(c.accent).border(1.5.dp, c.ink))
                    Text("RouterSpot", Type.bodyBold.copy(fontSize = 18.sp), modifier = Modifier.padding(start = 10.dp))
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("// Язык · Language")
            Text("Язык\nLanguage", Type.display)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            languages.forEach { (tag, name, hint) ->
                val selected = tag == current
                Card(border = if (selected) c.ink else c.line, onClick = {
                    localeManager.applicationLocales = LocaleList.forLanguageTags(tag)
                    onChosen()
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(name, Type.title.copy(fontSize = 22.sp))
                            Text(hint, Type.mono.copy(fontSize = 11.sp), c.mute)
                        }
                        Box(
                            Modifier.size(28.dp).background(if (selected) c.accent else c.card).border(1.5.dp, if (selected) c.ink else c.line),
                            contentAlignment = Alignment.Center,
                        ) { if (selected) Icon(Icons.check, c.onAccent, 18.dp) }
                    }
                }
            }
        }
    }
}
