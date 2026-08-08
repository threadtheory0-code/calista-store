package pk.calista.merchant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Screen 5 — Confirm queue: every pending order and its WhatsApp state, in one pass. */
@Composable
fun ConfirmQueueScreen(s: AppState) {
    val pending = s.pendingOrders

    LazyColumn(Modifier.fillMaxWidth().padding(14.dp)) {
        item {
            Blueprint {
                Kicker("Why this screen exists", T.gold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Confirm on WhatsApp before you pay a courier. Two sends with no reply is the " +
                        "signal to mark an order fake.",
                    color = T.faint, style = body(12),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(pending.size.toString() + " pending", color = T.muted, style = head(15))
            Spacer(Modifier.height(6.dp))
        }

        items(pending) { o ->
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(o.name + "  " + o.ref, color = T.text, style = head(15))
                        Text(
                            when (o.waStatus) {
                                "sent" -> "sent · awaiting reply"
                                "confirmed" -> "replied YES"
                                "declined" -> "said NO"
                                "replied" -> "replied — read it"
                                else -> "not contacted"
                            },
                            color = if (o.waStatus == "none") T.warn else T.faint,
                            style = body(12),
                        )
                    }
                    LineButton(if (o.waStatus == "none") "Send" else "Follow up") {
                        s.whatsApp(o, "confirm")
                    }
                }
                RowDivider()
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            GoldButton("Send to everyone not contacted", Modifier.fillMaxWidth()) {
                val targets = pending.filter { it.waStatus == "none" }
                if (targets.isEmpty()) s.flash("Everyone has been contacted")
                else {
                    // WhatsApp opens one chat at a time; start with the oldest.
                    s.whatsApp(targets.last(), "confirm")
                    s.flash("Opening " + targets.size + " chats, oldest first")
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}
