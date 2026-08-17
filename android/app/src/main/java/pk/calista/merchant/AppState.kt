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

enum class Screen {
    Connect, Today, Orders, OrderDetail, ConfirmQueue, Products, ProductEdit,
    Customers, CustomerDetail, Reports, Settings, Connections,
    Discounts, DiscountEdit, Banners, BannerEdit
}

/** Built from the order list - the website has no customers table. */
data class Customer(
    val name: String,
    val phone: String,
    val city: String,
    val orders: List<Order>,
) {
    val count: Int get() = orders.size
    val spent: Int get() = orders.filter { !it.status.equals("cancelled", true) }.sumOf { it.total }
    val last: String get() = orders.maxByOrNull { it.id }?.placed?.take(10) ?: ""
    val returns: Int get() = orders.count { it.status.equals("returned", true) }
}

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

    /** Customers */
    var customerQuery by mutableStateOf("")
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

    /** Store-front editing */
    var discounts by mutableStateOf<List<Discount>>(emptyList())
    var banners by mutableStateOf<List<Banner>>(emptyList())
    var fabrics by mutableStateOf<List<String>>(emptyList())
    var uploading by mutableStateOf(false)

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
        if (s == Screen.Discounts && discounts.isEmpty()) loadDiscounts()
        if (s == Screen.Banners && banners.isEmpty()) loadBanners()
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
        runCatching { fabrics = Api.fabrics() }
        runCatching { discounts = Api.discounts() }
        runCatching { banners = Api.banners() }
        runCatching { checkAlerts() }
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

    // ---- customers ---------------------------------------------------------

    val customers: List<Customer>
        get() = orders.groupBy { it.localPhone.ifBlank { it.name } }
            .map { (phone, list) ->
                val newest = list.maxByOrNull { it.id }!!
                Customer(newest.name, phone, newest.city, list.sortedByDescending { it.id })
            }
            .sortedByDescending { it.orders.firstOrNull()?.id ?: 0L }

    val visibleCustomers: List<Customer>
        get() = customers.filter {
            customerQuery.isBlank() ||
                it.name.contains(customerQuery, true) ||
                it.phone.contains(customerQuery)
        }

    val openCustomer: Customer? get() = customers.firstOrNull { it.phone == openPhone }

    fun openCustomer(c: Customer) {
        openPhone = c.phone
        screen = Screen.CustomerDetail
    }

    // ---- product editor ----------------------------------------------------

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

    fun saveDraft() {
        if (dName.isBlank()) { flash("Give the product a name"); return }
        val body = JSONObject()
            .put("name", dName.trim())
            .put("fabric", dFabric.trim())
            .put("price", dPrice.filter { it.isDigit() }.toIntOrNull() ?: 0)
            .put(
                "sale_price",
                (dSale.filter { it.isDigit() }.toIntOrNull() ?: JSONObject.NULL) as Any,
            )
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

    /**
     * Shrinks the picture, uploads it to the store's image bucket and writes the returned
     * path into whichever draft asked for it.
     */
    fun uploadPhoto(uri: android.net.Uri, into: (String) -> Unit) {
        scope.launch {
            uploading = true
            try {
                val file = Photo.prepare(ctx, uri)
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

    fun uploadCameraFile(file: java.io.File, into: (String) -> Unit) =
        uploadPhoto(android.net.Uri.fromFile(file), into)

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
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour >= 20 && summaryDay != today) {
            summaryDay = today
            val st = stats
            if (st != null) {
                Notify.newOrder(
                    ctx,
                    "Today: " + rs(st.sales),
                    st.orders.toString() + " orders · " + rs(st.cod) + " to collect on delivery",
                    2003,
                )
            }
        }
    }

    fun shareInvoice(o: Order) = Invoice.share(ctx, o)

    /** Order card + message: one WhatsApp send that shows the customer their items. */
    fun whatsAppWithPhotos(o: Order, template: String) {
        scope.launch {
            loading = true
            try {
                val file = OrderCard.build(ctx, o) { imageFor(it) }
                if (file == null) {
                    flash("Could not build the card — sending text only")
                    whatsApp(o, template)
                    return@launch
                }
                OrderCard.share(ctx, file, WaTemplates.fill(template, o), o.waDigits)
                runCatching { Api.logWhatsApp(o.id, template) }
                orders = orders.map { if (it.id == o.id) it.copy(waStatus = "sent") else it }
                flash("Opening " + o.firstName + "'s chat · " + o.localPhone)
            } catch (e: Exception) {
                flash(e.message ?: "Could not open WhatsApp")
            } finally {
                loading = false
            }
        }
    }

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
