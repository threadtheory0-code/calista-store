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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp

/** Screen 3 — Orders: search, filter chips with live counts, and bulk status changes. */
@Composable
fun OrdersScreen(s: AppState) {
    val counts = s.counts()
    val chips = listOf("all", "pending", "confirmed", "packed", "shipped", "delivered", "returned")
    var selectMode by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = s.query,
                onValueChange = { s.query = it },
                modifier = Modifier.weight(1f),
                shape = RectangleShape,
                singleLine = true,
                placeholder = { Text("Name, phone or order number", color = T.ghost, style = body(13)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = T.gold,
                    unfocusedBorderColor = T.line,
                    focusedTextColor = T.text,
                    unfocusedTextColor = T.text,
                    cursorColor = T.gold,
                    focusedContainerColor = T.surface,
                    unfocusedContainerColor = T.surface,
                ),
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .background(if (selectMode) T.goldTint else Color.Transparent)
                    .border(1.dp, if (selectMode) T.gold else T.line)
                    .clickable {
                        selectMode = !selectMode
                        if (!selectMode) s.clearSelection()
                    }
                    .padding(horizontal = 11.dp, vertical = 14.dp),
            ) { Kicker(if (selectMode) "done" else "select", if (selectMode) T.goldSoft else T.muted) }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            for (c in chips) {
                val active = s.filter == c
                Box(
                    Modifier
                        .background(if (active) T.gold else Color.Transparent)
                        .border(1.dp, if (active) T.gold else T.line)
                        .clickable { s.filter = c }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Kicker(c + "  " + (counts[c] ?: 0), if (active) T.bg else T.muted)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (selectMode) {
            BulkBar(s)
            Spacer(Modifier.height(10.dp))
        }

        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            items(s.visibleOrders) { o ->
                if (selectMode) {
                    Row(
                        Modifier.fillMaxWidth().clickable { s.toggleSelect(o.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val on = o.id in s.selected
                        Box(
                            Modifier.size(20.dp)
                                .background(if (on) T.gold else Color.Transparent)
                                .border(1.dp, if (on) T.gold else T.line),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (on) Text("✓", color = T.bg, style = mono(12))
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f)) { OrderRow(o) { s.toggleSelect(o.id) } }
                    }
                } else {
                    OrderRow(o) { s.openOrder(o.id, Screen.Orders) }
                }
            }
            item {
                if (s.visibleOrders.isEmpty()) {
                    Spacer(Modifier.height(30.dp))
                    Text("Nothing matches.", color = T.faint, style = body(13))
                }
                Spacer(Modifier.height(24.dp))
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
            Box(
                Modifier.border(1.dp, T.line).clickable { s.selectAllVisible() }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            ) { Kicker("all", T.muted) }
            Spacer(Modifier.width(7.dp))
            Box(
                Modifier.border(1.dp, T.line).clickable { s.clearSelection() }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            ) { Kicker("none", T.muted) }
        }
        if (n > 0) {
            Spacer(Modifier.height(11.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                for (st in listOf("confirmed", "packed", "shipped", "delivered", "cancelled")) {
                    val c = statusColor(st)
                    Box(
                        Modifier.background(c.copy(alpha = 0.14f)).border(1.dp, c.copy(alpha = 0.55f))
                            .clickable { s.bulkStatus(st) }
                            .padding(horizontal = 11.dp, vertical = 8.dp),
                    ) { Kicker("→ " + st, c) }
                }
            }
        }
    }
}
