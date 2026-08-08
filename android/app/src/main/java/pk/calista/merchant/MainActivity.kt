package pk.calista.merchant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = AppState(applicationContext)
        setContent { CalistaTheme { App(state) } }
    }
}

@Composable
fun App(s: AppState) {
    Column(Modifier.fillMaxSize().background(T.screen)) {

        if (s.screen == Screen.Connect) {
            ConnectScreen(s)
            return@Column
        }

        TopBar(s)
        if (s.loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = T.gold,
                trackColor = Color.Transparent,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (s.screen) {
                Screen.Today -> TodayScreen(s)
                Screen.Orders -> OrdersScreen(s)
                Screen.OrderDetail -> OrderDetailScreen(s)
                Screen.ConfirmQueue -> ConfirmQueueScreen(s)
                Screen.Products -> ProductsScreen(s)
                Screen.Reports -> ReportsScreen(s)
                Screen.Settings -> SettingsScreen(s)
                Screen.Connections -> ConnectionsScreen(s)
                else -> Unit
            }
            if (s.toast.isNotBlank()) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(14.dp).fillMaxWidth()
                        .background(T.elev).border(1.dp, T.gold).padding(12.dp)
                ) {
                    Text(s.toast, color = T.text, style = body(13))
                }
            }
        }

        BottomRail(s)
    }
}

@Composable
private fun TopBar(s: AppState) {
    val title = when (s.screen) {
        Screen.Today -> "Calista · Today"
        Screen.Orders -> "Orders"
        Screen.OrderDetail -> "Order " + (s.openOrder?.ref ?: "")
        Screen.ConfirmQueue -> "Confirm queue"
        Screen.Products -> "Products"
        Screen.Reports -> "Reports"
        Screen.Settings -> "Settings"
        Screen.Connections -> "Store connections"
        else -> ""
    }
    val showBack = s.screen == Screen.OrderDetail || s.screen == Screen.ConfirmQueue ||
        s.screen == Screen.Connections

    Column {
        Row(
            Modifier.fillMaxWidth().background(T.rail).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                Box(
                    Modifier.size(34.dp).border(1.dp, T.line)
                        .clickable {
                            s.go(if (s.screen == Screen.Connections) Screen.Settings else s.backTo)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.ArrowBack, "Back", tint = T.text, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = T.text, style = head(19, FontWeight.Bold))
                Text(
                    if (s.loading) "syncing…" else "synced " + s.lastSync,
                    color = T.ghost,
                    style = mono(10),
                )
            }
            Box(
                Modifier.size(34.dp).border(1.dp, T.line).clickable { s.refresh() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Refresh, "Refresh", tint = T.gold, modifier = Modifier.size(17.dp))
            }
        }
        RowDivider()
    }
}

@Composable
private fun BottomRail(s: AppState) {
    val tabs = listOf(
        Triple("Today", Screen.Today, "T"),
        Triple("Orders", Screen.Orders, "O"),
        Triple("Products", Screen.Products, "P"),
        Triple("Reports", Screen.Reports, "R"),
        Triple("More", Screen.Settings, "M"),
    )
    Column {
        RowDivider()
        Row(Modifier.fillMaxWidth().background(T.rail), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (t in tabs) {
                val active = s.screen == t.second ||
                    (t.second == Screen.Orders && s.screen == Screen.OrderDetail) ||
                    (t.second == Screen.Settings && s.screen == Screen.Connections)
                Column(
                    Modifier.weight(1f).clickable { s.go(t.second) }.padding(vertical = 11.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(22.dp)
                            .border(1.dp, if (active) T.gold else T.line),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(t.third, color = if (active) T.gold else T.faint, style = mono(11))
                    }
                    Spacer(Modifier.height(5.dp))
                    Kicker(t.first, if (active) T.gold else T.faint)
                }
            }
        }
    }
}
