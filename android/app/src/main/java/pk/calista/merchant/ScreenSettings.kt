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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Screen 8 — Settings. */
@Composable
fun SettingsScreen(s: AppState) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SettingRow("Store connections", "Cloudflare account, admin token, PostEx", "linked") {
            s.go(Screen.Connections)
        }
        SettingRow("WhatsApp confirmations", "Templates and confirmation state", "on") {
            s.backTo = Screen.Settings
            s.go(Screen.ConfirmQueue)
        }
        SettingRow("Courier accounts", "PostEx live · Leopards, TCS manual", "1 live") {
            s.go(Screen.Connections)
        }
        SettingRow("Sync now", "Pull orders, stats and products", s.lastSync) { s.refresh() }
        SettingRow("Sign out", "Clears the token from this phone", "") { s.signOut() }

        Spacer(Modifier.height(18.dp))
        Text(
            "calista-merchant 1.0.0 · d1 kapra_store_db · r2 calista-images",
            color = T.ghost, style = mono(10), modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun SettingRow(label: String, sub: String, right: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = T.text, style = head(15))
                Spacer(Modifier.height(2.dp))
                Text(sub, color = T.ghost, style = body(11))
            }
            Text(right, color = T.faint, style = mono(11))
        }
        RowDivider()
    }
}

/** Screen 9 — Store connections: the live wiring, in one place. */
@Composable
fun ConnectionsScreen(s: AppState) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {

        Blueprint {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Cloudflare — calista store", color = T.text, style = head(16, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Dot(if (s.orders.isEmpty()) T.warn else T.ok)
            }
            Spacer(Modifier.height(12.dp))
            KeyValue("Account ID", "ea0cb1a3-42fd-44c0-b4aa-ab6c977bcd89", T.goldSoft)
            KeyValue("Store address", s.storeUrl)
            KeyValue("D1 · R2", "kapra_store_db · calista-images")
            KeyValue("Admin token", "on this device only")
            KeyValue("Last sync", s.lastSync)
            Spacer(Modifier.height(12.dp))
            LineButton("Test store connection", Modifier.fillMaxWidth()) { s.refresh() }
        }
        Spacer(Modifier.height(12.dp))

        Blueprint {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("PostEx — courier account", color = T.text, style = head(16, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Dot(T.ok)
            }
            Spacer(Modifier.height(12.dp))
            KeyValue("API token", "NTc4NDdmNWM4…MjJmOWM=", T.goldSoft)
            KeyValue("Merchant", "57847f5c · Calista")
            KeyValue("Order type", "COD · normal delivery")
            KeyValue("Booked parcels", s.orders.count { it.tracking.isNotBlank() }.toString())
            Spacer(Modifier.height(10.dp))
            Text(
                "The token lives as the POSTEX_TOKEN secret on the Worker, never inside this APK. " +
                    "Booking goes through /api/admin/courier/postex.",
                color = T.ghost, style = body(11),
            )
        }
        Spacer(Modifier.height(12.dp))

        Blueprint {
            Kicker("Endpoints in use")
            Spacer(Modifier.height(8.dp))
            for (e in listOf(
                "GET  /api/admin/orders?since=",
                "PATCH /api/admin/orders/:id",
                "GET  /api/admin/stats?range=",
                "GET  /api/admin/products",
                "PATCH /api/admin/products/:id",
                "POST /api/admin/courier/postex",
                "GET  /api/admin/courier/postex/track/:cn",
            )) {
                Text(e, color = T.faint, style = mono(11))
                Spacer(Modifier.height(5.dp))
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(7.dp).background(color))
}
