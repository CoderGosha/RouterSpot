package app.routerspot.ui

import androidx.compose.ui.res.stringResource
import app.routerspot.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

@Composable
fun Text(text: String, style: TextStyle, color: Color = colors.ink, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    BasicText(text, modifier, style.copy(color = color), overflow = TextOverflow.Ellipsis, maxLines = maxLines)

/** Моноширинная подпись в стиле «// СТАТУС». */
@Composable
fun Label(text: String, color: Color = colors.mute, modifier: Modifier = Modifier) =
    Text(text.uppercase(), Type.label, color, modifier)

enum class Dot { OFF, ON, BAD }

@Composable
fun StatusDot(dot: Dot) {
    val c = colors
    Box(
        Modifier.size(8.dp).clip(CircleShape)
            .background(when (dot) { Dot.OFF -> c.off; Dot.ON -> c.accent; Dot.BAD -> c.alarm })
            .then(if (dot == Dot.ON) Modifier.border(1.5.dp, c.ink, CircleShape) else Modifier),
    )
}

/** Строки статуса с волосяными разделителями. */
@Composable
fun StatusRows(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        content()
    }

@Composable
fun StatusRow(title: String, value: String, dot: Dot) {
    val c = colors
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Type.body, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(dot)
            Text(value.uppercase(), Type.mono, if (dot == Dot.OFF) c.mute else c.ink)
        }
    }
    Hairline()
}

@Composable
fun Hairline() = Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))

/** Панель с точечной сеткой и угловыми скобками — главный «кибер»-элемент. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = colors
    Box(
        modifier
            .background(c.panel)
            .border(1.dp, c.line)
            .drawBehind {
                val step = 14.dp.toPx()
                var y = step / 2
                while (y < size.height) {
                    var x = step / 2
                    while (x < size.width) {
                        drawCircle(c.dots, 1.dp.toPx(), Offset(x, y))
                        x += step
                    }
                    y += step
                }
                val l = 12.dp.toPx()
                val w = 2.dp.toPx()
                drawRect(c.ink, Offset(0f, 0f), androidx.compose.ui.geometry.Size(l, w))
                drawRect(c.ink, Offset(0f, 0f), androidx.compose.ui.geometry.Size(w, l))
                drawRect(c.ink, Offset(size.width - l, size.height - w), androidx.compose.ui.geometry.Size(l, w))
                drawRect(c.ink, Offset(size.width - w, size.height - l), androidx.compose.ui.geometry.Size(w, l))
            },
    ) { content() }
}

@Composable
fun Card(modifier: Modifier = Modifier, border: Color = colors.line, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = colors
    Box(
        modifier.fillMaxWidth().background(c.card).border(if (border == c.line) 1.dp else 1.5.dp, border)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) { content() }
}

@Composable
fun Chip(text: String, on: Boolean = false, modifier: Modifier = Modifier) {
    val c = colors
    Text(
        text.uppercase(),
        Type.label.copy(fontSize = 10.sp, letterSpacing = 0.08.sp),
        if (on) c.onAccent else c.mute,
        modifier.background(if (on) c.accent else Color.Transparent)
            .border(1.dp, if (on) c.ink else c.line)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** Скошенные углы у главной кнопки — лёгкий киберпанк поверх скандинавской формы. */
private val ChamferShape = GenericShape { size, _ ->
    val cut = size.height * 14f / 60f // 14dp при высоте 60dp, как в макете
    moveTo(0f, 0f)
    lineTo(size.width - cut, 0f)
    lineTo(size.width, cut)
    lineTo(size.width, size.height)
    lineTo(cut, size.height)
    lineTo(0f, size.height - cut)
    close()
}

enum class CtaStyle { PRIMARY, SECONDARY, GHOST }

@Composable
fun Cta(text: String, icon: ImageVector?, style: CtaStyle = CtaStyle.PRIMARY, enabled: Boolean = true, onClick: () -> Unit) {
    val c = colors
    val bg = when (style) { CtaStyle.PRIMARY -> c.accent; CtaStyle.SECONDARY -> c.card; CtaStyle.GHOST -> Color.Transparent }
    val fg = if (style == CtaStyle.PRIMARY) c.onAccent else c.ink
    Row(
        Modifier.fillMaxWidth().height(if (style == CtaStyle.GHOST) 44.dp else 60.dp)
            .clip(ChamferShape)
            .background(if (enabled) bg else c.line)
            .then(if (style != CtaStyle.GHOST) Modifier.border(1.5.dp, c.ink, ChamferShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (style == CtaStyle.GHOST) Arrangement.Center else Arrangement.SpaceBetween,
    ) {
        Text(text, if (style == CtaStyle.GHOST) Type.body.copy(fontWeight = FontWeight.SemiBold) else Type.button, fg)
        if (icon != null && style != CtaStyle.GHOST) Icon(icon, fg, 22.dp)
    }
}

enum class NodeState { OFF, ON, BAD }

/** Схема «Хотспот → TUN → VPN»: узлы и связи, гаснут/горят/краснеют по состоянию. */
@Composable
fun Chain(nodes: List<Triple<ImageVector?, String, String>>, states: List<NodeState>, links: List<NodeState>) {
    val c = colors
    Panel(Modifier.fillMaxWidth().height(118.dp)) {
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.Top) {
            nodes.forEachIndexed { i, (icon, text, caption) ->
                if (i > 0) ChainLink(links[i - 1])
                val state = states[i]
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val border = when (state) { NodeState.ON -> c.ink; NodeState.BAD -> c.alarm; NodeState.OFF -> c.off }
                    val fg = when (state) { NodeState.ON -> c.onAccent; NodeState.BAD -> c.alarm; NodeState.OFF -> c.mute }
                    Box(
                        Modifier.size(52.dp).background(if (state == NodeState.ON) c.accent else c.card).border(1.5.dp, border),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (icon != null) Icon(icon, fg, 22.dp) else Text(text, Type.mono.copy(fontWeight = FontWeight.Bold), fg)
                    }
                    Label(caption, if (state == NodeState.BAD) c.alarm else if (state == NodeState.ON) c.ink else c.mute)
                }
            }
        }
    }
}

@Composable
private fun RowScope.ChainLink(state: NodeState) {
    val c = colors
    val color = when (state) { NodeState.ON -> c.ink; NodeState.BAD -> c.alarm; NodeState.OFF -> c.off }
    Box(
        Modifier.padding(top = 26.dp).width(40.dp).height(2.dp).drawBehind {
            drawLine(
                color, Offset(0f, 0f), Offset(size.width, 0f), 1.5.dp.toPx(),
                pathEffect = if (state == NodeState.ON) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        },
    )
}

/** Нижняя навигация: три вкладки, активная отмечена линией сверху. */
@Composable
fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val c = colors
    val tabs = listOf(Icons.wifi to stringResource(R.string.tab_sharing), Icons.list to stringResource(R.string.tab_profiles), Icons.terminal to stringResource(R.string.tab_system))
    Column(Modifier.fillMaxWidth().background(c.card)) {
        Hairline()
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            tabs.forEachIndexed { i, (icon, title) ->
                val active = i == selected
                Column(
                    Modifier.weight(1f).height(64.dp)
                        .drawBehind { if (active) drawRect(c.ink, size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
                        .clickable(role = Role.Tab) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(icon, if (active) c.ink else c.mute)
                    Text(
                        title,
                        Type.small.copy(fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold),
                        if (active) c.ink else c.mute,
                    )
                }
            }
        }
    }
}

@Composable
fun Header(right: String = "v0.1 · SHELL", rightDot: Dot? = null, alarm: Boolean = false) {
    val c = colors
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(if (alarm) c.alarm else c.accent).border(1.5.dp, c.ink))
        Text("RouterSpot", Type.bodyBold.copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold), modifier = Modifier.padding(start = 10.dp).weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Label(right)
            if (rightDot != null) StatusDot(rightDot)
        }
    }
}

@Composable
fun Spacer(height: Dp) = Box(Modifier.height(height))
