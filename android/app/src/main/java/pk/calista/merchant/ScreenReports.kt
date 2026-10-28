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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Reports: revenue, the COD funnel, cities that return parcels, busy hours, best sellers. */
@Composable
fun ReportsScreen(s: AppState) {
    val st = s.stats
    val days = when (s.range) { "30d" -> 30; "90d" -> 90; else -> 7 }
    val from = Dates.startOfToday() - (days - 1) * 86_400_000L
    val win = s.orders.filter { it.placedAt >= from }
    val live = win.filter { it.st !in DEAD }
    val revenue = live.sumOf { it.total }
    val aov = if (live.isEmpty()) 0 else revenue / live.size
    val closed = win.filter { it.st in setOf("delivered", "returned") }
    val deliveredRate = if (closed.isEmpty()) "—" else (closed.count { it.st == "delivered" } * 100 / closed.size).toString() + "%"
    val cancelRate = if (win.isEmpty()) "—" else (win.count { it.st in setOf("cancelled", "fake") } * 100 / win.size).toString() + "%"

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {

        Row(Modifier.fillMaxWidth().border(1.dp, T.line)) {
            for (r in listOf("7d", "30d", "90d")) {
                val active = s.range == r
                Box(
                    Modifier.weight(1f)
                        .background(if (active) T.gold else Color.Transparent)
                        .clickable { s.pickRange(r) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Kicker(r.removeSuffix("d") + " days", if (active) T.bg else T.muted)
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        Blueprint {
            Kicker("Revenue · " + days + " days")
            Spacer(Modifier.height(8.dp))
            Text(rs(st?.sales ?: revenue), color = T.text, style = head(30, FontWeight.Bold))
            Text("cancelled, fake and returned orders excluded", color = T.ghost, style = body(11))
            if (st != null && st.series.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Bars(st.series, 96)
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("Orders", win.size.toString(), Modifier.weight(1f))
            Metric("Avg order", rs(aov), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("Delivered rate", deliveredRate, Modifier.weight(1f))
            Metric("Cancelled / fake", cancelRate, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))

        Blueprint {
            Kicker("Where orders are now")
            Spacer(Modifier.height(10.dp))
            val flow = listOf("pending", "confirmed", "packed", "shipped", "delivered", "returned", "cancelled", "fake")
            val max = (flow.maxOfOrNull { f -> win.count { it.st == f } } ?: 1).coerceAtLeast(1)
            for (f in flow) {
                val n = win.count { it.st == f }
                if (n == 0) continue
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f, color = T.muted, style = body(12), modifier = Modifier.width(84.dp))
                    Box(Modifier.weight(1f).height(8.dp)) {
                        Box(Modifier.fillMaxWidth(n.toFloat() / max).height(8.dp).background(statusColor(f).copy(alpha = 0.8f)))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(n.toString(), color = T.text, style = mono(11), modifier = Modifier.width(34.dp))
                }
            }
            if (win.isEmpty()) Text("No orders in this window.", color = T.faint, style = body(13))
        }
        Spacer(Modifier.height(12.dp))

        Blueprint {
            Kicker("Cities")
            Spacer(Modifier.height(4.dp))
            Text("Return rate counts returned parcels against everything that reached the customer.", color = T.ghost, style = body(11))
            Spacer(Modifier.height(10.dp))
            val cities = win.groupBy { it.city.trim().lowercase().replaceFirstChar { c -> c.uppercase() } }
                .entries.sortedByDescending { it.value.size }.take(8)
            for (e in cities) {
                val reached = e.value.count { it.st in setOf("delivered", "returned") }
                val ret = e.value.count { it.st == "returned" }
                val rate = if (reached == 0) "—" else (ret * 100 / reached).toString() + "%"
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.key.ifBlank { "—" }, color = T.text, style = body(13), modifier = Modifier.weight(1f))
                    Text(e.value.size.toString() + " orders", color = T.faint, style = mono(11), modifier = Modifier.width(78.dp))
                    Text(
                        rate + " ret", style = mono(11), modifier = Modifier.width(64.dp),
                        color = if (reached > 0 && ret * 100 / reached >= 20) T.warn else T.ok,
                    )
                }
                RowDivider()
            }
            if (cities.isEmpty()) Text("No orders in this window.", color = T.faint, style = body(13))
        }
        Spacer(Modifier.height(12.dp))

        Blueprint {
            Kicker("When customers order")
            Spacer(Modifier.height(10.dp))
            val hours = IntArray(24)
            for (o in win) if (o.placedAt > 0) hours[Dates.hourOf(o.placedAt)]++
            Bars(hours.toList(), 70, highlightLast = false)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                for (h in listOf("12am", "6am", "12pm", "6pm")) {
                    Text(h, color = T.ghost, style = mono(10), modifier = Modifier.weight(1f))
                }
            }
            val peak = hours.indices.maxByOrNull { hours[it] } ?: 0
            if (win.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Busiest hour: " + (if (peak % 12 == 0) 12 else peak % 12) + (if (peak < 12) " am" else " pm") +
                        " — post new arrivals and run ads just before it.",
                    color = T.muted, style = body(12),
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Blueprint {
            Kicker("Best sellers")
            Spacer(Modifier.height(10.dp))
            val units = HashMap<String, Int>()
            val money = HashMap<String, Int>()
            for (o in live) for (i in o.items) {
                units[i.name] = (units[i.name] ?: 0) + i.qty
                money[i.name] = (money[i.name] ?: 0) + i.price * i.qty
            }
            val top = units.entries.sortedByDescending { it.value }.take(6)
            val max = (top.firstOrNull()?.value ?: 1).coerceAtLeast(1)
            for (e in top) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.key, color = T.text, style = body(13), modifier = Modifier.weight(1f), maxLines = 1)
                    Text(e.value.toString() + " pcs · " + rs(money[e.key] ?: 0), color = T.goldSoft, style = mono(11))
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(6.dp)) {
                    Box(Modifier.fillMaxWidth(e.value.toFloat() / max).height(6.dp).background(T.gold.copy(alpha = 0.8f)))
                }
                Spacer(Modifier.height(10.dp))
            }
            if (top.isEmpty()) Text("No sales in this window.", color = T.faint, style = body(13))
        }
        Spacer(Modifier.height(12.dp))

        val unpaid = s.codOutstanding
        val transit = s.orders.filter { it.st == "shipped" }
        Blueprint(Modifier.clickable { s.go(Screen.CodLedger) }) {
            Kicker("Cash on delivery")
            Spacer(Modifier.height(8.dp))
            KeyValue("With the courier (shipped)", rs(transit.sumOf { it.total }), T.ship)
            KeyValue("Delivered, not paid out yet", rs(unpaid.sumOf { it.total }), T.warn)
            KeyValue("Received", rs(s.orders.filter { it.st == "delivered" && it.codPaid }.sumOf { it.total }), T.ok)
            Spacer(Modifier.height(8.dp))
            Text("Tap to reconcile against the courier's payment.", color = T.ghost, style = body(11))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Built from the latest " + s.orders.size + " orders on this phone.",
            color = T.ghost, style = mono(10),
        )
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Blueprint(modifier) {
        Kicker(label)
        Spacer(Modifier.height(6.dp))
        Text(value, color = T.text, style = head(20, FontWeight.Bold), maxLines = 1)
    }
}
