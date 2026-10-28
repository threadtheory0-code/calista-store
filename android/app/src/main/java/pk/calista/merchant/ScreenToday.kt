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

/** Today — what happened since midnight and everything that needs a decision now. */
@Composable
fun TodayScreen(s: AppState) {
    val st = s.stats
    val today = s.todayOrders
    val sales = today.filter { it.st !in DEAD }.sumOf { it.total }
    val pending = s.pendingOrders
    val cod = s.codOutstanding
    val low = s.products.filter { it.active && it.stock <= 3 }
    val risky = s.orders.filter { it.st in OPEN && s.risk(it).level == "high" }
    val stale = s.stalePending
    val stuck = s.stuckParcels
    val pickUnits = s.pickList(false).sumOf { it.qty }

    Refreshable(s) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            item {
                Gap(14)
                if (s.goal > 0) {
                    val pct = (sales.toFloat() / s.goal).coerceIn(0f, 1f)
                    Blueprint(Modifier.clickable { s.go(Screen.Security) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Kicker("Today's target", T.gold, Modifier.weight(1f))
                            Text(
                                (pct * 100).toInt().toString() + "%",
                                color = if (pct >= 1f) T.ok else T.goldSoft, style = mono(12),
                            )
                        }
                        Gap(8)
                        Text(rs(sales) + " of " + rs(s.goal), color = T.text, style = head(20, FontWeight.Bold))
                        Gap(10)
                        Box(Modifier.fillMaxWidth().height(6.dp).background(T.line)) {
                            Box(Modifier.fillMaxWidth(pct).height(6.dp).background(if (pct >= 1f) T.ok else T.gold))
                        }
                        if (pct < 1f) {
                            Gap(6)
                            Text(rs(s.goal - sales) + " to go", color = T.ghost, style = body(11))
                        }
                    }
                    Gap(12)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("Sales today", rs(sales), Modifier.weight(1f))
                    Kpi("Orders today", today.size.toString(), Modifier.weight(1f))
                }
                Gap(10)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("To confirm", pending.size.toString(), Modifier.weight(1f)) {
                        s.filter = "pending"; s.dateFilter = "any"; s.go(Screen.Orders)
                    }
                    Kpi("Cash to collect", rs(cod.sumOf { it.total }), Modifier.weight(1f)) {
                        s.go(Screen.CodLedger)
                    }
                }
                Gap(16)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Shortcut("New order", "WhatsApp · phone", Modifier.weight(1f)) { s.newOrder() }
                    Shortcut(
                        "Pick list",
                        if (pickUnits == 0) "nothing to pack" else pickUnits.toString() + " pieces",
                        Modifier.weight(1f),
                    ) { s.go(Screen.PickList) }
                    Shortcut("Confirm", pending.count { it.waStatus == "none" }.toString() + " not messaged", Modifier.weight(1f)) {
                        s.go(Screen.ConfirmQueue)
                    }
                }
                Gap(10)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Shortcut(
                        "Offers",
                        if (s.discounts.isEmpty()) "discount codes" else s.discounts.count { it.active }.toString() + " running",
                        Modifier.weight(1f),
                    ) { s.go(Screen.Discounts) }
                    Shortcut(
                        "Banners",
                        if (s.banners.isEmpty()) "homepage" else s.banners.count { it.active }.toString() + " showing",
                        Modifier.weight(1f),
                    ) { s.go(Screen.Banners) }
                    Shortcut("Add product", "photo + price", Modifier.weight(1f)) { s.newProduct() }
                }
                Gap(18)
            }

            val alerts = listOf(
                Triple(risky.size, "high-risk orders still open", "risky"),
                Triple(stale.size, "pending for over 24 hours", "pending"),
                Triple(stuck.size, "parcels shipped 5+ days ago, not delivered", "shipped"),
                Triple(low.size, "products at 3 or fewer pieces", "low"),
            ).filter { it.first > 0 }
            if (alerts.isNotEmpty()) {
                item {
                    Kicker("Needs attention", T.warn)
                    Gap(8)
                    Blueprint(borderColor = T.warn.copy(alpha = 0.45f), padding = 0) {
                        for ((i, a) in alerts.withIndex()) {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (a.third == "low") {
                                        s.productFilter = "low"; s.go(Screen.Products)
                                    } else {
                                        s.filter = a.third; s.dateFilter = "any"; s.go(Screen.Orders)
                                    }
                                }.padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(a.first.toString(), color = T.warn, style = head(18, FontWeight.Bold))
                                HGap(12)
                                Text(a.second, color = T.muted, style = body(13), modifier = Modifier.weight(1f))
                                Text("›", color = T.ghost, style = head(18))
                            }
                            if (i < alerts.size - 1) RowDivider()
                        }
                    }
                    Gap(18)
                }
            }

            if (st != null && st.series.isNotEmpty()) {
                item {
                    Blueprint {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Kicker("Sales · last " + st.series.size + " days", modifier = Modifier.weight(1f))
                            Text(rs(st.sales), color = T.goldSoft, style = mono(11))
                        }
                        Gap(12)
                        Bars(st.series, 88)
                    }
                    Gap(16)
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Kicker("Waiting for confirmation", T.gold, Modifier.weight(1f))
                    Text(pending.size.toString() + " pending", color = T.faint, style = mono(11))
                }
                Gap(4)
            }

            items(pending.take(8), key = { it.id }) { o ->
                OrderRow(o, s.risk(o)) { s.openOrder(o.id, Screen.Today) }
            }

            item {
                if (pending.size > 8) {
                    Gap(10)
                    LineButton("See all " + pending.size + " pending", Modifier.fillMaxWidth()) {
                        s.filter = "pending"; s.dateFilter = "any"; s.go(Screen.Orders)
                    }
                }
                Gap(28)
            }
        }
    }
}

/** Bar chart drawn with plain boxes — light enough to recompose every sync. */
@Composable
fun Bars(series: List<Int>, height: Int, highlightLast: Boolean = true) {
    val max = (series.maxOrNull() ?: 1).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(height.dp),
        horizontalArrangement = Arrangement.spacedBy(if (series.size > 14) 2.dp else 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for ((i, v) in series.withIndex()) {
            val last = highlightLast && i == series.size - 1
            Box(
                Modifier.weight(1f)
                    .height((4 + (height - 6) * v.toFloat() / max).dp)
                    .background(if (last) T.goldSoft else T.gold.copy(alpha = 0.7f))
            )
        }
    }
}

@Composable
private fun Shortcut(label: String, note: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.border(1.dp, T.line).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 12.dp),
    ) {
        Text(label, color = T.goldSoft, style = head(14, FontWeight.Bold), maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(note, color = T.ghost, style = body(11), maxLines = 1)
    }
}

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Blueprint(if (onClick != null) modifier.clickable(onClick = onClick) else modifier) {
        Kicker(label)
        Spacer(Modifier.height(8.dp))
        Text(value, color = T.text, style = head(22, FontWeight.Bold), maxLines = 1)
    }
}

/** Shared by Today, Orders, customers and the confirm queue. */
@Composable
fun OrderRow(o: Order, risk: Risk? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.name, color = T.text, style = head(15), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Text(o.ref, color = T.ghost, style = mono(11))
                    if (o.note.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.width(5.dp).height(5.dp).background(T.goldSoft))
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    o.city + " · " + o.units + (if (o.units == 1) " piece" else " pieces") + " · " + Dates.ago(o.placedAt) +
                        (if (o.source.isNotBlank() && o.source != "web") " · " + o.source else ""),
                    color = T.faint, style = body(12), maxLines = 1,
                )
                if (risk != null && risk.level != "low" && o.st in OPEN) {
                    Spacer(Modifier.height(5.dp))
                    RiskTag(risk)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(rs(o.total), color = T.goldSoft, style = head(15))
                Spacer(Modifier.height(5.dp))
                StatusTag(o.status)
                if (o.st == "delivered") {
                    Spacer(Modifier.height(4.dp))
                    Text(if (o.codPaid) "cash in" else "cash due", color = if (o.codPaid) T.ok else T.warn, style = mono(10))
                }
            }
        }
        RowDivider()
    }
}
