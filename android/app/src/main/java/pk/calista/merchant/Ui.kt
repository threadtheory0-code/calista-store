package pk.calista.merchant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp

/** A wireframe object: hairline border plus the four "+" registration marks. */
@Composable
fun Blueprint(
    modifier: Modifier = Modifier,
    borderColor: Color = T.line,
    padding: Int = 14,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, borderColor)
                .padding(padding.dp),
            content = content,
        )
        Mark(Modifier.align(Alignment.TopStart).offset((-4).dp, (-7).dp))
        Mark(Modifier.align(Alignment.TopEnd).offset(4.dp, (-7).dp))
        Mark(Modifier.align(Alignment.BottomStart).offset((-4).dp, 7.dp))
        Mark(Modifier.align(Alignment.BottomEnd).offset(4.dp, 7.dp))
    }
}

@Composable
private fun Mark(modifier: Modifier) {
    Text("+", modifier, color = T.ghost, style = mono(10))
}

@Composable
fun Kicker(text: String, color: Color = T.faint, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, color = color, style = kicker())
}

/** The one solid object on a screen. */
@Composable
fun GoldButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        enabled = enabled,
        shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = T.gold,
            contentColor = T.bg,
            disabledContainerColor = T.line,
            disabledContentColor = T.ghost,
        ),
    ) {
        Text(label.uppercase(), style = head(13, androidx.compose.ui.text.font.FontWeight.Bold))
    }
}

@Composable
fun LineButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        enabled = enabled,
        shape = RectangleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, T.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = T.muted),
    ) {
        Kicker(label, T.muted)
    }
}

@Composable
fun StatusTag(status: String, modifier: Modifier = Modifier) {
    val c = statusColor(status)
    Box(
        modifier
            .background(c.copy(alpha = 0.14f))
            .border(1.dp, c.copy(alpha = 0.55f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Kicker(status, c)
    }
}

fun statusColor(status: String): Color = when (status.lowercase()) {
    "pending" -> T.gold
    "confirmed" -> T.goldSoft
    "packed" -> T.goldSoft
    "shipped" -> T.ship
    "delivered" -> T.ok
    "returned", "cancelled", "fake" -> T.warn
    else -> T.faint
}

@Composable
fun KeyValue(label: String, value: String, valueColor: Color = T.text) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = T.faint, style = body(12))
        Text(value, color = valueColor, style = mono(12))
    }
}

@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(T.line))
}
