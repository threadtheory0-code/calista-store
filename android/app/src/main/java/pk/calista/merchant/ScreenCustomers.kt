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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Screen — Customers, built from the order history. Repeat buyers first. */
@Composable
fun CustomersScreen(s: AppState) {
    val list = s.visibleCustomers
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = s.customerQuery,
            onValueChange = { s.customerQuery = it },
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp),
            shape = RectangleShape,
            singleLine = true,
            placeholder = { Text("Search name or phone", color = T.ghost, style = body(13)) },
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
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Stat("customers", list.size.toString())
            Stat("repeat", list.count { it.count > 1 }.toString())
            Stat("returns", list.sumOf { it.returns }.toString())
        }
        Spacer(Modifier.height(14.dp))

        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            items(list) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { s.openCustomer(c) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).border(1.dp, T.line),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            c.name.trim().take(1).uppercase().ifBlank { "?" },
                            color = T.goldSoft, style = head(16),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name.ifBlank { c.phone }, color = T.text, style = head(15))
                            if (c.count > 1) {
                                Spacer(Modifier.width(7.dp))
                                Box(
                                    Modifier.background(T.goldTint).border(1.dp, T.gold)
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                ) { Kicker(c.count.toString() + "x", T.goldSoft) }
                            }
                        }
                        Text(c.phone + " · " + c.city, color = T.ghost, style = mono(11))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(rs(c.spent), color = T.goldSoft, style = head(14, FontWeight.Bold))
                        Text(c.last, color = T.ghost, style = mono(10))
                    }
                }
                RowDivider()
            }
            item {
                if (list.isEmpty()) {
                    Spacer(Modifier.height(30.dp))
                    Text("No customers yet.", color = T.faint, style = body(13))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Screen — one customer: lifetime value and every order they ever placed. */
@Composable
fun CustomerDetailScreen(s: AppState) {
    val c = s.openCustomer ?: return
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Blueprint {
            Kicker("Customer")
            Spacer(Modifier.height(6.dp))
            Text(c.name.ifBlank { c.phone }, color = T.text, style = head(21, FontWeight.Bold))
            Spacer(Modifier.height(2.dp))
            Text(c.phone + " · " + c.city, color = T.faint, style = mono(12))
            Spacer(Modifier.height(14.dp))
            KeyValue("Orders", c.count.toString())
            KeyValue("Lifetime value", rs(c.spent), T.goldSoft)
            KeyValue("Returned", c.returns.toString(), if (c.returns > 0) T.warn else T.text)
            KeyValue("Last order", c.last)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LineButton("Call", Modifier.weight(1f)) { s.call(c.orders.first()) }
            LineButton("WhatsApp", Modifier.weight(1f)) { s.whatsApp(c.orders.first(), "confirm") }
        }
        Spacer(Modifier.height(18.dp))
        Kicker("Order history")
        Spacer(Modifier.height(4.dp))
        for (o in c.orders) OrderRow(o) { s.openOrder(o.id, Screen.CustomerDetail) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(Modifier.border(1.dp, T.line).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(value, color = T.text, style = head(18, FontWeight.Bold))
        Kicker(label, T.ghost)
    }
}
