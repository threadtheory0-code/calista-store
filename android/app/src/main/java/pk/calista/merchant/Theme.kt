package pk.calista.merchant

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Calista's palette: the Industry blueprint system re-mapped to black and gold. */
object T {
    val bg = Color(0xFF0A0A0A)
    val screen = Color(0xFF0E0E0E)
    val rail = Color(0xFF101010)
    val surface = Color(0xFF151515)
    val elev = Color(0xFF1A1A1A)
    val text = Color(0xFFFAFAF8)
    val muted = Color(0xB0FAFAF8)
    val faint = Color(0x85FAFAF8)
    val ghost = Color(0x52FAFAF8)
    val gold = Color(0xFFB8923E)
    val goldSoft = Color(0xFFE2D2A6)
    val goldPress = Color(0xFF9A7A32)
    val goldTint = Color(0x24B8923E)
    val line = Color(0x21FAFAF8)
    val warn = Color(0xFFC9704F)
    val ship = Color(0xFF8FA6B8)
    val ok = Color(0xFF9BA88F)
}

/** Condensed-feeling heading style. Swap to Barlow Condensed by dropping the TTFs in
 *  res/font and using FontFamily(Font(R.font.barlow_condensed_semibold)) here. */
fun head(size: Int, weight: FontWeight = FontWeight.SemiBold): TextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    letterSpacing = 0.2.sp,
)

fun body(size: Int, weight: FontWeight = FontWeight.Normal): TextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
)

/** The all-caps micro label the system uses for section kickers. */
fun kicker(): TextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.SemiBold,
    fontSize = 10.sp,
    letterSpacing = 1.6.sp,
)

fun mono(size: Int): TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = size.sp,
)

private val square = RoundedCornerShape(0.dp)

@Composable
fun CalistaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = T.gold,
            onPrimary = T.bg,
            background = T.bg,
            onBackground = T.text,
            surface = T.surface,
            onSurface = T.text,
            error = T.warn,
        ),
        shapes = Shapes(square, square, square, square, square),
        content = content,
    )
}
