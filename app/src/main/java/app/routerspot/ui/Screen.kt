package app.routerspot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Каркас вкладки: шапка, прокручиваемое содержимое, закреплённые снизу действия. */
@Composable
fun Screen(
    header: @Composable () -> Unit = { Header() },
    footer: @Composable ColumnScope.() -> Unit = {},
    spacing: Dp = 22.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        header()
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        // Если содержимое уходит под кнопки, отделяем их линией, чтобы не казалось, что они наезжают.
        if (scroll.canScrollForward) Hairline()
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = if (scroll.canScrollForward) 14.dp else 0.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = footer,
        )
    }
}
