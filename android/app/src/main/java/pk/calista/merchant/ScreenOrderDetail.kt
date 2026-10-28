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
import androidx.compose.foundation.layout.width
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

/** Order detail: risk, progress, WhatsApp, notes, delivery details, items, courier, cash. */
@Composable
fun OrderDetailScreen(s: AppState) {
    val o = s.openOrder ?: return
    var waSheet by remember { mutableStateOf(false) }
    var waPick by remember { mutableStateOf("confirm") }
    var editNote by remember(o.id) { mutableStateOf(false) }
    var noteText by remember(o.id) { mutableStateOf(o.note) }
    var editContact by remember(o.id) { mutableStateOf(false) }
    var cName by remember(o.id) { mutableStateOf(o.name) }
    var cPhone by remember(o.id) { mutableStateOf(o.localPhone) }
    var cAddress by remember(o.id) { mutableStateOf(o.address) }
    var cCity by remember(o.id) { mutableStateOf(o.city) }
    var confirmBlock by remember(o.id) { mutableStateOf(false) }
    val risk = s.risk(o)
    val customer = s.customerOf(o)
    val blocked = s.isBlocked(o.phone)

    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(o.name, color = T.text, style = head(22, FontWeight.Bold))
                    Text(
                        o.localPhone + " · " + o.city + " · " + Dates.stamp(o.placedAt),
                        color = T.faint, style = body(12),
                    )
                }
                StatusTag(o.status)
            }
            Spacer(Modifier.height(14.dp))

            // risk
            if (o.st in OPEN || risk.level != "low") {
                val rc = when (risk.level) { "high" -> T.warn; "medium" -> T.gold; else -> T.ok }
                Blueprint(borderColor = rc.copy(alpha = 0.55f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Kicker("Delivery risk", rc, Modifier.weight(1f))
                        Text(risk.level.uppercase() + " · " + risk.score, color = rc, style = mono(11))
                    }
                    Spacer(Modifier.height(8.dp))
                    if (risk.reasons.isEmpty()) {
                        Text("No warning signs on this number.", color = T.muted, style = body(13))
                    }
                    for (r in risk.reasons) Text("· " + r, color = T.muted, style = body(13))
                    if (customer != null && customer.count > 1) {
                        Spacer(Modifier.height(10.dp))
                        MiniAction(customer.count.toString() + " orders from this number", T.goldSoft) { s.openCustomer(customer) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Blueprint {
                Kicker("Progress")
                Spacer(Modifier.height(10.dp))
                val flow = listOf("pending", "confirmed", "packed", "shipped", "delivered")
                val at = flow.indexOf(o.st)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((i, step) in flow.withIndex()) {
                        Column(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth().height(3.dp).background(if (i <= at) T.gold else T.line))
                            Spacer(Modifier.height(6.dp))
                            Kicker(step, if (i <= at) T.goldSoft else T.ghost)
                        }
                    }
                }
                if (at < 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("This order is " + o.st + ".", color = T.warn, style = body(12))
                }
            }
            Spacer(Modifier.height(12.dp))

            Blueprint(borderColor = if (o.waStatus == "confirmed") T.ok else T.line) {
                Kicker("WhatsApp")
                Spacer(Modifier.height(6.dp))
                Text(
                    when (o.waStatus) {
                        "confirmed" -> "Customer replied YES — confirmed."
                        "sent" -> "Sent · awaiting reply."
                        "declined" -> "Customer said NO."
                        "replied" -> "Replied — read the thread."
                        else -> "Not contacted yet."
                    },
                    color = T.muted, style = body(13),
                )
                Spacer(Modifier.height(10.dp))
                LineButton(if (o.waStatus == "none") "Send confirmation" else "Message customer", Modifier.fillMaxWidth()) {
                    waSheet = true
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LineButton("Call", Modifier.weight(1f)) { s.call(o) }
                LineButton("Copy address", Modifier.weight(1f)) { s.copyAddress(o) }
            }
            Spacer(Modifier.height(12.dp))

            // internal note
            Blueprint {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Kicker("Note · only you see this", modifier = Modifier.weight(1f))
                    if (!editNote) MiniAction(if (o.note.isBlank()) "add" else "edit") { noteText = o.note; editNote = true }
                }
                Spacer(Modifier.height(6.dp))
                if (editNote) {
                    Field("Note", noteText, "e.g. wants delivery after 5 pm", lines = 3) { noteText = it }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LineButton("Cancel", Modifier.weight(1f)) { editNote = false }
                        GoldButton("Save note", Modifier.weight(1f)) { s.saveNote(o, noteText); editNote = false }
                    }
                } else {
                    Text(o.note.ifBlank { "No note." }, color = if (o.note.isBlank()) T.ghost else T.muted, style = body(13))
                }
            }
            Spacer(Modifier.height(12.dp))

            Blueprint {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Kicker("Deliver to", modifier = Modifier.weight(1f))
                    if (!editContact && o.tracking.isBlank()) MiniAction("edit") { editContact = true }
                }
                Spacer(Modifier.height(6.dp))
                if (editContact) {
                    Field("Name", cName, "Customer name") { cName = it }
                    Spacer(Modifier.height(10.dp))
                    Field("Mobile", cPhone, "03XXXXXXXXX", number = true) { cPhone = it }
                    Spacer(Modifier.height(10.dp))
                    Field("Address", cAddress, "House, street, area, landmark", lines = 3) { cAddress = it }
                    Spacer(Modifier.height(10.dp))
                    Field("City", cCity, "Lahore") { cCity = it }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LineButton("Cancel", Modifier.weight(1f)) { editContact = false }
                        GoldButton("Save", Modifier.weight(1f)) {
                            s.saveContact(o, cName.trim(), cPhone.trim(), cAddress.trim(), cCity.trim())
                            editContact = false
                        }
                    }
                } else {
                    Text(o.address + ", " + o.city, color = T.muted, style = body(13))
                }
            }
            Spacer(Modifier.height(12.dp))

            Blueprint {
                Kicker("Items")
                Spacer(Modifier.height(8.dp))
                for (li in o.items) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(s.imageFor(li.name), 44, 56)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(li.name, color = T.text, style = body(13))
                            Text(
                                (if (li.size != "-") "size " + li.size + " · " else "") + "x" + li.qty + " · " + rs(li.price) + " each",
                                color = T.ghost, style = mono(11),
                            )
                        }
                        Text(rs(li.price * li.qty), color = T.goldSoft, style = head(14))
                    }
                }
                Spacer(Modifier.height(8.dp))
                RowDivider()
                Spacer(Modifier.height(8.dp))
                if (o.discountAmount > 0) {
                    KeyValue(
                        "Discount" + (if (o.discountCode.isNotBlank()) " · " + o.discountCode else ""),
                        "−" + rs(o.discountAmount), T.ok,
                    )
                }
                KeyValue("Total (" + o.payment + ")", rs(o.total), T.goldSoft)
                Spacer(Modifier.height(12.dp))
                GoldButton("Send items + photos on WhatsApp", Modifier.fillMaxWidth()) { s.whatsAppWithPhotos(o, "confirm") }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LineButton("Invoice PDF", Modifier.weight(1f)) { s.shareInvoice(o) }
                    LineButton("Order again", Modifier.weight(1f)) { s.newOrder(copy = o) }
                }
            }
            Spacer(Modifier.height(12.dp))

            Blueprint {
                Kicker("Shipment")
                Spacer(Modifier.height(8.dp))
                if (o.tracking.isNotBlank()) {
                    KeyValue("Courier", o.courier.ifBlank { "PostEx" })
                    KeyValue("Tracking", o.tracking, T.goldSoft)
                    val live = s.courierStatus[o.id] ?: o.postexStatus
                    if (live.isNotBlank()) KeyValue("Courier says", live, T.ship)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LineButton("Check status", Modifier.weight(1f)) { s.track(o) }
                        LineButton("Send tracking", Modifier.weight(1f)) { s.whatsApp(o, "dispatch") }
                    }
                } else {
                    Text(
                        if (risk.level == "high") "High risk — confirm on WhatsApp or call before booking."
                        else "Not booked yet. Booking creates the PostEx parcel and saves the CN here.",
                        color = if (risk.level == "high") T.warn else T.faint, style = body(12),
                    )
                    Spacer(Modifier.height(10.dp))
                    GoldButton("Book with PostEx", Modifier.fillMaxWidth(), enabled = !s.loading && o.st in OPEN) {
                        s.bookPostEx(o)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (o.st == "delivered") {
                Blueprint(borderColor = if (o.codPaid) T.ok.copy(alpha = 0.6f) else T.warn.copy(alpha = 0.5f)) {
                    Kicker("Cash on delivery")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (o.codPaid) "Received from the courier." else rs(o.total) + " delivered — not yet paid out to you.",
                        color = T.muted, style = body(13),
                    )
                    Spacer(Modifier.height(10.dp))
                    if (o.codPaid) LineButton("Mark as not received", Modifier.fillMaxWidth()) { s.setCodPaid(o, false) }
                    else GoldButton("Cash received", Modifier.fillMaxWidth()) { s.setCodPaid(o, true) }
                }
                Spacer(Modifier.height(12.dp))
            }

            val nextLabel = when (o.st) {
                "pending" -> "Confirm order"
                "confirmed" -> "Mark packed"
                "shipped" -> "Mark delivered"
                else -> ""
            }
            if (nextLabel.isNotBlank()) {
                GoldButton(nextLabel, Modifier.fillMaxWidth()) { s.advance(o) }
                Spacer(Modifier.height(10.dp))
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (o.st == "packed") MiniAction("mark shipped", T.ship) { s.setStatus(o, "shipped") }
                if (o.st == "shipped" || o.st == "delivered") MiniAction("returned", T.warn) { s.setStatus(o, "returned") }
                if (o.st in OPEN) MiniAction("mark fake", T.warn) { s.setStatus(o, "fake") }
                if (o.st in OPEN) MiniAction("cancel order", T.warn) { s.setStatus(o, "cancelled") }
                if (o.st in DEAD) MiniAction("reopen", T.gold) { s.setStatus(o, "pending") }
                if (blocked) MiniAction("unblock number", T.gold) { s.unblock(o.localPhone) }
                else MiniAction("block number", T.warn) { confirmBlock = true }
            }
            Spacer(Modifier.height(30.dp))
        }

        if (confirmBlock) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(T.surface).border(1.dp, T.warn).padding(16.dp),
            ) {
                Text("Block " + o.localPhone + "?", color = T.text, style = head(19, FontWeight.Bold))
                Spacer(Modifier.height(6.dp))
                Text(
                    "The website will refuse new orders from this number. This order is marked fake. " +
                        "You can unblock any time in Settings.",
                    color = T.faint, style = body(12),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LineButton("Keep", Modifier.weight(1f)) { confirmBlock = false }
                    GoldButton("Block", Modifier.weight(1f)) {
                        confirmBlock = false
                        s.block(o.localPhone, "Order " + o.ref + " · " + o.st)
                        if (o.st in OPEN) s.setStatus(o, "fake")
                    }
                }
            }
        }

        if (waSheet) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(T.surface).border(1.dp, T.gold).padding(16.dp),
            ) {
                Text("Message " + o.firstName, color = T.text, style = head(19, FontWeight.Bold))
                Text(o.localPhone + " · opens WhatsApp with the text filled in", color = T.faint, style = body(12))
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    for (k in WaTemplates.keys) {
                        val active = waPick == k
                        Box(
                            Modifier
                                .background(if (active) T.goldTint else Color.Transparent)
                                .border(1.dp, if (active) T.gold else T.line)
                                .clickable { waPick = k }
                                .padding(horizontal = 9.dp, vertical = 8.dp),
                        ) { Kicker(WaTemplates.label(k), if (active) T.goldSoft else T.muted) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().border(1.dp, T.line).padding(12.dp)) {
                    Text(WaTemplates.fill(waPick, o), color = T.muted, style = body(13))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LineButton("Close", Modifier.weight(1f)) { waSheet = false }
                    GoldButton("Open WhatsApp", Modifier.weight(2f)) {
                        waSheet = false
                        s.whatsApp(o, waPick)
                    }
                }
            }
        }
    }
}
