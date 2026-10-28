package pk.calista.merchant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Orders: search, status + date filters with live counts, bulk actions in one request. */
@Composable
fun OrdersScreen(s: AppState) {
    val counts = s.counts()
    val chips = listOf("all", "pending", "confirmed", "packed", "shipped", "delivered", "risky", "unpaid", "returned", "cancelled", "fake")
    var selectMode by remember { mutableStateOf(false) }
    val list = s.visibleOrders

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchBox(s.query, "Name, phone, city, CN or #order", Modifier.weight(1f)) { s.query = it }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .background(if (selectMode) T.goldTint else Color.Transparent)
                    .border(1.dp, if (selectMode) T.gold else T.line)
                    .clickable {
                        selectMode = !selectMode
                        if (!selectMode) s.clearSelection()
                    }
                    .padding(horizontal = 11.dp, vertical = 17.dp),
            ) { Kicker(if (selectMode) "done" else "select", if (selectMode) T.goldSoft else T.muted) }
        }
        Spacer(Modifier.height(12.dp))

        ChoiceChips(
            chips.map { it to (it + "  " + (counts[it] ?: 0)) }.filter { it.first == "all" || (counts[it.first] ?: 0) > 0 || it.first == s.filter },
            s.filter,
            Modifier.padding(horizontal = 14.dp),
        ) { s.filter = it }
        Spacer(Modifier.height(8.dp))
        ChoiceChips(
            listOf("any" to "any day", "today" to "today", "yesterday" to "yesterday", "7d" to "last 7 days"),
            s.dateFilter,
            Modifier.padding(horizontal = 14.dp),
        ) { s.dateFilter = it }
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                list.size.toString() + " orders · " + rs(list.filter { it.st !in DEAD }.sumOf { it.total }),
                color = T.faint, style = mono(11), modifier = Modifier.weight(1f),
            )
            if (s.filter != "all" || s.dateFilter != "any" || s.query.isNotBlank()) {
                Text(
                    "clear", color = T.gold, style = mono(11),
                    modifier = Modifier.clickable { s.filter = "all"; s.dateFilter = "any"; s.query = "" },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (selectMode) {
            BulkBar(s)
            Spacer(Modifier.height(10.dp))
        }

        Refreshable(s) {
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                items(list, key = { it.id }) { o ->
                    if (selectMode) {
                        Row(
                            Modifier.fillMaxWidth().clickable { s.toggleSelect(o.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val on = o.id in s.selected
                            Box(
                                Modifier.size(22.dp)
                                    .background(if (on) T.gold else Color.Transparent)
                                    .border(1.dp, if (on) T.gold else T.line),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (on) Text("✓", color = T.bg, style = mono(12))
                            }
                            Spacer(Modifier.width(12.dp))
                            Box(Modifier.weight(1f)) { OrderRow(o, s.risk(o)) { s.toggleSelect(o.id) } }
                        }
                    } else {
                        OrderRow(o, s.risk(o)) { s.openOrder(o.id, Screen.Orders) }
                    }
                }
                item {
                    if (list.isEmpty()) {
                        Spacer(Modifier.height(30.dp))
                        Text("Nothing matches.", color = T.faint, style = body(13))
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun BulkBar(s: AppState) {
    val n = s.selected.size
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp)
            .background(T.elev).border(1.dp, T.gold).padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (n == 0) "Tap orders to select" else n.toString() + " selected",
                color = if (n == 0) T.faint else T.goldSoft,
                style = head(14),
                modifier = Modifier.weight(1f),
            )
            MiniAction("all") { s.selectAllVisible() }
            Spacer(Modifier.width(7.dp))
            MiniAction("none") { s.clearSelection() }
        }
        if (n > 0) {
            Spacer(Modifier.height(11.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                for (st in listOf("confirmed", "packed", "shipped", "delivered", "returned", "cancelled")) {
                    val c = statusColor(st)
                    Box(
                        Modifier.background(c.copy(alpha = 0.14f)).border(1.dp, c.copy(alpha = 0.55f))
                            .clickable { s.bulkStatus(st) }
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                    ) { Kicker("→ " + st, c) }
                }
                Box(
                    Modifier.background(T.ok.copy(alpha = 0.14f)).border(1.dp, T.ok.copy(alpha = 0.55f))
                        .clickable { s.bulkCodPaid(s.selected.toList()) }
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                ) { Kicker("cash received", T.ok) }
            }
        }
    }
}
