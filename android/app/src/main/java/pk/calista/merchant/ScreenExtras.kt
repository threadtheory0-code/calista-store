package pk.calista.merchant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// New order — for orders taken on WhatsApp, Instagram or the phone.
// ---------------------------------------------------------------------------

@Composable
fun NewOrderScreen(s: AppState) {
    var picking by remember { mutableStateOf(false) }
    var pq by remember { mutableStateOf("") }

    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {
            Kicker("Where did it come from?")
            Spacer(Modifier.height(8.dp))
            ChoiceChips(
                listOf("whatsapp" to "WhatsApp", "instagram" to "Instagram", "facebook" to "Facebook", "phone" to "Phone call", "walk-in" to "Walk-in"),
                s.nSource,
            ) { s.nSource = it }
            Spacer(Modifier.height(16.dp))

            Field("Customer name", s.nName, "Ayesha Khan") { s.nName = it }
            Spacer(Modifier.height(12.dp))
            Field("Mobile", s.nPhone, "03XXXXXXXXX", number = true) { s.nPhone = it }
            val known = s.customers.firstOrNull {
                s.nPhone.length >= 10 && it.phone == s.nPhone.filter { c -> c.isDigit() }.let { d -> if (d.startsWith("92")) "0" + d.substring(2) else d }
            }
            if (known != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Known customer · " + known.count + " orders · " + known.bad + " returned/cancelled",
                        color = if (known.bad > 0) T.warn else T.ok, style = body(12), modifier = Modifier.weight(1f),
                    )
                    if (s.nAddress.isBlank()) MiniAction("use last address", T.goldSoft) {
                        s.nName = s.nName.ifBlank { known.name }
                        s.nAddress = known.orders.first().address
                        s.nCity = known.city
                    }
                }
            }
            if (s.nPhone.isNotBlank() && s.isBlocked(s.nPhone)) {
                Spacer(Modifier.height(6.dp))
                Text("This number is on your blocklist.", color = T.warn, style = body(12))
            }
            Spacer(Modifier.height(12.dp))
            Field("Address", s.nAddress, "House, street, area, nearby landmark", lines = 3) { s.nAddress = it }
            Spacer(Modifier.height(12.dp))
            Field("City", s.nCity, "Lahore") { s.nCity = it }
            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker("Items", modifier = Modifier.weight(1f))
                MiniAction("+ add product", T.gold) { picking = true }
            }
            Spacer(Modifier.height(8.dp))
            if (s.nLines.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().border(1.dp, T.line).clickable { picking = true }.padding(18.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Tap to pick from your products", color = T.faint, style = body(13)) }
            }
            for ((i, l) in s.nLines.withIndex()) {
                Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).border(1.dp, T.line).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Thumb(s.abs(l.image), 44, 56)
                        Spacer(Modifier.width(10.dp))
                        Text(l.name, color = T.text, style = body(13), modifier = Modifier.weight(1f), maxLines = 2)
                        Box(
                            Modifier.size(34.dp).border(1.dp, T.line).clickable { s.setLine(i, l.copy(qty = l.qty - 1)) },
                            contentAlignment = Alignment.Center,
                        ) { Text("−", color = T.gold, style = head(16)) }
                        Text(l.qty.toString(), color = T.text, style = head(15), modifier = Modifier.width(34.dp).padding(start = 12.dp))
                        Box(
                            Modifier.size(34.dp).border(1.dp, T.line).clickable { s.setLine(i, l.copy(qty = l.qty + 1)) },
                            contentAlignment = Alignment.Center,
                        ) { Text("+", color = T.gold, style = head(16)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field("Size", l.size, "optional", Modifier.weight(1f)) { s.setLine(i, l.copy(size = it)) }
                        Field("Price each", l.price.toString(), "0", Modifier.weight(1f), number = true) {
                            s.setLine(i, l.copy(price = it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Field("Discount (Rs)", s.nDiscount, "0", number = true) { s.nDiscount = it }
            Spacer(Modifier.height(12.dp))
            Field("Note", s.nNote, "e.g. deliver after 5 pm", lines = 2) { s.nNote = it }
            Spacer(Modifier.height(16.dp))

            Kicker("Start as")
            Spacer(Modifier.height(8.dp))
            ChoiceChips(listOf("confirmed" to "confirmed", "pending" to "pending", "packed" to "packed"), s.nStatus) { s.nStatus = it }
            Spacer(Modifier.height(16.dp))

            Blueprint {
                KeyValue("Subtotal", rs(s.nSubtotal))
                if ((s.nDiscount.filter { it.isDigit() }.toIntOrNull() ?: 0) > 0) {
                    KeyValue("Discount", "−" + rs(s.nDiscount.filter { it.isDigit() }.toIntOrNull() ?: 0), T.ok)
                }
                KeyValue("Customer pays (COD)", rs(s.nTotal), T.goldSoft)
            }
            Spacer(Modifier.height(16.dp))
            GoldButton(if (s.saving) "Creating…" else "Create order", Modifier.fillMaxWidth(), enabled = !s.saving) {
                s.saveNewOrder()
            }
            Spacer(Modifier.height(8.dp))
            Text("Stock comes down automatically, the same as a website order.", color = T.ghost, style = body(11))
            Spacer(Modifier.height(30.dp))
        }

        if (picking) {
            Column(Modifier.fillMaxSize().background(T.screen).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SearchBox(pq, "Search products", Modifier.weight(1f)) { pq = it }
                    Spacer(Modifier.width(10.dp))
                    MiniAction("done", T.gold) { picking = false }
                }
                Spacer(Modifier.height(10.dp))
                val list = s.products.filter { it.active && (pq.isBlank() || it.name.contains(pq, true) || it.fabric.contains(pq, true)) }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(list, key = { it.id }) { p ->
                        val inCart = s.nLines.filter { it.productId == p.id }.sumOf { it.qty }
                        Row(
                            Modifier.fillMaxWidth().clickable { s.addLine(p) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Thumb(s.abs(p.image), 44, 56)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = T.text, style = body(13), maxLines = 2)
                                Text(
                                    rs(p.unitPrice) + " · " + p.stock + " in stock",
                                    color = if (p.stock <= 0) T.warn else T.ghost, style = mono(11),
                                )
                            }
                            if (inCart > 0) {
                                Box(Modifier.background(T.gold).padding(horizontal = 7.dp, vertical = 3.dp)) {
                                    Text("×" + inCart, color = T.bg, style = mono(11))
                                }
                            } else {
                                Text("+", color = T.gold, style = head(20))
                            }
                        }
                        RowDivider()
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pick list — what to take off the shelf for every confirmed order.
// ---------------------------------------------------------------------------

@Composable
fun PickListScreen(s: AppState) {
    var withPending by remember { mutableStateOf(false) }
    var ticked by remember { mutableStateOf<Set<String>>(emptySet()) }
    val lines = s.pickList(withPending)
    val orderIds = lines.flatMap { it.orderIds }.distinct()

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            ChoiceChips(
                listOf("confirmed" to "confirmed only", "all" to "confirmed + pending"),
                if (withPending) "all" else "confirmed",
            ) { withPending = it == "all" }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    lines.sumOf { it.qty }.toString() + " pieces · " + orderIds.size + " orders",
                    color = T.text, style = head(16, FontWeight.Bold), modifier = Modifier.weight(1f),
                )
                Text(ticked.size.toString() + "/" + lines.size + " picked", color = T.faint, style = mono(11))
            }
            Spacer(Modifier.height(10.dp))
        }
        items(lines, key = { it.name + "|" + it.size }) { l ->
            val k = l.name + "|" + l.size
            val on = k in ticked
            Row(
                Modifier.fillMaxWidth().clickable { ticked = if (on) ticked - k else ticked + k }.padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(22.dp).background(if (on) T.gold else Color.Transparent).border(1.dp, if (on) T.gold else T.line),
                    contentAlignment = Alignment.Center,
                ) { if (on) Text("✓", color = T.bg, style = mono(12)) }
                Spacer(Modifier.width(12.dp))
                Thumb(s.imageFor(l.name), 40, 50)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.name, color = if (on) T.ghost else T.text, style = body(13), maxLines = 2)
                    Text(
                        (if (l.size != "-") "size " + l.size + " · " else "") + l.orderIds.distinct().joinToString(" ") { "#" + it },
                        color = T.ghost, style = mono(10), maxLines = 1,
                    )
                }
                Text("×" + l.qty, color = T.goldSoft, style = head(18, FontWeight.Bold))
            }
            RowDivider()
        }
        item {
            if (lines.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("Nothing to pack. Confirmed orders appear here.", color = T.faint, style = body(13))
            } else {
                Spacer(Modifier.height(16.dp))
                LineButton("Share list (WhatsApp, print)", Modifier.fillMaxWidth()) { s.sharePickList(lines) }
                Spacer(Modifier.height(10.dp))
                val confirmedIds = s.orders.filter { it.id in orderIds && it.st == "confirmed" }.map { it.id }
                GoldButton("Mark " + confirmedIds.size + " orders packed", Modifier.fillMaxWidth(), enabled = confirmedIds.isNotEmpty()) {
                    s.selected = confirmedIds.toSet()
                    s.bulkStatus("packed")
                    ticked = emptySet()
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Cash to collect — delivered COD the courier has not paid out yet.
// ---------------------------------------------------------------------------

@Composable
fun CodLedgerScreen(s: AppState) {
    val due = s.codOutstanding
    val transit = s.orders.filter { it.st == "shipped" }
    var sel by remember { mutableStateOf<Set<Long>>(emptySet()) }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Blueprint(Modifier.weight(1f)) {
                    Kicker("Delivered, unpaid")
                    Spacer(Modifier.height(6.dp))
                    Text(rs(due.sumOf { it.total }), color = T.warn, style = head(20, FontWeight.Bold))
                    Text(due.size.toString() + " parcels", color = T.ghost, style = mono(10))
                }
                Blueprint(Modifier.weight(1f)) {
                    Kicker("With the courier")
                    Spacer(Modifier.height(6.dp))
                    Text(rs(transit.sumOf { it.total }), color = T.ship, style = head(20, FontWeight.Bold))
                    Text(transit.size.toString() + " parcels", color = T.ghost, style = mono(10))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "When the courier's payment lands, tick the parcels it covers and mark them received. " +
                    "Whatever is left here is money still owed to you.",
                color = T.faint, style = body(12),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (sel.isEmpty()) "Nothing ticked" else sel.size.toString() + " ticked · " + rs(due.filter { it.id in sel }.sumOf { it.total }),
                    color = T.goldSoft, style = head(14), modifier = Modifier.weight(1f),
                )
                MiniAction("all") { sel = due.map { it.id }.toSet() }
                Spacer(Modifier.width(7.dp))
                MiniAction("none") { sel = emptySet() }
            }
            Spacer(Modifier.height(6.dp))
        }
        items(due, key = { it.id }) { o ->
            val on = o.id in sel
            Row(
                Modifier.fillMaxWidth().clickable { sel = if (on) sel - o.id else sel + o.id },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(22.dp).background(if (on) T.gold else Color.Transparent).border(1.dp, if (on) T.gold else T.line),
                    contentAlignment = Alignment.Center,
                ) { if (on) Text("✓", color = T.bg, style = mono(12)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 11.dp)) {
                    Text(o.name + "  " + o.ref, color = T.text, style = head(14))
                    Text((if (o.tracking.isBlank()) "no CN" else o.tracking) + " · " + Dates.ago(o.placedAt), color = T.ghost, style = mono(10))
                }
                Text(rs(o.total), color = T.goldSoft, style = head(14))
            }
            RowDivider()
        }
        item {
            if (due.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("Every delivered parcel has been paid out.", color = T.ok, style = body(13))
            } else {
                Spacer(Modifier.height(16.dp))
                GoldButton("Mark " + sel.size + " as cash received", Modifier.fillMaxWidth(), enabled = sel.isNotEmpty()) {
                    s.bulkCodPaid(sel.toList())
                    sel = emptySet()
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Reviews — approve what customers wrote before it shows on the website.
// ---------------------------------------------------------------------------

@Composable
fun ReviewsScreen(s: AppState) {
    var tab by remember { mutableStateOf("waiting") }
    val list = s.reviews.filter { if (tab == "waiting") !it.approved else it.approved }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            ChoiceChips(
                listOf(
                    "waiting" to ("waiting  " + s.reviews.count { !it.approved }),
                    "live" to ("live  " + s.reviews.count { it.approved }),
                ),
                tab,
            ) { tab = it }
            Spacer(Modifier.height(12.dp))
        }
        items(list, key = { it.id }) { r ->
            Blueprint(Modifier.padding(bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("★".repeat(r.rating.coerceIn(0, 5)) + "☆".repeat((5 - r.rating).coerceIn(0, 5)), color = T.gold, style = body(14), modifier = Modifier.weight(1f))
                    Text(Dates.ago(Dates.parse(r.created)), color = T.ghost, style = mono(10))
                }
                Spacer(Modifier.height(6.dp))
                Text(r.body.ifBlank { "(no text)" }, color = T.muted, style = body(13))
                Spacer(Modifier.height(8.dp))
                Text(
                    r.name + (if (r.city.isNotBlank()) " · " + r.city else "") + (if (r.product.isNotBlank()) " · " + r.product else ""),
                    color = T.ghost, style = mono(11),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.approved) MiniAction("hide", T.muted) { s.approveReview(r, false) }
                    else MiniAction("approve · show on website", T.ok) { s.approveReview(r, true) }
                    MiniAction("delete", T.warn) { s.deleteReview(r) }
                }
            }
        }
        item {
            if (list.isEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(if (tab == "waiting") "No reviews waiting." else "No reviews live yet.", color = T.faint, style = body(13))
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Blocked numbers — refused at the website checkout.
// ---------------------------------------------------------------------------

@Composable
fun BlockedScreen(s: AppState) {
    var phone by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            Text(
                "Orders from these numbers are refused at checkout with a polite message asking them to " +
                    "order on WhatsApp — so you decide, not the courier.",
                color = T.faint, style = body(12),
            )
            Spacer(Modifier.height(14.dp))
            Field("Mobile", phone, "03XXXXXXXXX", number = true) { phone = it }
            Spacer(Modifier.height(10.dp))
            Field("Reason", reason, "refused 2 parcels") { reason = it }
            Spacer(Modifier.height(12.dp))
            GoldButton("Block number", Modifier.fillMaxWidth(), enabled = phone.length >= 10) {
                s.block(phone.trim(), reason.trim()); phone = ""; reason = ""
            }
            Spacer(Modifier.height(18.dp))
            Kicker(s.blocked.size.toString() + " blocked")
            Spacer(Modifier.height(6.dp))
        }
        items(s.blocked, key = { it.phone }) { b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.phone, color = T.text, style = mono(13))
                    if (b.reason.isNotBlank()) Text(b.reason, color = T.ghost, style = body(11))
                }
                MiniAction("unblock", T.gold) { s.unblock(b.phone) }
            }
            RowDivider()
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

// ---------------------------------------------------------------------------
// App lock + daily target
// ---------------------------------------------------------------------------

@Composable
fun SecurityScreen(s: AppState) {
    var newPin by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf(if (s.goal > 0) s.goal.toString() else "") }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Blueprint {
            Kicker("App lock")
            Spacer(Modifier.height(6.dp))
            Text(
                if (s.pin.isBlank()) "Off. Anyone holding this phone can open your store."
                else "On. The app asks for the PIN when opened and after two minutes away.",
                color = T.muted, style = body(13),
            )
            Spacer(Modifier.height(12.dp))
            Field("4-digit PIN", newPin, "••••", number = true) { newPin = it.filter { c -> c.isDigit() }.take(4) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.pin.isNotBlank()) LineButton("Turn off", Modifier.weight(1f)) { s.changePin(""); newPin = "" }
                GoldButton(if (s.pin.isBlank()) "Turn on" else "Change PIN", Modifier.weight(1f), enabled = newPin.length == 4) {
                    s.changePin(newPin); newPin = ""
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Blueprint {
            Kicker("Daily sales target")
            Spacer(Modifier.height(6.dp))
            Text("Shows a progress bar on Today and in the 8 pm summary.", color = T.muted, style = body(13))
            Spacer(Modifier.height(12.dp))
            Field("Target (Rs)", goal, "50000", number = true) { goal = it }
            Spacer(Modifier.height(12.dp))
            GoldButton("Save target", Modifier.fillMaxWidth()) { s.setGoal(goal) }
        }
        Spacer(Modifier.height(30.dp))
    }
}
