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
import androidx.compose.foundation.layout.size
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

/** Screen 4 — Order detail: timeline, WhatsApp state, call / copy, items, PostEx booking. */
@Composable
fun OrderDetailScreen(s: AppState) {
    val o = s.openOrder ?: return
    var waSheet by remember { mutableStateOf(false) }
    var waPick by remember { mutableStateOf("confirm") }

    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(o.name, color = T.text, style = head(22, FontWeight.Bold))
                    Text(o.localPhone + " · " + o.city, color = T.faint, style = body(12))
                }
                StatusTag(o.status)
            }
            Spacer(Modifier.height(14.dp))

            // status timeline
            Blueprint {
                Kicker("Progress")
                Spacer(Modifier.height(10.dp))
                val flow = listOf("pending", "confirmed", "packed", "shipped", "delivered")
                val at = flow.indexOf(o.status.lowercase())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((i, step) in flow.withIndex()) {
                        Column(Modifier.weight(1f)) {
                            Box(
                                Modifier.fillMaxWidth().height(3.dp)
                                    .background(if (i <= at) T.gold else T.line)
                            )
                            Spacer(Modifier.height(6.dp))
                            Kicker(step, if (i <= at) T.goldSoft else T.ghost)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // whatsapp state
            Blueprint(borderColor = if (o.waStatus == "confirmed") T.ok else T.line) {
                Kicker("WhatsApp confirmation")
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
                LineButton(
                    if (o.waStatus == "none") "Send confirmation" else "Message customer",
                    Modifier.fillMaxWidth(),
                ) { waSheet = true }
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LineButton("Call", Modifier.weight(1f)) { s.call(o) }
                LineButton("Copy address", Modifier.weight(1f)) { s.copyAddress(o) }
            }
            Spacer(Modifier.height(12.dp))

            Blueprint {
                Kicker("Deliver to")
                Spacer(Modifier.height(6.dp))
                Text(o.address + ", " + o.city, color = T.muted, style = body(13))
            }
            Spacer(Modifier.height(12.dp))

            Blueprint {
                Kicker("Items")
                Spacer(Modifier.height(8.dp))
                for (li in o.items) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Thumb(s.imageFor(li.name), 44, 56)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(li.name, color = T.text, style = body(13))
                            Text("size " + li.size + " · x" + li.qty, color = T.ghost, style = mono(11))
                        }
                        Text(rs(li.price * li.qty), color = T.goldSoft, style = head(14))
                    }
                }
                Spacer(Modifier.height(8.dp))
                RowDivider()
                Spacer(Modifier.height(8.dp))
                KeyValue("Total (" + o.payment + ")", rs(o.total), T.goldSoft)
                Spacer(Modifier.height(12.dp))
                GoldButton("Send items + photos on WhatsApp", Modifier.fillMaxWidth()) {
                    s.whatsAppWithPhotos(o, "confirm")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Builds one picture of the order — every item photo, size and price — " +
                        "and opens " + o.firstName + "'s chat on " + o.localPhone +
                        " with the confirmation message as its caption.",
                    color = T.ghost, style = body(11),
                )
                Spacer(Modifier.height(10.dp))
                LineButton("Share invoice PDF", Modifier.fillMaxWidth()) { s.shareInvoice(o) }
            }
            Spacer(Modifier.height(12.dp))

            // shipment / PostEx
            Blueprint {
                Kicker("Shipment")
                Spacer(Modifier.height(8.dp))
                if (o.tracking.isNotBlank()) {
                    KeyValue("Courier", if (o.courier.isBlank()) "PostEx" else o.courier)
                    KeyValue("Tracking", o.tracking, T.goldSoft)
                } else {
                    Text(
                        "Not booked. Booking calls PostEx create-order on your Worker and stores " +
                            "the CN it returns.",
                        color = T.faint, style = body(12),
                    )
                    Spacer(Modifier.height(10.dp))
                    GoldButton("Book with PostEx", Modifier.fillMaxWidth(), enabled = !s.loading) {
                        s.bookPostEx(o)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            val nextLabel = when (o.status.lowercase()) {
                "pending" -> "Confirm order"
                "confirmed" -> "Mark packed"
                "shipped" -> "Mark delivered"
                else -> ""
            }
            if (nextLabel.isNotBlank()) {
                GoldButton(nextLabel, Modifier.fillMaxWidth()) { s.advance(o) }
                Spacer(Modifier.height(10.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LineButton("Mark fake", Modifier.weight(1f)) { s.setStatus(o, "fake") }
                LineButton("Cancel order", Modifier.weight(1f)) { s.setStatus(o, "cancelled") }
            }
            Spacer(Modifier.height(30.dp))
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
                                .padding(horizontal = 9.dp, vertical = 7.dp),
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
