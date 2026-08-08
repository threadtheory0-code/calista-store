package pk.calista.merchant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Screen 7 — Reports: revenue by range, best sellers, COD reconciliation. */
@Composable
fun ReportsScreen(s: AppState) {
    val st = s.stats
    val delivered = s.orders.filter { it.status.equals("delivered", true) }
    val inTransit = s.orders.filter { it.status.equals("shipped", true) && it.payment == "COD" }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {

        Row(Modifier.fillMaxWidth().border(1.dp, T.line)) {
            for (r in listOf("7d", "30d", "90d")) {
                val active = s.range == r
                Box(
                    Modifier.weight(1f)
                        .background(if (active) T.gold else Color.Transparent)
                        .clickable { s.setRange(r) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Kicker(
                        if (r == "7d") "7 days" else if (r == "30d") "30 days" else "90 days",
                        if (active) T.bg else T.muted,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        Blueprint {
            Kicker("Revenue · " + s.range)
            Spacer(Modifier.height(8.dp))
            Text(if (st != null) rs(st.sales) else "—", color = T.text, style = head(30, FontWeight.Bold))
            if (st != null && st.series.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                val max = (st.series.maxOrNull() ?: 1).coerceAtLeast(1)
                Row(
                    Modifier.fillMaxWidth().height(96.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    for (v in st.series) {
                        Box(
                            Modifier.weight(1f).height((6 + 90f * v / max).dp)
                                .background(T.gold.copy(alpha = 0.75f))
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Blueprint {
            Kicker("Best sellers")
            Spacer(Modifier.height(10.dp))
            val tally = HashMap<String, Int>()
            for (o in s.orders) for (i in o.items) tally[i.name] = (tally[i.name] ?: 0) + i.price * i.qty
            val top = tally.entries.sortedByDescending { it.value }.take(5)
            val max = (top.firstOrNull()?.value ?: 1).coerceAtLeast(1)
            for (e in top) {
                Text(e.key, color = T.text, style = body(13))
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(e.value.toFloat() / max).height(6.dp)
                            .background(T.gold.copy(alpha = 0.8f))
                    )
                    Spacer(Modifier.weight((1f - e.value.toFloat() / max).coerceAtLeast(0.01f)))
                }
                Spacer(Modifier.height(3.dp))
                Text(rs(e.value), color = T.goldSoft, style = mono(11))
                Spacer(Modifier.height(10.dp))
            }
            if (top.isEmpty()) Text("No sales in this window.", color = T.faint, style = body(13))
        }
        Spacer(Modifier.height(12.dp))

        Blueprint {
            Kicker("COD reconciliation")
            Spacer(Modifier.height(8.dp))
            KeyValue("Delivered orders", delivered.size.toString())
            KeyValue("COD in transit", rs(inTransit.sumOf { it.total }), T.warn)
            KeyValue(
                "Collected (delivered COD)",
                rs(delivered.filter { it.payment == "COD" }.sumOf { it.total }),
                T.ok,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Pending remittance comes from PostEx get-unpaid — wire it in SYNC-SETUP.md step 8 " +
                    "and this card fills itself.",
                color = T.ghost, style = body(11),
            )
        }
        Spacer(Modifier.height(30.dp))
    }
}
