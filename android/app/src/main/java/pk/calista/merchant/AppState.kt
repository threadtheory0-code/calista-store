package pk.calista.merchant

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class Screen { Connect, Today, Orders, OrderDetail, ConfirmQueue, Products, Reports, Settings, Connections }

/** One store of truth for the UI. The server is the store of truth for the data. */
class AppState(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("calista", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var screen by mutableStateOf(Screen.Connect)
    var backTo by mutableStateOf(Screen.Orders)

    var storeUrl by mutableStateOf(prefs.getString("base", "") ?: "")
    var adminToken by mutableStateOf(prefs.getString("token", "") ?: "")

    var orders by mutableStateOf<List<Order>>(emptyList())
    var products by mutableStateOf<List<Product>>(emptyList())
    var stats by mutableStateOf<Stats?>(null)

    var loading by mutableStateOf(false)
    var toast by mutableStateOf("")
    var lastSync by mutableStateOf("never")

    var filter by mutableStateOf("all")
    var query by mutableStateOf("")
    var productQuery by mutableStateOf("")
    var range by mutableStateOf("7d")
    var openOrderId by mutableStateOf(0L)

    /** Live sync */
    var live by mutableStateOf(prefs.getBoolean("live", true))
    var syncing by mutableStateOf(false)
    var newBadge by mutableStateOf(0)
    private var cursor = ""
    private var pollJob: Job? = null
    private var tick = 0

    /** Bulk selection on the Orders screen */
    var selected by mutableStateOf<Set<Long>>(emptySet())

    init {
        Api.base = storeUrl
        Api.token = adminToken
        if (storeUrl.isNotBlank() && adminToken.isNotBlank()) {
            screen = Screen.Today
            refresh()
        }
        Notify.ensure(ctx)
        startPolling()
    }

    /** Turns a relative image path from the website into something Coil can load. */
    fun abs(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("http") -> url
        else -> storeUrl.trimEnd('/') + "/" + url.trimStart('/')
    }

    /** Best-effort photo for an order line item, matched against the product list. */
    fun imageFor(itemName: String): String {
        val n = itemName.trim().lowercase()
        if (n.isBlank()) return ""
        val hit = products.firstOrNull { it.name.trim().lowercase() == n }
            ?: products.firstOrNull {
                val pn = it.name.trim().lowercase()
                pn.isNotBlank() && (n.contains(pn) || pn.contains(n))
            }
        return abs(hit?.image ?: "")
    }

    fun toggleLive() {
        live = !live
        prefs.edit().putBoolean("live", live).apply()
        flash(if (live) "Live sync on · every 5 seconds" else "Live sync paused")
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(5000)
                if (!live || storeUrl.isBlank() || adminToken.isBlank() || loading) continue
                runCatching { pollOnce() }
            }
        }
    }

    private suspend fun pollOnce() {
        if (cursor.isBlank()) return
        syncing = true
        try {
            val (list, time) = Api.orders(since = cursor, limit = 60)
            if (time.isNotBlank()) {
                cursor = time
                lastSync = time.take(19).replace("T", " ")
            }
            if (list.isNotEmpty()) {
                val known = orders.map { it.id }.toSet()
                val fresh = list.filter { it.id !in known }
                val merged = ArrayList(orders)
                for (o in list) {
                    val i = merged.indexOfFirst { it.id == o.id }
                    if (i >= 0) merged[i] = o else merged.add(o)
                }
                orders = merged.sortedByDescending { it.id }
                if (fresh.isNotEmpty()) {
                    newBadge += fresh.size
                    val title = if (fresh.size == 1) "New order " + fresh[0].ref
                        else fresh.size.toString() + " new orders"
                    val text = fresh.joinToString("\n") {
                        it.name + " · " + it.city + " · " + rs(it.total)
                    }
                    Notify.newOrder(ctx, title, text)
                    flash(title)
                }
            }
            tick++
            if (tick % 12 == 0) loadRest()
        } finally {
            syncing = false
        }
    }

    val openOrder: Order? get() = orders.firstOrNull { it.id == openOrderId }

    val visibleOrders: List<Order>
        get() = orders.filter { o ->
            (filter == "all" || o.status.equals(filter, true)) &&
                (query.isBlank() ||
                    o.name.contains(query, true) ||
                    o.phone.contains(query) ||
                    o.id.toString().contains(query))
        }

    val pendingOrders: List<Order> get() = orders.filter { it.status.equals("pending", true) }

    fun counts(): Map<String, Int> {
        val m = HashMap<String, Int>()
        m["all"] = orders.size
        for (o in orders) m[o.status.lowercase()] = (m[o.status.lowercase()] ?: 0) + 1
        return m
    }

    fun go(s: Screen) {
        screen = s
        if (s == Screen.Orders || s == Screen.Today) newBadge = 0
    }

    fun openOrder(id: Long, from: Screen) {
        openOrderId = id
        backTo = from
        screen = Screen.OrderDetail
    }

    fun flash(msg: String) {
        toast = msg
        scope.launch { delay(3200); if (toast == msg) toast = "" }
    }

    fun connect(url: String, token: String) {
        val clean = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://" + it }
        storeUrl = clean
        adminToken = token.trim()
        Api.base = storeUrl
        Api.token = adminToken
        prefs.edit().putString("base", storeUrl).putString("token", adminToken).apply()
        scope.launch {
            loading = true
            try {
                val (list, time) = Api.orders()
                orders = list
                cursor = time
                lastSync = time.take(19).replace("T", " ")
                screen = Screen.Today
                flash("Connected · " + list.size + " orders")
                loadRest()
            } catch (e: Exception) {
                flash(e.message ?: "Cannot reach store")
            } finally {
                loading = false
            }
        }
    }

    fun signOut() {
        prefs.edit().clear().apply()
        storeUrl = ""; adminToken = ""; orders = emptyList(); products = emptyList(); stats = null
        screen = Screen.Connect
    }

    fun refresh() {
        scope.launch {
            loading = true
            try {
                val (list, time) = Api.orders()
                orders = list
                cursor = time
                lastSync = time.take(19).replace("T", " ")
                newBadge = 0
                loadRest()
            } catch (e: Exception) {
                flash(e.message ?: "Sync failed")
            } finally {
                loading = false
            }
        }
    }

    private suspend fun loadRest() {
        runCatching { stats = Api.stats(range) }
        runCatching { products = Api.products() }
    }

    fun pickRange(r: String) {
        range = r
        scope.launch { runCatching { stats = Api.stats(r) } }
    }

    fun advance(o: Order) {
        val next = when (o.status.lowercase()) {
            "pending" -> "confirmed"
            "confirmed" -> "packed"
            "shipped" -> "delivered"
            else -> return
        }
        setStatus(o, next)
    }

    fun setStatus(o: Order, status: String) {
        val before = orders
        orders = orders.map { if (it.id == o.id) it.copy(status = status) else it }
        scope.launch {
            try {
                Api.setStatus(o.id, status)
                flash(o.ref + " → " + status)
            } catch (e: Exception) {
                orders = before
                flash(e.message ?: "Could not save")
            }
        }
    }

    fun toggleSelect(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun clearSelection() { selected = emptySet() }

    fun selectAllVisible() { selected = visibleOrders.map { it.id }.toSet() }

    fun bulkStatus(status: String) {
        val ids = selected.toList()
        if (ids.isEmpty()) return
        orders = orders.map { if (it.id in selected) it.copy(status = status) else it }
        selected = emptySet()
        scope.launch {
            var ok = 0
            for (id in ids) runCatching { Api.setStatus(id, status) }.onSuccess { ok++ }
            flash(ok.toString() + " of " + ids.size + " orders → " + status)
        }
    }

    fun bookPostEx(o: Order) {
        scope.launch {
            loading = true
            try {
                val cn = Api.bookPostEx(o.id)
                orders = orders.map {
                    if (it.id == o.id) it.copy(status = "shipped", courier = "PostEx", tracking = cn) else it
                }
                flash("PostEx booked · CN " + cn)
            } catch (e: Exception) {
                flash(e.message ?: "PostEx booking failed")
            } finally {
                loading = false
            }
        }
    }

    fun bumpStock(p: Product, delta: Int) {
        val next = (p.stock + delta).coerceAtLeast(0)
        products = products.map { if (it.id == p.id) it.copy(stock = next) else it }
        scope.launch {
            runCatching { Api.setProduct(p.id, JSONObject().put("stock", next)) }
                .onFailure { flash("Stock not saved: " + (it.message ?: "")) }
        }
    }

    fun toggleLive(p: Product) {
        val next = !p.active
        products = products.map { if (it.id == p.id) it.copy(active = next) else it }
        scope.launch {
            runCatching { Api.setProduct(p.id, JSONObject().put("active", if (next) 1 else 0)) }
                .onFailure { flash("Visibility not saved") }
        }
    }

    val visibleProducts: List<Product>
        get() = products.filter { productQuery.isBlank() || it.name.contains(productQuery, true) }

    // ---- phone intents -----------------------------------------------------

    fun call(o: Order) {
        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + o.localPhone)))
    }

    fun copyAddress(o: Order) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(
            android.content.ClipData.newPlainText(
                "address",
                o.name + "\n" + o.localPhone + "\n" + o.address + ", " + o.city
            )
        )
        flash("Address copied for the courier slip")
    }

    fun whatsApp(o: Order, template: String) {
        val text = WaTemplates.fill(template, o)
        val uri = Uri.parse("https://wa.me/" + o.waDigits + "?text=" + Uri.encode(text))
        val i = Intent(Intent.ACTION_VIEW, uri)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
            .onFailure { flash("WhatsApp is not installed") }
        scope.launch { runCatching { Api.logWhatsApp(o.id, template) } }
        orders = orders.map { if (it.id == o.id) it.copy(waStatus = "sent") else it }
    }
}

object WaTemplates {
    val keys = listOf("confirm", "address", "dispatch", "cod")

    fun label(key: String) = when (key) {
        "confirm" -> "Confirm order"
        "address" -> "Ask address"
        "dispatch" -> "Dispatched"
        else -> "COD reminder"
    }

    fun fill(key: String, o: Order): String {
        val items = o.items.joinToString("\n") { "· " + it.name + " (size " + it.size + ") x " + it.qty }
        return when (key) {
            "confirm" ->
                "Assalam-o-alaikum " + o.firstName + "! This is Calista.\n\n" +
                    "We have received your order " + o.ref + ":\n" + items + "\n" +
                    "Total " + rs(o.total) + " (" + o.payment + ")\n\n" +
                    "Please reply YES to confirm and we will dispatch today. Reply NO to cancel."
            "address" ->
                o.firstName + ", about order " + o.ref + " — could you please share your complete " +
                    "address with a nearby landmark, so the courier reaches you without a call?"
            "dispatch" ->
                o.firstName + ", your Calista order " + o.ref + " has been dispatched.\n\n" +
                    "Courier: " + (if (o.courier.isBlank()) "PostEx" else o.courier) + "\n" +
                    "Tracking: " + (if (o.tracking.isBlank()) "pending" else o.tracking) + "\n\n" +
                    "Please keep " + rs(o.total) + " ready for the rider. Shukriya!"
            else ->
                o.firstName + ", your Calista parcel " + o.ref + " is out for delivery today.\n\n" +
                    "Amount to pay the rider: " + rs(o.total) + " (cash on delivery)."
        }
    }
}
