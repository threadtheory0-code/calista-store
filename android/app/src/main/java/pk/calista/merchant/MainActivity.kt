package pk.calista.merchant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

    /** Android 13+ needs the user's yes before the new-order chime can appear. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
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
                else -> Unit
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
private fun TopBar(s: AppState) {
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
        else -> ""
    }
    val back = when (s.screen) {
        Screen.OrderDetail -> s.backTo
        Screen.ConfirmQueue -> Screen.Today
        Screen.Connections -> Screen.Settings
        Screen.ProductEdit -> Screen.Products
        Screen.CustomerDetail -> Screen.Customers
        Screen.Discounts -> Screen.Today
        Screen.DiscountEdit -> Screen.Discounts
        Screen.Banners -> Screen.Today
        Screen.BannerEdit -> Screen.Banners
        Screen.Settings -> Screen.Today
        else -> null
    }

    Column {
        Row(
            Modifier.fillMaxWidth().background(T.rail).padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (back != null) {
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
                    Text(title, color = T.text, style = head(19, FontWeight.Bold))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dot by animateFloatAsState(
                        if (s.syncing || s.loading) 1f else 0.55f, label = "dot",
                    )
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
            IconSquare(Icons.Filled.Refresh, T.gold) { s.refresh() }
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
        Modifier.size(36.dp).border(1.dp, borderColor).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

private data class Tab(val label: String, val screen: Screen, val icon: ImageVector, val also: List<Screen>)

@Composable
private fun BottomRail(s: AppState) {
    val tabs = listOf(
        Tab("Today", Screen.Today, Icons.Outlined.Storefront, listOf(Screen.ConfirmQueue)),
        Tab("Orders", Screen.Orders, Icons.Outlined.ReceiptLong, listOf(Screen.OrderDetail)),
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
                            Icon(
                                t.icon, t.label,
                                tint = if (active) T.gold else T.faint,
                                modifier = Modifier.size(19.dp),
                            )
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
                                Text(
                                    if (badge > 99) "99+" else badge.toString(),
                                    color = T.bg, style = mono(9),
                                )
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
