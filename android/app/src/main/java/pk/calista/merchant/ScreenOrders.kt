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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp

/** Screen 3 — Orders: filter chips with live counts, search by name / phone / order number. */
@Composable
fun OrdersScreen(s: AppState) {
    val counts = s.counts()
    val chips = listOf("all", "pending", "confirmed", "packed", "shipped", "delivered", "returned", "fake")

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = s.query,
            onValueChange = { s.query = it },
            modifier = Modifier.fillMaxWidth().padding(14.dp),
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

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            for (c in chips) {
                val active = s.filter == c
                Box(
                    Modifier
                        .background(if (active) T.gold else androidx.compose.ui.graphics.Color.Transparent)
                        .border(1.dp, if (active) T.gold else T.line)
                        .clickable { s.filter = c }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Kicker(
                        c + "  " + (counts[c] ?: 0),
                        if (active) T.bg else T.muted,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            items(s.visibleOrders) { o -> OrderRow(o) { s.openOrder(o.id, Screen.Orders) } }
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
