package pk.calista.merchant

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Screen {
    Connect, Today, Orders, OrderDetail, ConfirmQueue, Products, ProductEdit,
    Customers, CustomerDetail, Reports, Settings, Connections,
    Discounts, DiscountEdit, Banners, BannerEdit,
    NewOrder, PickList, Reviews, Blocked, Security, CodLedger,
}

/** Built from the order list — the website has no customers table. */
data class Customer(
    val name: String,
    val phone: String,
    val city: String,
    val orders: List<Order>,
) {
    val count: Int get() = orders.size
    val spent: Int get() = orders.filter { it.st !in DEAD }.sumOf { it.total }
    val last: String get() = Dates.ago(orders.maxByOrNull { it.id }?.placedAt ?: 0L)
    val returns: Int get() = orders.count { it.st == "returned" }
    val bad: Int get() = orders.count { it.st in BAD }
    val delivered: Int get() = orders.count { it.st == "delivered" }
    val vip: Boolean get() = delivered >= 3 || spent >= 25_000
}

val DEAD = setOf("cancelled", "fake", "returned")
val BAD = setOf("cancelled", "fake", "returned")
val OPEN = setOf("pending", "confirmed", "packed", "shipped")

/** How likely a COD order is to come back. Reasons are shown to the merchant word for word. */
data class Risk(val score: Int, val reasons: List<String>) {
    val level: String get() = when { score >= 50 -> "high"; score >= 25 -> "medium"; else -> "low" }
}

data class DraftLine(
    val productId: Long,
    val name: String,
    val size: String,
    val qty: Int,
    val price: Int,
    val image: String,
)

data class PickLine(val name: String, val size: String, val qty: Int, val orderIds: List<Long>)

/** One store of truth for the UI. The server is the store of truth for the data. */
class AppState(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("calista", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cacheFile = File(ctx.filesDir, "store-cache.json")

    var screen by mutableStateOf(Screen.Connect)
    var backTo by mutableStateOf(Screen.Orders)

    var storeUrl by mutableStateOf(prefs.getString("base", "") ?: "")
    var adminToken by mutableStateOf(prefs.getString("token", "") ?: "")

    var orders by mutableStateOf<List<Order>>(emptyList())
    var products by mutableStateOf<List<Product>>(emptyList())
    var stats by mutableStateOf<Stats?>(null)

    var loading by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var toast by mutableStateOf("")
    var lastSync by mutableStateOf("never")

    var filter by mutableStateOf("all")
    var dateFilter by mutableStateOf("any")
    var query by mutableStateOf("")
    var productQuery by mutableStateOf("")
    var productFilter by mutableStateOf("all")
    /** "" = every fabric; otherwise the fabric name, matched case-insensitively. */
    var productFabric by mutableStateOf("")
    var range by mutableStateOf("7d")
    var openOrderId by mutableStateOf(0L)

    /** Live sync: 5 s while on screen, 30 s in the background. */
    var live by mutableStateOf(prefs.getBoolean("live", true))
    var syncing by mutableStateOf(false)
    var newBadge by mutableStateOf(0)
    var foreground by mutableStateOf(true)
    private var cursor = ""
    private var pollJob: Job? = null
    private var tick = 0

    /** App lock */
    var pin by mutableStateOf(prefs.getString("pin", "") ?: "")
    var locked by mutableStateOf((prefs.getString("pin", "") ?: "").isNotBlank())
    private var stoppedAt = 0L

    /** Daily sales target shown on Today. 0 = off. */
    var goal by mutableStateOf(prefs.getInt("goal", 0))

    var selected by mutableStateOf<Set<Long>>(emptySet())

    var customerQuery by mutableStateOf("")
    var customerFilter by mutableStateOf("all")
    var openPhone by mutableStateOf("")

    /** Product editor draft */
    var draftId by mutableStateOf(0L)
    var dName by mutableStateOf("")
    var dFabric by mutableStateOf("")
    var dPrice by mutableStateOf("")
    var dSale by mutableStateOf("")
    var dStock by mutableStateOf("")
    var dImage by mutableStateOf("")
    var dActive by mutableStateOf(true)
    var saving by mutableStateOf(false)

    private var lowStockWarned = mutableSetOf<Long>()
    private var summaryDay = ""

    var discounts by mutableStateOf<List<Discount>>(emptyList())
    var banners by mutableStateOf<List<Banner>>(emptyList())
    var fabrics by mutableStateOf<List<String>>(emptyList())
    var blocked by mutableStateOf<List<Blocked>>(emptyList())
    var reviews by mutableStateOf<List<Review>>(emptyList())
    var uploading by mutableStateOf(false)

    /** Latest PostEx status per order id, fetched on demand. */
    var courierStatus by mutableStateOf<Map<Long, String>>(emptyMap())

    /** Discount draft */
    var xId by mutableStateOf(0L)
    var xTitle by mutableStateOf("")
    var xCode by mutableStateOf("")
    var xType by mutableStateOf("percentage_off_order")
    var xValue by mutableStateOf("")
    var xMinCart by mutableStateOf("")
    var xLimit by mutableStateOf("")
    var xBuy by mutableStateOf("")
    var xGet by mutableStateOf("")
    var xGetPercent by mutableStateOf("100")
    var xStart by mutableStateOf("")
    var xEnd by mutableStateOf("")
    var xActive by mutableStateOf(true)

    /** Banner draft */
    var bId by mutableStateOf(0L)
    var bEyebrow by mutableStateOf("")
    var bHeading by mutableStateOf("")
    var bSub by mutableStateOf("")
    var bBtnText by mutableStateOf("")
    var bBtnLink by mutableStateOf("")
    var bImage by mutableStateOf("")
    var bActive by mutableStateOf(true)

    /** Manual order draft */
    var nName by mutableStateOf("")
    var nPhone by mutableStateOf("")
    var nAddress by mutableStateOf("")
    var nCity by mutableStateOf("")
    var nLines by mutableStateOf<List<DraftLine>>(emptyList())
    var nDiscount by mutableStateOf("")
    var nNote by mutableStateOf("")
    var nSource by mutableStateOf("whatsapp")
    var nStatus by mutableStateOf("confirmed")

    init {
        Api.base = storeUrl
        Api.token = adminToken
        Notify.ensure(ctx)
        if (storeUrl.isNotBlank() && adminToken.isNotBlank()) {
            screen = Screen.Today
            scope.launch {
                loadCache()
                refresh()
            }
        }
        startPolling()
    }

    // ---- cache: the last store snapshot, shown instantly on launch ---------

    private suspend fun loadCache() {
        val boot = withContext(Dispatchers.IO) {
            runCatching {
                if (cacheFile.exists()) Api.parseBoot(JSONObject(cacheFile.readText())) else null
            }.getOrNull()
        } ?: return
        applyBoot(boot)
        lastSync = "cached"
    }

    private fun saveCache(text: String) {
        scope.launch(Dispatchers.IO) { runCatching { cacheFile.writeText(text) } }
    }

    private fun applyBoot(b: Boot) {
        orders = b.orders
        products = b.products
        if (b.stats != null) stats = b.stats
        discounts = b.discounts
        banners = b.banners
        if (b.fabrics.isNotEmpty()) fabrics = b.fabrics
        blocked = b.blocked
        if (b.serverTime.isNotBlank()) {
            cursor = b.serverTime
            lastSync = b.serverTime.take(19)
        }
    }

    fun abs(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("http") -> url
        else -> storeUrl.trimEnd('/') + "/" + url.trimStart('/')
    }

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

    // ---- lifecycle + polling ----------------------------------------------

    fun onForeground() {
        foreground = true
        if (pin.isNotBlank() && stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 120_000L) {
            locked = true
        }
        if (live && cursor.isNotBlank()) scope.launch { runCatching { pollOnce() } }
    }

    fun onBackground() {
        foreground = false
        stoppedAt = System.currentTimeMillis()
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
                delay(if (foreground) 5_000L else 30_000L)
                if (!live || storeUrl.isBlank() || adminToken.isBlank() || loading) continue
                runCatching { pollOnce() }
            }
        }
    }

    private suspend fun pollOnce() {
        if (cursor.isBlank() || syncing) return
        syncing = true
        try {
            val (list, time) = Api.orders(since = cursor, limit = 80)
            if (time.isNotBlank()) {
                cursor = time
                lastSync = time.take(19)
            }
            if (list.isNotEmpty()) merge(list)
            tick++
            // Products, stock, stats and offers: one bootstrap call about once a minute.
            if (tick % 12 == 0) runCatching { boot(quiet = true) }
        } finally {
            syncing = false
        }
    }

    private fun merge(list: List<Order>) {
        val known = orders.map { it.id }.toHashSet()
        val fresh = list.filter { it.id !in known }
        val byId = LinkedHashMap<Long, Order>()
        for (o in orders) byId[o.id] = o
        for (o in list) byId[o.id] = o
        orders = byId.values.sortedByDescending { it.id }
        if (fresh.isNotEmpty()) {
            newBadge += fresh.size
            val title = if (fresh.size == 1) "New order " + fresh[0].ref else fresh.size.toString() + " new orders"
            val text = fresh.joinToString("\n") {
                val r = risk(it)
                it.name + " · " + it.city + " · " + rs(it.total) + (if (r.level == "high") " · high risk" else "")
            }
            Notify.newOrder(ctx, title, text)
            flash(title)
        }
    }

    private suspend fun boot(quiet: Boolean) {
        try {
            val (b, raw) = Api.bootstrap(range)
            applyBoot(b)
            saveCache(raw)
            checkAlerts()
        } catch (e: ApiError) {
            // Worker not yet on 1.6: fall back to the separate calls.
            if (!quiet) legacyLoad()
        }
    }

    private suspend fun legacyLoad() {
        val (list, time) = Api.orders(limit = 300)
        orders = list
        cursor = time
        lastSync = time.take(19)
        runCatching { stats = Api.stats(range) }
        runCatching { products = Api.products() }
        runCatching { fabrics = Api.fabrics() }
        runCatching { discounts = Api.discounts() }
        runCatching { banners = Api.banners() }
    }

    fun refresh() {
        scope.launch {
            loading = true
            try {
                boot(quiet = false)
                newBadge = 0
            } catch (e: Exception) {
                flash(e.message ?: "Sync failed")
            } finally {
                loading = false
            }
        }
    }

    /** Pull-to-refresh: same as refresh but drives the pull indicator. */
    fun pullRefresh() {
        scope.launch {
            refreshing = true
            try { boot(quiet = false) } catch (e: Exception) { flash(e.message ?: "Sync failed") }
            finally { refreshing = false }
        }
    }

    // ---- derived lists (cached so scrolling never re-groups) ---------------

    val openOrder: Order? get() = orders.firstOrNull { it.id == openOrderId }

    val visibleOrders: List<Order>
        get() {
            val q = query.trim()
            val from = when (dateFilter) {
                "today" -> Dates.startOfToday()
                "yesterday" -> Dates.startOfToday() - 86_400_000L
                "7d" -> Dates.startOfToday() - 6 * 86_400_000L
                else -> 0L
            }
            val to = if (dateFilter == "yesterday") Dates.startOfToday() else Long.MAX_VALUE
            return orders.filter { o ->
                (filter == "all" || o.st == filter ||
                    (filter == "risky" && risk(o).level == "high" && o.st in OPEN) ||
                    (filter == "unpaid" && o.st == "delivered" && !o.codPaid)) &&
                    (from == 0L || (o.placedAt in from until to)) &&
                    (q.isBlank() ||
                        o.name.contains(q, true) ||
                        o.phone.contains(q) ||
                        o.localPhone.contains(q) ||
                        o.city.contains(q, true) ||
                        o.tracking.contains(q, true) ||
                        o.id.toString() == q.removePrefix("#"))
            }
        }

    val pendingOrders: List<Order> get() = orders.filter { it.st == "pending" }

    val todayOrders: List<Order>
        get() {
            val from = Dates.startOfToday()
            return orders.filter { it.placedAt >= from }
        }

    val stalePending: List<Order> get() = orders.filter { it.st == "pending" && it.ageHours >= 24 }
    val stuckParcels: List<Order> get() = orders.filter { it.st == "shipped" && it.ageHours >= 120 }
    val codOutstanding: List<Order> get() = orders.filter { it.st == "delivered" && !it.codPaid }

    fun counts(): Map<String, Int> {
        val m = HashMap<String, Int>()
        m["all"] = orders.size
        for (o in orders) m[o.st] = (m[o.st] ?: 0) + 1
        m["risky"] = orders.count { it.st in OPEN && risk(it).level == "high" }
        m["unpaid"] = codOutstanding.size
        return m
    }

    private var custKey: List<Order>? = null
    private var custCache: List<Customer> = emptyList()
    private var phoneIndex: Map<String, List<Order>> = emptyMap()

    private fun ensureCustomers() {
        val o = orders
        if (custKey === o) return
        val groups = o.groupBy { it.localPhone.ifBlank { it.name } }
        phoneIndex = groups
        custCache = groups.map { (phone, list) ->
            val sorted = list.sortedByDescending { it.id }
            Customer(sorted.first().name, phone, sorted.first().city, sorted)
        }.sortedByDescending { it.orders.firstOrNull()?.id ?: 0L }
        custKey = o
    }

    val customers: List<Customer> get() { ensureCustomers(); return custCache }

    val visibleCustomers: List<Customer>
        get() = customers.filter {
            (customerFilter == "all" ||
                (customerFilter == "repeat" && it.count > 1) ||
                (customerFilter == "vip" && it.vip) ||
                (customerFilter == "risky" && it.bad > 0)) &&
                (customerQuery.isBlank() ||
                    it.name.contains(customerQuery, true) ||
                    it.phone.contains(customerQuery) ||
                    it.city.contains(customerQuery, true))
        }

    val openCustomer: Customer? get() = customers.firstOrNull { it.phone == openPhone }

    fun openCustomer(c: Customer) {
        openPhone = c.phone
        screen = Screen.CustomerDetail
    }

    fun customerOf(o: Order): Customer? {
        ensureCustomers()
        return custCache.firstOrNull { it.phone == o.localPhone.ifBlank { o.name } }
    }

    fun isBlocked(phone: String): Boolean {
        val p = phone.filter { it.isDigit() }.let { if (it.startsWith("92")) "0" + it.substring(2) else it }
        return blocked.any { it.phone == p }
    }

    fun risk(o: Order): Risk {
        ensureCustomers()
        val reasons = ArrayList<String>()
        var s = 0
        if (isBlocked(o.phone)) { s += 100; reasons.add("This number is on your blocklist") }
        val past = (phoneIndex[o.localPhone.ifBlank { o.name }] ?: emptyList()).filter { it.id != o.id }
        val bad = past.count { it.st in BAD }
        val good = past.count { it.st == "delivered" }
        if (bad > 0) {
            s += (bad * 25).coerceAtMost(75)
            reasons.add(bad.toString() + " earlier order" + (if (bad > 1) "s" else "") + " returned, cancelled or fake")
        } else if (o.riskNote.isNotBlank()) {
            s += 20; reasons.add(o.riskNote)
        }
        if (good > 0) {
            s -= (good * 20).coerceAtMost(40)
            reasons.add(good.toString() + " delivered before — a known buyer")
        }
        if (past.any { it.st in OPEN && kotlin.math.abs(it.placedAt - o.placedAt) < 86_400_000L }) {
            s += 20; reasons.add("Another open order from this number within 24 hours")
        }
        if (o.address.trim().length < 20) { s += 15; reasons.add("Short address — ask for a landmark") }
        if (past.isEmpty() && o.total >= 10_000) { s += 15; reasons.add("First order, above Rs 10,000") }
        if (o.waStatus == "declined") { s += 40; reasons.add("Said NO on WhatsApp") }
        if (o.waStatus == "confirmed") { s -= 15; reasons.add("Confirmed on WhatsApp") }
        return Risk(s.coerceIn(0, 100), reasons)
    }

    /** What to take off the shelf for every confirmed order. */
    fun pickList(includePending: Boolean): List<PickLine> {
        val src = orders.filter { it.st == "confirmed" || (includePending && it.st == "pending") }
        val map = LinkedHashMap<String, PickLine>()
        for (o in src) for (li in o.items) {
            val k = li.name.trim().lowercase() + "|" + li.size
            val cur = map[k]
            map[k] = if (cur == null) PickLine(li.name, li.size, li.qty, listOf(o.id))
            else cur.copy(qty = cur.qty + li.qty, orderIds = cur.orderIds + o.id)
        }
        return map.values.sortedByDescending { it.qty }
    }

    fun sharePickList(lines: List<PickLine>) {
        val text = "Calista pick list · " + Dates.stamp(System.currentTimeMillis()) + "\n\n" +
            lines.joinToString("\n") { "☐ " + it.qty + " × " + it.name + (if (it.size != "-") " (" + it.size + ")" else "") } +
            "\n\n" + lines.flatMap { it.orderIds }.distinct().size + " orders"
        shareText(text, "Share pick list")
    }

    // ---- navigation --------------------------------------------------------

    fun go(s: Screen) {
        screen = s
        if (s == Screen.Orders || s == Screen.Today) newBadge = 0
        if (s == Screen.Discounts && discounts.isEmpty()) loadDiscounts()
        if (s == Screen.Banners && banners.isEmpty()) loadBanners()
        if (s == Screen.Reviews) loadReviews()
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

    // ---- connect / lock / prefs -------------------------------------------

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
                boot(quiet = false)
                screen = Screen.Today
                flash("Connected · " + orders.size + " orders")
            } catch (e: Exception) {
                flash(e.message ?: "Cannot reach store")
            } finally {
                loading = false
            }
        }
    }

    fun signOut() {
        prefs.edit().remove("base").remove("token").apply()
        runCatching { cacheFile.delete() }
        storeUrl = ""; adminToken = ""; orders = emptyList(); products = emptyList(); stats = null
        cursor = ""
        screen = Screen.Connect
    }

    fun changePin(p: String) {
        val clean = p.filter { it.isDigit() }
        if (clean.isNotEmpty() && clean.length != 4) { flash("PIN must be 4 digits"); return }
        pin = clean
        prefs.edit().putString("pin", clean).apply()
        flash(if (clean.isBlank()) "App lock off" else "App lock on")
    }

    fun unlock(p: String): Boolean {
        if (p == pin) { locked = false; return true }
        return false
    }

    fun setGoal(v: String) {
        goal = v.filter { it.isDigit() }.toIntOrNull() ?: 0
        prefs.edit().putInt("goal", goal).apply()
        flash(if (goal == 0) "Daily target off" else "Daily target " + rs(goal))
    }

    fun pickRange(r: String) {
        range = r
        scope.launch { runCatching { stats = Api.stats(r) } }
    }

    // ---- order actions -----------------------------------------------------

    fun advance(o: Order) {
        val next = when (o.st) {
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

    private fun patchLocal(o: Order, body: JSONObject, local: (Order) -> Order, ok: String) {
        val before = orders
        orders = orders.map { if (it.id == o.id) local(it) else it }
        scope.launch {
            try { Api.patchOrder(o.id, body); flash(ok) }
            catch (e: Exception) { orders = before; flash(e.message ?: "Could not save") }
        }
    }

    fun saveNote(o: Order, note: String) =
        patchLocal(o, JSONObject().put("admin_note", note.trim()), { it.copy(note = note.trim()) }, "Note saved")

    fun saveContact(o: Order, name: String, phone: String, address: String, city: String) {
        val body = JSONObject().put("name", name).put("phone", phone).put("address", address).put("city", city)
        patchLocal(o, body, { it.copy(name = name, phone = phone, address = address, city = city) }, "Delivery details saved")
    }

    fun setCodPaid(o: Order, paid: Boolean) =
        patchLocal(
            o, JSONObject().put("cod_paid", paid),
            { it.copy(codPaidAt = if (paid) "now" else "") },
            if (paid) o.ref + " · cash received" else o.ref + " · marked unpaid",
        )

    fun toggleSelect(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun clearSelection() { selected = emptySet() }

    fun selectAllVisible() { selected = visibleOrders.map { it.id }.toSet() }

    fun bulkStatus(status: String) = bulk(selected.toList(), JSONObject().put("status", status),
        { it.copy(status = status) }, "→ " + status)

    fun bulkCodPaid(ids: List<Long>) = bulk(ids, JSONObject().put("cod_paid", true),
        { it.copy(codPaidAt = "now") }, "cash received")

    private fun bulk(ids: List<Long>, body: JSONObject, local: (Order) -> Order, label: String) {
        if (ids.isEmpty()) return
        val set = ids.toHashSet()
        val before = orders
        orders = orders.map { if (it.id in set) local(it) else it }
        selected = emptySet()
        scope.launch {
            try {
                Api.bulkOrders(ids, body)
                flash(ids.size.toString() + " orders " + label)
            } catch (e: ApiError) {
                // Worker before 1.6 — one request per order.
                var ok = 0
                for (id in ids) runCatching { Api.patchOrder(id, JSONObject(body.toString())) }.onSuccess { ok++ }
                if (ok == 0) orders = before
                flash(ok.toString() + " of " + ids.size + " orders " + label)
            }
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

    fun track(o: Order) {
        if (o.tracking.isBlank()) return
        scope.launch {
            runCatching { Api.trackPostEx(o.tracking) }
                .onSuccess { st ->
                    courierStatus = courierStatus + (o.id to st.ifBlank { "No update from courier yet" })
                    if (st.isNotBlank()) orders = orders.map { if (it.id == o.id) it.copy(postexStatus = st) else it }
                }
                .onFailure { flash(it.message ?: "Tracking failed") }
        }
    }

    // ---- manual orders -----------------------------------------------------

    fun newOrder(from: Customer? = null, copy: Order? = null) {
        val src = copy ?: from?.orders?.firstOrNull()
        nName = src?.name ?: ""
        nPhone = src?.localPhone ?: ""
        nAddress = src?.address ?: ""
        nCity = src?.city ?: ""
        nLines = copy?.items?.map {
            DraftLine(it.productId, it.name, if (it.size == "-") "" else it.size, it.qty, it.price, imageFor(it.name))
        } ?: emptyList()
        nDiscount = ""; nNote = ""; nSource = "whatsapp"; nStatus = "confirmed"
        backTo = screen
        screen = Screen.NewOrder
    }

    fun addLine(p: Product) {
        val i = nLines.indexOfFirst { it.productId == p.id && it.size.isBlank() }
        nLines = if (i >= 0) nLines.mapIndexed { j, l -> if (j == i) l.copy(qty = l.qty + 1) else l }
        else nLines + DraftLine(p.id, p.name, "", 1, p.unitPrice, p.image)
    }

    fun setLine(i: Int, line: DraftLine) {
        nLines = if (line.qty <= 0) nLines.filterIndexed { j, _ -> j != i }
        else nLines.mapIndexed { j, l -> if (j == i) line else l }
    }

    val nSubtotal: Int get() = nLines.sumOf { it.price * it.qty }
    val nTotal: Int get() = (nSubtotal - (nDiscount.filter { it.isDigit() }.toIntOrNull() ?: 0)).coerceAtLeast(0)

    fun saveNewOrder() {
        if (nName.isBlank() || nPhone.isBlank() || nAddress.isBlank() || nCity.isBlank()) {
            flash("Name, phone, address and city are needed"); return
        }
        if (nLines.isEmpty()) { flash("Add at least one item"); return }
        val items = JSONArray()
        for (l in nLines) {
            items.put(
                JSONObject()
                    .put("product_id", (if (l.productId > 0) l.productId else JSONObject.NULL) as Any)
                    .put("name", l.name)
                    .put("size", (if (l.size.isBlank()) JSONObject.NULL else l.size) as Any)
                    .put("qty", l.qty).put("price", l.price)
            )
        }
        val body = JSONObject()
            .put("name", nName.trim()).put("phone", nPhone.trim())
            .put("address", nAddress.trim()).put("city", nCity.trim())
            .put("items", items)
            .put("discount_amount", nDiscount.filter { it.isDigit() }.toIntOrNull() ?: 0)
            .put("note", nNote.trim()).put("source", nSource).put("status", nStatus)
        scope.launch {
            saving = true
            try {
                val id = Api.createOrder(body)
                runCatching { boot(quiet = true) }
                flash("Order #" + id + " created")
                openOrder(id, Screen.Orders)
            } catch (e: Exception) {
                flash(e.message ?: "Could not create the order")
            } finally { saving = false }
        }
    }

    // ---- blocklist ---------------------------------------------------------

    fun block(phone: String, reason: String) {
        scope.launch {
            runCatching { Api.block(phone, reason) }
                .onSuccess {
                    val p = it.optString("phone", phone)
                    blocked = listOf(Blocked(p, reason, "now")) + blocked.filter { b -> b.phone != p }
                    flash(p + " blocked — the website will refuse it")
                }
                .onFailure { flash(it.message ?: "Could not block") }
        }
    }

    fun unblock(phone: String) {
        val before = blocked
        blocked = blocked.filter { it.phone != phone }
        scope.launch {
            runCatching { Api.unblock(phone) }
                .onSuccess { flash(phone + " unblocked") }
                .onFailure { blocked = before; flash("Could not unblock") }
        }
    }

    // ---- reviews -----------------------------------------------------------

    fun loadReviews() {
        scope.launch {
            runCatching { Api.reviews() }
                .onSuccess { reviews = it }
                .onFailure { flash(it.message ?: "Could not load reviews") }
        }
    }

    fun approveReview(r: Review, on: Boolean) {
        reviews = reviews.map { if (it.id == r.id) it.copy(approved = on) else it }
        scope.launch {
            runCatching { Api.setReview(r.id, on) }
                .onSuccess { flash(if (on) "Review is live on the website" else "Review hidden") }
                .onFailure { flash("Could not save") }
        }
    }

    fun deleteReview(r: Review) {
        reviews = reviews.filter { it.id != r.id }
        scope.launch { runCatching { Api.deleteReview(r.id) }.onFailure { flash("Could not delete") } }
    }

    // ---- products ----------------------------------------------------------

    fun bumpStock(p: Product, delta: Int) = setStock(p, p.stock + delta)

    fun setStock(p: Product, value: Int) {
        val next = value.coerceAtLeast(0)
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
        get() = products.filter {
            (productFilter == "all" ||
                (productFilter == "low" && it.stock in 1..3) ||
                (productFilter == "out" && it.stock <= 0) ||
                (productFilter == "hidden" && !it.active) ||
                (productFilter == "sale" && it.sale != null && it.sale > 0)) &&
                (productFabric.isBlank() || it.fabric.trim().equals(productFabric, true)) &&
                (productQuery.isBlank() || it.name.contains(productQuery, true) || it.fabric.contains(productQuery, true))
        }

    /** Fabric chips: the store's category order first, then any other fabric in use, with counts. */
    val fabricCounts: List<Pair<String, Int>>
        get() {
            val counts = LinkedHashMap<String, Int>()
            val names = HashMap<String, String>()
            for (p in products) {
                val f = p.fabric.trim()
                if (f.isBlank()) continue
                val k = f.lowercase()
                counts[k] = (counts[k] ?: 0) + 1
                if (k !in names) names[k] = f
            }
            val ordered = ArrayList<Pair<String, Int>>()
            for (f in fabrics) {
                val k = f.trim().lowercase()
                val n = counts.remove(k) ?: continue
                ordered.add(f.trim() to n)
            }
            for ((k, n) in counts.entries.sortedByDescending { it.value }) ordered.add((names[k] ?: k) to n)
            return ordered
        }

    /** Units sold per product name, from the loaded orders. */
    fun unitsSold(p: Product): Int {
        val n = p.name.trim().lowercase()
        return orders.filter { it.st !in DEAD }.sumOf { o -> o.items.filter { it.name.trim().lowercase() == n }.sumOf { it.qty } }
    }

    fun productLink(p: Product): String =
        if (p.slug.isBlank()) storeUrl else storeUrl.trimEnd('/') + "/product.html?slug=" + Uri.encode(p.slug)

    fun shareProduct(p: Product) {
        val price = if (p.sale != null && p.sale > 0) rs(p.sale) + " (was " + rs(p.price) + ")" else rs(p.price)
        shareText(p.name + "\n" + price + " · cash on delivery\n\n" + productLink(p), "Share product")
    }

    fun newProduct() {
        draftId = 0; dName = ""; dFabric = ""; dPrice = ""; dSale = ""; dStock = "0"
        dImage = ""; dActive = true
        screen = Screen.ProductEdit
    }

    fun editProduct(p: Product) {
        draftId = p.id
        dName = p.name
        dFabric = p.fabric
        dPrice = p.price.toString()
        dSale = p.sale?.toString() ?: ""
        dStock = p.stock.toString()
        dImage = p.image
        dActive = p.active
        screen = Screen.ProductEdit
    }

    /** Keeps every field, clears the id — saving creates a new product. */
    fun duplicateDraft() {
        draftId = 0
        dName = dName.trim() + " (copy)"
        dStock = "0"
        flash("Copy ready — change what differs and save")
    }

    fun saveDraft() {
        if (dName.isBlank()) { flash("Give the product a name"); return }
        val body = JSONObject()
            .put("name", dName.trim())
            .put("fabric", dFabric.trim())
            .put("price", dPrice.filter { it.isDigit() }.toIntOrNull() ?: 0)
            .put("sale_price", (dSale.filter { it.isDigit() }.toIntOrNull() ?: JSONObject.NULL) as Any)
            .put("stock", dStock.filter { it.isDigit() }.toIntOrNull() ?: 0)
            .put("image", dImage.trim())
            .put("active", if (dActive) 1 else 0)
        scope.launch {
            saving = true
            try {
                if (draftId == 0L) {
                    Api.createProduct(body)
                    flash("Product added — live on the website")
                } else {
                    Api.setProduct(draftId, body)
                    flash("Product saved")
                }
                products = Api.products()
                screen = Screen.Products
            } catch (e: Exception) {
                flash(e.message ?: "Could not save")
            } finally {
                saving = false
            }
        }
    }

    fun deleteDraft() {
        val id = draftId
        if (id == 0L) { screen = Screen.Products; return }
        scope.launch {
            runCatching { Api.deleteProduct(id) }
                .onSuccess {
                    products = products.filter { it.id != id }
                    flash("Product deleted")
                    screen = Screen.Products
                }
                .onFailure { flash("Could not delete") }
        }
    }

    // ---- photo upload ------------------------------------------------------

    fun uploadPhoto(uri: Uri, into: (String) -> Unit) {
        scope.launch {
            uploading = true
            try {
                val file = withContext(Dispatchers.IO) { Photo.prepare(ctx, uri) }
                if (file == null) { flash("Could not read that picture"); return@launch }
                val url = Api.upload(file)
                into(url)
                flash("Photo uploaded")
                runCatching { file.delete() }
            } catch (e: Exception) {
                flash(e.message ?: "Upload failed")
            } finally {
                uploading = false
            }
        }
    }

    fun uploadCameraFile(file: File, into: (String) -> Unit) = uploadPhoto(Uri.fromFile(file), into)

    // ---- discounts ---------------------------------------------------------

    fun loadDiscounts() {
        scope.launch {
            runCatching { Api.discounts() }
                .onSuccess { discounts = it }
                .onFailure { flash(it.message ?: "Could not load discounts") }
        }
    }

    fun newDiscount() {
        xId = 0; xTitle = ""; xCode = ""; xType = "percentage_off_order"; xValue = ""
        xMinCart = ""; xLimit = ""; xBuy = "1"; xGet = "1"; xGetPercent = "100"
        xStart = ""; xEnd = ""; xActive = true
        screen = Screen.DiscountEdit
    }

    fun editDiscount(d: Discount) {
        xId = d.id; xTitle = d.title; xCode = d.code; xType = d.type
        xValue = d.value?.toString() ?: ""
        xMinCart = d.minCart?.toString() ?: ""
        xLimit = d.usageLimit?.toString() ?: ""
        xBuy = d.buyQty?.toString() ?: "1"
        xGet = d.getQty?.toString() ?: "1"
        xGetPercent = d.getPercent.toString()
        xStart = d.startDate; xEnd = d.endDate; xActive = d.active
        screen = Screen.DiscountEdit
    }

    fun saveDiscount() {
        if (xTitle.isBlank()) { flash("Give the offer a name"); return }
        val num = { t: String -> t.filter { it.isDigit() }.toIntOrNull() }
        val txt = { t: String -> if (t.isBlank()) JSONObject.NULL as Any else t.trim() as Any }
        val body = JSONObject()
            .put("title", xTitle.trim())
            .put("code", txt(xCode.uppercase()))
            .put("type", xType)
            .put("value", (num(xValue) ?: JSONObject.NULL) as Any)
            .put("min_cart_value", (num(xMinCart) ?: JSONObject.NULL) as Any)
            .put("usage_limit", (num(xLimit) ?: JSONObject.NULL) as Any)
            .put("buy_quantity", (num(xBuy) ?: JSONObject.NULL) as Any)
            .put("get_quantity", (num(xGet) ?: JSONObject.NULL) as Any)
            .put("get_discount_percent", num(xGetPercent) ?: 100)
            .put("start_date", txt(xStart))
            .put("end_date", txt(xEnd))
            .put("is_active", if (xActive) 1 else 0)
        scope.launch {
            saving = true
            try {
                if (xId == 0L) Api.createDiscount(body) else Api.setDiscount(xId, body)
                discounts = Api.discounts()
                flash(if (xId == 0L) "Offer created — live now" else "Offer saved")
                screen = Screen.Discounts
            } catch (e: Exception) {
                flash(e.message ?: "Could not save")
            } finally { saving = false }
        }
    }

    fun toggleDiscount(d: Discount) {
        discounts = discounts.map { if (it.id == d.id) it.copy(active = !it.active) else it }
        scope.launch {
            runCatching { Api.setDiscount(d.id, JSONObject().put("is_active", if (d.active) 0 else 1)) }
                .onFailure { flash("Could not change that") }
        }
    }

    fun deleteDiscount() {
        val id = xId
        if (id == 0L) { screen = Screen.Discounts; return }
        scope.launch {
            runCatching { Api.deleteDiscount(id) }
                .onSuccess {
                    discounts = discounts.filter { it.id != id }
                    flash("Offer deleted"); screen = Screen.Discounts
                }
                .onFailure { flash("Could not delete") }
        }
    }

    // ---- banners -----------------------------------------------------------

    fun loadBanners() {
        scope.launch {
            runCatching { Api.banners() }
                .onSuccess { banners = it }
                .onFailure { flash(it.message ?: "Could not load banners") }
        }
    }

    fun newBanner() {
        bId = 0; bEyebrow = ""; bHeading = ""; bSub = ""
        bBtnText = ""; bBtnLink = ""; bImage = ""; bActive = true
        screen = Screen.BannerEdit
    }

    fun editBanner(b: Banner) {
        bId = b.id; bEyebrow = b.eyebrow; bHeading = b.heading; bSub = b.subheading
        bBtnText = b.buttonText; bBtnLink = b.buttonLink; bImage = b.image; bActive = b.active
        screen = Screen.BannerEdit
    }

    fun saveBanner() {
        if (bHeading.isBlank()) { flash("The banner needs a heading"); return }
        val body = JSONObject()
            .put("eyebrow", bEyebrow.trim())
            .put("heading", bHeading.trim())
            .put("subheading", bSub.trim())
            .put("button_text", bBtnText.trim())
            .put("button_link", bBtnLink.trim())
            .put("image_url", bImage.trim())
            .put("is_active", if (bActive) 1 else 0)
        scope.launch {
            saving = true
            try {
                if (bId == 0L) Api.createBanner(body) else Api.setBanner(bId, body)
                banners = Api.banners()
                flash(if (bId == 0L) "Banner added to the homepage" else "Banner saved")
                screen = Screen.Banners
            } catch (e: Exception) {
                flash(e.message ?: "Could not save")
            } finally { saving = false }
        }
    }

    fun toggleBanner(b: Banner) {
        banners = banners.map { if (it.id == b.id) it.copy(active = !it.active) else it }
        scope.launch {
            runCatching { Api.setBanner(b.id, JSONObject().put("is_active", if (b.active) 0 else 1)) }
                .onFailure { flash("Could not change that") }
        }
    }

    fun deleteBanner() {
        val id = bId
        if (id == 0L) { screen = Screen.Banners; return }
        scope.launch {
            runCatching { Api.deleteBanner(id) }
                .onSuccess {
                    banners = banners.filter { it.id != id }
                    flash("Banner deleted"); screen = Screen.Banners
                }
                .onFailure { flash("Could not delete") }
        }
    }

    // ---- alerts ------------------------------------------------------------

    private fun checkAlerts() {
        val low = products.filter { it.active && it.stock in 0..3 && it.id !in lowStockWarned }
        if (low.isNotEmpty()) {
            low.forEach { lowStockWarned.add(it.id) }
            Notify.newOrder(
                ctx,
                if (low.size == 1) "Low stock: " + low[0].name else low.size.toString() + " products low on stock",
                low.joinToString("\n") { it.name + " · " + it.stock + " left" },
                2002,
            )
        }
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour >= 20 && summaryDay != today) {
            summaryDay = today
            val t = todayOrders
            val sales = t.filter { it.st !in DEAD }.sumOf { it.total }
            Notify.newOrder(
                ctx,
                "Today: " + rs(sales) + (if (goal > 0) " of " + rs(goal) else ""),
                t.size.toString() + " orders · " + pendingOrders.size + " still pending · " +
                    rs(codOutstanding.sumOf { it.total }) + " COD not yet received",
                2003,
            )
        }
    }

    fun shareInvoice(o: Order) = Invoice.share(ctx, o)

    // ---- WhatsApp ------------------------------------------------------------

    /** "auto" picks WhatsApp Business when it is installed — that is the store's number. */
    var waApp by mutableStateOf(prefs.getString("waApp", "auto") ?: "auto")
    var cardBusy by mutableStateOf(false)
    /** Photo card waiting for step 2, after the text has opened the right chat. */
    var pendingCard by mutableStateOf<Pair<Order, File>?>(null)

    private fun installed(pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun waPackage(): String? = when (waApp) {
        "business" -> if (installed("com.whatsapp.w4b")) "com.whatsapp.w4b" else null
        "personal" -> if (installed("com.whatsapp")) "com.whatsapp" else null
        else -> when {
            installed("com.whatsapp.w4b") -> "com.whatsapp.w4b"
            installed("com.whatsapp") -> "com.whatsapp"
            else -> null
        }
    }

    fun cycleWaApp() {
        waApp = when (waApp) { "auto" -> "business"; "business" -> "personal"; else -> "auto" }
        prefs.edit().putString("waApp", waApp).apply()
        flash("WhatsApp: " + waAppLabel())
    }

    fun waAppLabel(): String = when (waApp) {
        "business" -> "WhatsApp Business"
        "personal" -> "WhatsApp"
        else -> if (waPackage() == "com.whatsapp.w4b") "auto · Business" else "auto · WhatsApp"
    }

    /**
     * Two steps, because WhatsApp only attaches a picture straight into a chat that already
     * exists. Step 1 opens the customer's chat with the message typed in (works for any
     * number). Step 2 — the bar shown when you come back — drops the photo card into that
     * same chat.
     */
    fun whatsAppWithPhotos(o: Order, template: String) {
        if (cardBusy) return
        scope.launch {
            cardBusy = true
            try {
                val file = OrderCard.build(ctx, o) { imageFor(it) }
                whatsApp(o, template)
                if (file != null) pendingCard = Pair(o, file)
                else flash("Photos could not be prepared — message opened without them")
            } catch (e: Exception) {
                flash(e.message ?: "Could not open WhatsApp")
            } finally {
                cardBusy = false
            }
        }
    }

    fun sendPendingCard() {
        val p = pendingCard ?: return
        pendingCard = null
        val o = p.first
        OrderCard.share(ctx, p.second, "Your order " + o.ref + " · " + rs(o.total), o.waDigits, waPackage())
    }

    fun dismissPendingCard() { pendingCard = null }

    // ---- phone intents -----------------------------------------------------

    private fun shareText(text: String, title: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(chooser) }.onFailure { flash("Nothing to share with") }
    }

    fun call(o: Order) {
        val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + o.localPhone)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    fun copyAddress(o: Order) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(
            android.content.ClipData.newPlainText("address", o.name + "\n" + o.localPhone + "\n" + o.address + ", " + o.city)
        )
        flash("Address copied for the courier slip")
    }

    fun whatsApp(o: Order, template: String) {
        val text = WaTemplates.fill(template, o)
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=" + o.waDigits + "&text=" + Uri.encode(text))
        val pkg = waPackage()
        val opened = runCatching {
            val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) i.setPackage(pkg)
            ctx.startActivity(i)
            true
        }.getOrElse {
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
        if (!opened) { flash("WhatsApp is not installed"); return }
        scope.launch { runCatching { Api.logWhatsApp(o.id, template) } }
        orders = orders.map { if (it.id == o.id && it.waStatus == "none") it.copy(waStatus = "sent") else it }
    }
}

object WaTemplates {
    val keys = listOf("confirm", "address", "dispatch", "cod", "review", "restock")

    fun label(key: String) = when (key) {
        "confirm" -> "Confirm order"
        "address" -> "Ask address"
        "dispatch" -> "Dispatched"
        "cod" -> "COD reminder"
        "review" -> "Ask for review"
        else -> "Come back offer"
    }

    fun fill(key: String, o: Order): String {
        val items = o.items.joinToString("\n") {
            "· " + it.name + (if (it.size != "-") " (size " + it.size + ")" else "") + " x " + it.qty
        }
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
            "cod" ->
                o.firstName + ", your Calista parcel " + o.ref + " is out for delivery today.\n\n" +
                    "Amount to pay the rider: " + rs(o.total) + " (cash on delivery)."
            "review" ->
                o.firstName + ", we hope you love your Calista order " + o.ref + "!\n\n" +
                    "Could you send us a photo or a line about the fabric? It helps other " +
                    "customers choose. Shukriya!"
            else ->
                o.firstName + ", thank you for shopping with Calista. New arrivals are live now — " +
                    "reply to this message and we will send you this week's picks."
        }
    }
}
