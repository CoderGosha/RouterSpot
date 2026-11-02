package app.routerspot.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

/** Линейные иконки 24×24 из макета (stroke). */
object Icons {
    val wifi = icon("M5 12.5a10 10 0 0 1 14 0", "M8.5 15.5a5 5 0 0 1 7 0", "M11 18.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0")
    val shield = icon("M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6z")
    val list = icon("M4 6h16M4 12h16M4 18h10")
    val terminal = icon("M4 5h16v14H4z", "M8 10l3 2-3 2M13 15h3")
    val chevron = icon("M9 6l6 6-6 6")
    val arrow = icon("M5 12h14M13 6l6 6-6 6")
    val stop = icon("M7 7h10v10H7z")
    val clipboard = icon("M6 4h12v17H6z", "M9 4h6v3H9z")
    val qr = icon("M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 18h2v2h-2zM14 18h2M18 14h2")
    val check = icon("M5 12.5l4.5 4.5L19 7.5")
    val alert = icon("M12 3l9.5 17h-19z", "M12 10v4M12 17.5v.01")
    val refresh = icon("M4 12a8 8 0 0 1 14-5.3M20 12a8 8 0 0 1-14 5.3", "M18 3v4h-4M6 21v-4h4")
    val back = icon("M15 6l-6 6 6 6")
    val trash = icon("M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13")

    private fun icon(vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        paths.forEach {
            builder.addPath(
                pathData = PathParser().parsePathString(it).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}

@Composable
fun Icon(icon: ImageVector, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Image(icon, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}
