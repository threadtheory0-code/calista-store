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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Screen 2 — Today. Everything that needs a decision in the next hour. */
@Composable
fun TodayScreen(s: AppState) {
    val st = s.stats
    val pending = s.pendingOrders
    val low = s.products.filter { it.stock <= 3 }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        item {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("Sales today", if (st != null) rs(st.sales) else "—", Modifier.weight(1f))
                Kpi("Orders", if (st != null) st.orders.toString() else "—", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("COD in transit", if (st != null) rs(st.cod) else "—", Modifier.weight(1f))
                Kpi("Return rate", st?.returnRate ?: "—", Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Kicker("Storefront", T.gold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Shortcut(
                    "Offers",
                    if (s.discounts.isEmpty()) "discount codes" else
                        s.discounts.count { it.active }.toString() + " running",
                    Modifier.weight(1f),
                ) { s.go(Screen.Discounts) }
                Shortcut(
                    "Banners",
                    if (s.banners.isEmpty()) "homepage" else
                        s.banners.count { it.active }.toString() + " showing",
                    Modifier.weight(1f),
                ) { s.go(Screen.Banners) }
                Shortcut("Add product", "photo + price", Modifier.weight(1f)) { s.newProduct() }
            }
            Spacer(Modifier.height(18.dp))
        }

        if (st != null && st.series.isNotEmpty()) {
            item {
                Blueprint {
                    Kicker("Last 7 days")
                    Spacer(Modifier.height(12.dp))
                    val max = (st.series.maxOrNull() ?: 1).coerceAtLeast(1)
                    Row(
                        Modifier.fillMaxWidth().height(88.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        for (v in st.series) {
                            Box(
                                Modifier.weight(1f)
                                    .height((6 + 82f * v / max).dp)
                                    .background(T.gold.copy(alpha = 0.75f))
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Kicker("Needs you now", T.gold, Modifier.weight(1f))
                Text(pending.size.toString() + " pending", color = T.faint, style = mono(11))
            }
            Spacer(Modifier.height(8.dp))
        }

        items(pending.take(6)) { o ->
            OrderRow(o) { s.openOrder(o.id, Screen.Today) }
        }

        item {
            Spacer(Modifier.height(10.dp))
            LineButton("Open the confirm queue", Modifier.fillMaxWidth()) { s.go(Screen.ConfirmQueue) }
            Spacer(Modifier.height(20.dp))
            Kicker("Low stock")
            Spacer(Modifier.height(8.dp))
        }

        items(low.take(5)) { p ->
            Row(
                Modifier.fillMaxWidth().clickable { s.go(Screen.Products) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p.name, color = T.text, style = body(13), modifier = Modifier.weight(1f))
                Text(p.stock.toString() + " left", color = T.warn, style = mono(12))
            }
        }

        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun Shortcut(
    label: String,
    note: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .border(1.dp, T.line)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 12.dp),
    ) {
        Text(label, color = T.goldSoft, style = head(14, FontWeight.Bold))
        Spacer(Modifier.height(3.dp))
        Text(note, color = T.ghost, style = body(11), maxLines = 1)
    }
}

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier = Modifier) {
    Blueprint(modifier) {
        Kicker(label)
        Spacer(Modifier.height(8.dp))
        Text(value, color = T.text, style = head(24, FontWeight.Bold))
    }
}

/** Shared by Today, Orders and the confirm queue. */
@Composable
fun OrderRow(o: Order, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.name, color = T.text, style = head(15))
                    Spacer(Modifier.width(8.dp))
                    Text(o.ref, color = T.ghost, style = mono(11))
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    o.city + " · " + o.payment + " · " + o.items.size + " item",
                    color = T.faint, style = body(12),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(rs(o.total), color = T.goldSoft, style = head(15))
                Spacer(Modifier.height(5.dp))
                StatusTag(o.status)
            }
        }
        RowDivider()
    }
}
