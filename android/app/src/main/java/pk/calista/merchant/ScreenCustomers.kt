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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Customers, built from the order history. VIPs and risky numbers are tagged. */
@Composable
fun CustomersScreen(s: AppState) {
    val all = s.customers
    val list = s.visibleCustomers
    Column(Modifier.fillMaxWidth()) {
        SearchBox(
            s.customerQuery, "Search name, phone or city",
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp),
        ) { s.customerQuery = it }
        Spacer(Modifier.height(10.dp))
        ChoiceChips(
            listOf(
                "all" to ("all  " + all.size),
                "repeat" to ("repeat  " + all.count { it.count > 1 }),
                "vip" to ("vip  " + all.count { it.vip }),
                "risky" to ("returned / fake  " + all.count { it.bad > 0 }),
            ),
            s.customerFilter,
            Modifier.padding(horizontal = 14.dp),
        ) { s.customerFilter = it }
        Spacer(Modifier.height(10.dp))
        val repeatRate = if (all.isEmpty()) 0 else all.count { it.count > 1 } * 100 / all.size
        Text(
            repeatRate.toString() + "% come back · " + rs(if (all.isEmpty()) 0 else all.sumOf { it.spent } / all.size) + " average lifetime value",
            color = T.faint, style = mono(11), modifier = Modifier.padding(horizontal = 14.dp),
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            items(list, key = { it.phone }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { s.openCustomer(c) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).border(1.dp, if (c.vip) T.gold else T.line), contentAlignment = Alignment.Center) {
                        Text(c.name.trim().take(1).uppercase().ifBlank { "?" }, color = T.goldSoft, style = head(16))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name.ifBlank { c.phone }, color = T.text, style = head(15), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            if (c.vip) { Spacer(Modifier.width(7.dp)); Tag("vip", T.gold) }
                            else if (c.count > 1) { Spacer(Modifier.width(7.dp)); Tag(c.count.toString() + "x", T.goldSoft) }
                            if (c.bad > 0) { Spacer(Modifier.width(5.dp)); Tag(c.bad.toString() + " bad", T.warn) }
                            if (s.isBlocked(c.phone)) { Spacer(Modifier.width(5.dp)); Tag("blocked", T.warn) }
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
                    Text("No customers match.", color = T.faint, style = body(13))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** One customer: lifetime value, behaviour, and every order they ever placed. */
@Composable
fun CustomerDetailScreen(s: AppState) {
    val c = s.openCustomer ?: return
    val first = c.orders.first()
    val blocked = s.isBlocked(c.phone)
    val favourite = c.orders.flatMap { it.items }.groupBy { it.name }.maxByOrNull { e -> e.value.sumOf { it.qty } }?.key
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Blueprint(borderColor = if (c.vip) T.gold else T.line) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker(if (c.vip) "VIP customer" else "Customer", if (c.vip) T.gold else T.faint, Modifier.weight(1f))
                if (blocked) Tag("blocked", T.warn)
            }
            Spacer(Modifier.height(6.dp))
            Text(c.name.ifBlank { c.phone }, color = T.text, style = head(21, FontWeight.Bold))
            Spacer(Modifier.height(2.dp))
            Text(c.phone + " · " + c.city, color = T.faint, style = mono(12))
            Spacer(Modifier.height(14.dp))
            KeyValue("Orders", c.count.toString())
            KeyValue("Delivered", c.delivered.toString(), T.ok)
            KeyValue("Returned / cancelled / fake", c.bad.toString(), if (c.bad > 0) T.warn else T.text)
            KeyValue("Lifetime value", rs(c.spent), T.goldSoft)
            KeyValue("Average order", rs(if (c.count == 0) 0 else c.orders.sumOf { it.total } / c.count))
            if (favourite != null) KeyValue("Buys most", favourite)
            KeyValue("Last order", c.last)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LineButton("Call", Modifier.weight(1f)) { s.call(first) }
            LineButton("WhatsApp", Modifier.weight(1f)) { s.whatsApp(first, "restock") }
        }
        Spacer(Modifier.height(10.dp))
        GoldButton("New order for " + first.firstName, Modifier.fillMaxWidth()) { s.newOrder(from = c) }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniAction("ask for review", T.goldSoft) { s.whatsApp(first, "review") }
            if (blocked) MiniAction("unblock number", T.gold) { s.unblock(c.phone) }
            else MiniAction("block number", T.warn) { s.block(c.phone, "Blocked from customer page") }
        }
        Spacer(Modifier.height(18.dp))
        Kicker("Order history")
        Spacer(Modifier.height(4.dp))
        for (o in c.orders) OrderRow(o) { s.openOrder(o.id, Screen.CustomerDetail) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Tag(label: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier.background(color.copy(alpha = 0.12f)).border(1.dp, color.copy(alpha = 0.6f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) { Kicker(label, color) }
}
