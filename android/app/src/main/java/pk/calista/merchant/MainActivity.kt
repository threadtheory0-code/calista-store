package pk.calista.merchant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = AppState(applicationContext)
        askForNotifications()
        setContent { CalistaTheme { App(state) } }
    }

    override fun onStart() {
        super.onStart()
        state.onForeground()
    }

    override fun onStop() {
        super.onStop()
        state.onBackground()
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }
}

/** Where the back arrow (and the phone's back gesture) goes from each screen. */
fun backOf(s: AppState): Screen? = when (s.screen) {
    Screen.OrderDetail -> s.backTo
    Screen.NewOrder -> if (s.backTo == Screen.NewOrder) Screen.Orders else s.backTo
    Screen.ConfirmQueue, Screen.PickList, Screen.CodLedger -> Screen.Today
    Screen.Connections, Screen.Blocked, Screen.Security, Screen.Reviews -> Screen.Settings
    Screen.ProductEdit -> Screen.Products
    Screen.CustomerDetail -> Screen.Customers
    Screen.Discounts, Screen.Banners, Screen.Settings -> Screen.Today
    Screen.DiscountEdit -> Screen.Discounts
    Screen.BannerEdit -> Screen.Banners
    Screen.Orders, Screen.Products, Screen.Customers, Screen.Reports -> Screen.Today
    else -> null
}

@Composable
fun App(s: AppState) {
    if (s.locked && s.pin.isNotBlank() && s.screen != Screen.Connect) {
        LockScreen(s)
        return
    }
    val back = backOf(s)
    BackHandler(enabled = back != null) { if (back != null) s.go(back) }

    Column(Modifier.fillMaxSize().background(T.screen)) {

        if (s.screen == Screen.Connect) {
            ConnectScreen(s)
            return@Column
        }

        TopBar(s, back)
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
                Screen.ProductEdit -> ProductEditScreen(s)
                Screen.Customers -> CustomersScreen(s)
                Screen.CustomerDetail -> CustomerDetailScreen(s)
                Screen.Discounts -> DiscountsScreen(s)
                Screen.DiscountEdit -> DiscountEditScreen(s)
                Screen.Banners -> BannersScreen(s)
                Screen.BannerEdit -> BannerEditScreen(s)
                Screen.Reports -> ReportsScreen(s)
                Screen.Settings -> SettingsScreen(s)
                Screen.Connections -> ConnectionsScreen(s)
                Screen.NewOrder -> NewOrderScreen(s)
                Screen.PickList -> PickListScreen(s)
                Screen.Reviews -> ReviewsScreen(s)
                Screen.Blocked -> BlockedScreen(s)
                Screen.Security -> SecurityScreen(s)
                Screen.CodLedger -> CodLedgerScreen(s)
                else -> Unit
            }
            val pc = s.pendingCard
            if (pc != null) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(T.surface).border(1.dp, T.gold).padding(14.dp),
                ) {
                    Kicker("Step 2 of 2", T.gold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Message sent to " + pc.first.firstName + "? Now send the photo card into the same chat.",
                        color = T.text, style = body(13),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LineButton("Skip", Modifier.weight(1f)) { s.dismissPendingCard() }
                        GoldButton("Send photos", Modifier.weight(2f)) { s.sendPendingCard() }
                    }
                }
            }
            if (s.toast.isNotBlank()) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(14.dp).fillMaxWidth()
                        .background(T.elev).border(1.dp, T.gold).padding(12.dp),
                ) {
                    Text(s.toast, color = T.text, style = body(13))
                }
            }
        }

        BottomRail(s)
    }
}

@Composable
private fun TopBar(s: AppState, back: Screen?) {
    val title = when (s.screen) {
        Screen.Today -> "Calista"
        Screen.Orders -> "Orders"
        Screen.OrderDetail -> "Order " + (s.openOrder?.ref ?: "")
        Screen.ConfirmQueue -> "Confirm queue"
        Screen.Products -> "Products"
        Screen.ProductEdit -> if (s.draftId == 0L) "New product" else "Edit product"
        Screen.Customers -> "Customers"
        Screen.CustomerDetail -> s.openCustomer?.name ?: "Customer"
        Screen.Discounts -> "Offers"
        Screen.DiscountEdit -> if (s.xId == 0L) "New offer" else "Edit offer"
        Screen.Banners -> "Storefront"
        Screen.BannerEdit -> if (s.bId == 0L) "New banner" else "Edit banner"
        Screen.Reports -> "Reports"
        Screen.Settings -> "Settings"
        Screen.Connections -> "Store connections"
        Screen.NewOrder -> "New order"
        Screen.PickList -> "Pick list"
        Screen.Reviews -> "Reviews"
        Screen.Blocked -> "Blocked numbers"
        Screen.Security -> "App lock & target"
        Screen.CodLedger -> "Cash to collect"
        else -> ""
    }
    val topLevel = s.screen in listOf(Screen.Today, Screen.Orders, Screen.Products, Screen.Customers, Screen.Reports)

    Column {
        Row(
            Modifier.fillMaxWidth().background(T.rail).padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (back != null && !topLevel) {
                IconSquare(Icons.Filled.ArrowBack, T.text) { s.go(back) }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                if (s.screen == Screen.Today) {
                    Image(
                        painterResource(R.drawable.logo_wordmark),
                        "Calista",
                        Modifier.height(24.dp).width(58.dp),
                    )
                } else {
                    Text(title, color = T.text, style = head(19, FontWeight.Bold), maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dot by animateFloatAsState(if (s.syncing || s.loading) 1f else 0.55f, label = "dot")
                    Box(
                        Modifier.size(6.dp).background(
                            when {
                                !s.live -> T.ghost
                                s.syncing || s.loading -> T.goldSoft
                                else -> T.gold.copy(alpha = dot)
                            },
                        ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            !s.live -> "paused"
                            s.loading || s.syncing -> "syncing…"
                            else -> "live · " + s.lastSync.takeLast(8)
                        },
                        color = T.ghost,
                        style = mono(10),
                    )
                }
            }
            IconSquare(
                if (s.live) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (s.live) T.gold else T.faint,
                if (s.live) T.gold else T.line,
            ) { s.toggleLive() }
            Spacer(Modifier.width(7.dp))
            IconSquare(Icons.Filled.Add, T.gold) { s.newOrder() }
            Spacer(Modifier.width(7.dp))
            IconSquare(Icons.Filled.Settings, T.muted) { s.go(Screen.Settings) }
        }
        RowDivider()
    }
}

@Composable
private fun IconSquare(
    icon: ImageVector,
    tint: Color,
    borderColor: Color = T.line,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(40.dp).border(1.dp, borderColor).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

private data class Tab(val label: String, val screen: Screen, val icon: ImageVector, val also: List<Screen>)

@Composable
private fun BottomRail(s: AppState) {
    val tabs = listOf(
        Tab("Today", Screen.Today, Icons.Outlined.Storefront, listOf(Screen.ConfirmQueue, Screen.PickList, Screen.CodLedger)),
        Tab("Orders", Screen.Orders, Icons.Outlined.ReceiptLong, listOf(Screen.OrderDetail, Screen.NewOrder)),
        Tab("Products", Screen.Products, Icons.Outlined.Checkroom, listOf(Screen.ProductEdit)),
        Tab("Customers", Screen.Customers, Icons.Outlined.PeopleAlt, listOf(Screen.CustomerDetail)),
        Tab("Reports", Screen.Reports, Icons.Outlined.BarChart, emptyList()),
    )
    Column {
        RowDivider()
        Row(
            Modifier.fillMaxWidth().background(T.rail).padding(top = 6.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (t in tabs) {
                val active = s.screen == t.screen || s.screen in t.also
                Column(
                    Modifier.weight(1f).clickable { s.go(t.screen) }.padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .width(46.dp).height(28.dp)
                                .background(if (active) T.goldTint else Color.Transparent)
                                .border(1.dp, if (active) T.gold else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(t.icon, t.label, tint = if (active) T.gold else T.faint, modifier = Modifier.size(19.dp))
                        }
                        val badge = when (t.screen) {
                            Screen.Orders -> s.newBadge
                            Screen.Today -> s.pendingOrders.size
                            else -> 0
                        }
                        if (badge > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(9.dp, (-5).dp)
                                    .background(if (t.screen == Screen.Orders) T.gold else T.warn)
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Text(if (badge > 99) "99+" else badge.toString(), color = T.bg, style = mono(9))
                            }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        t.label,
                        color = if (active) T.gold else T.faint,
                        style = kicker().copy(letterSpacing = 0.8.sp),
                    )
                }
            }
        }
    }
}

/** Four-digit PIN pad. Shown on cold start and after two minutes away. */
@Composable
fun LockScreen(s: AppState) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(T.bg).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.logo_wordmark), "Calista", Modifier.height(30.dp).width(72.dp))
        Spacer(Modifier.height(22.dp))
        Text(if (wrong) "Wrong PIN — try again" else "Enter your PIN", color = if (wrong) T.warn else T.muted, style = body(14))
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (i in 0 until 4) {
                Box(
                    Modifier.size(14.dp)
                        .background(if (i < entered.length) T.gold else Color.Transparent)
                        .border(1.dp, if (i < entered.length) T.gold else T.line),
                )
            }
        }
        Spacer(Modifier.height(30.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        for (row in keys.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (k in row) {
                    Box(
                        Modifier.size(width = 76.dp, height = 60.dp)
                            .border(1.dp, if (k.isBlank()) Color.Transparent else T.line)
                            .clickable(enabled = k.isNotBlank()) {
                                wrong = false
                                if (k == "⌫") entered = entered.dropLast(1)
                                else if (entered.length < 4) {
                                    entered += k
                                    if (entered.length == 4) {
                                        if (!s.unlock(entered)) { wrong = true; entered = "" }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(k, color = T.text, style = head(22)) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
