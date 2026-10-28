package pk.calista.merchant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class ApiError(message: String) : Exception(message)

data class LineItem(
    val name: String,
    val size: String,
    val qty: Int,
    val price: Int,
    val productId: Long = 0,
)

data class Order(
    val id: Long,
    val name: String,
    val phone: String,
    val city: String,
    val address: String,
    val status: String,
    val payment: String,
    val total: Int,
    val placed: String,
    val courier: String,
    val tracking: String,
    val waStatus: String,
    val items: List<LineItem>,
    val discountCode: String = "",
    val discountAmount: Int = 0,
    val riskNote: String = "",
    val note: String = "",
    val codPaidAt: String = "",
    val source: String = "",
    val postexStatus: String = "",
) {
    val firstName: String get() = name.trim().split(" ").firstOrNull() ?: name
    val ref: String get() = "#" + id
    val st: String get() = status.lowercase()
    val codPaid: Boolean get() = codPaidAt.isNotBlank()
    val units: Int get() = items.sumOf { it.qty }

    /** Parsed once per instance — list rows read it on every frame. */
    val placedAt: Long by lazy { Dates.parse(placed) }
    val ageHours: Long get() = if (placedAt == 0L) 0L else (System.currentTimeMillis() - placedAt) / 3_600_000L

    /** Local format PostEx and the dialler both accept: 03XXXXXXXXX. */
    val localPhone: String
        get() {
            var d = phone.filter { it.isDigit() }
            if (d.startsWith("92")) d = "0" + d.substring(2)
            return d.takeLast(11)
        }
    val waDigits: String
        get() {
            var d = phone.filter { it.isDigit() }
            if (d.startsWith("0")) d = "92" + d.substring(1)
            return d
        }
}

data class Product(
    val id: Long,
    val name: String,
    val fabric: String,
    val price: Int,
    val sale: Int?,
    val stock: Int,
    val active: Boolean,
    val image: String = "",
    val slug: String = "",
) {
    val unitPrice: Int get() = if (sale != null && sale > 0) sale else price
}

data class Discount(
    val id: Long,
    val code: String,
    val title: String,
    val type: String,
    val value: Int?,
    val minCart: Int?,
    val usageLimit: Int?,
    val usedCount: Int,
    val startDate: String,
    val endDate: String,
    val buyQty: Int?,
    val getQty: Int?,
    val getPercent: Int,
    val active: Boolean,
) {
    val summary: String
        get() = when (type) {
            "percentage_off_order" -> (value ?: 0).toString() + "% off the order"
            "fixed_off_order" -> rs(value ?: 0) + " off the order"
            "buy_x_get_y" ->
                "Buy " + (buyQty ?: 1) + " get " + (getQty ?: 1) +
                    (if (getPercent >= 100) " free" else " at " + getPercent + "% off")
            else -> type
        }
}

data class Banner(
    val id: Long,
    val eyebrow: String,
    val heading: String,
    val subheading: String,
    val buttonText: String,
    val buttonLink: String,
    val image: String,
    val sortOrder: Int,
    val active: Boolean,
)

data class Stats(
    val sales: Int,
    val orders: Int,
    val cod: Int,
    val codCount: Int,
    val returnRate: String,
    val series: List<Int>,
)

data class Review(
    val id: Long,
    val product: String,
    val name: String,
    val city: String,
    val rating: Int,
    val body: String,
    val approved: Boolean,
    val created: String,
)

data class Blocked(val phone: String, val reason: String, val created: String)

/** Everything the app shows, from one request. */
data class Boot(
    val serverTime: String,
    val orders: List<Order>,
    val products: List<Product>,
    val stats: Stats?,
    val discounts: List<Discount>,
    val banners: List<Banner>,
    val fabrics: List<String>,
    val blocked: List<Blocked>,
)

/** The store writes UTC ("2026-10-28 14:05:00"); the phone shows local time. */
object Dates {
    private val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val dayFmt = SimpleDateFormat("d MMM", Locale.US)
    private val timeFmt = SimpleDateFormat("d MMM, h:mm a", Locale.US)

    fun parse(s: String): Long {
        if (s.length < 10) return 0L
        var t = s.replace('T', ' ').take(19)
        if (t.length == 10) t += " 00:00:00"
        return runCatching { synchronized(utc) { utc.parse(t)?.time ?: 0L } }.getOrDefault(0L)
    }

    fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun hourOf(ms: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return c.get(Calendar.HOUR_OF_DAY)
    }

    fun ago(ms: Long): String {
        if (ms == 0L) return ""
        val m = (System.currentTimeMillis() - ms) / 60_000L
        return when {
            m < 1 -> "now"
            m < 60 -> m.toString() + "m"
            m < 1440 -> (m / 60).toString() + "h"
            m < 10080 -> (m / 1440).toString() + "d"
            else -> synchronized(dayFmt) { dayFmt.format(java.util.Date(ms)) }
        }
    }

    fun stamp(ms: Long): String =
        if (ms == 0L) "" else synchronized(timeFmt) { timeFmt.format(java.util.Date(ms)) }
}

private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")
private fun JSONObject.intOrNull(k: String): Int? = if (isNull(k)) null else optInt(k)

object Api {
    var base: String = ""
    var token: String = ""

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun builder(path: String): Request.Builder {
        if (base.isBlank()) throw ApiError("No store address set")
        return Request.Builder()
            .url(base.trimEnd('/') + path)
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
    }

    private suspend fun execText(b: Request.Builder): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(b.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code == 401) throw ApiError("Admin token rejected (401)")
                if (r.code == 404 && !text.trimStart().startsWith("{")) {
                    throw ApiError("404 · " + base + " is not running the Calista Worker. Check the store address.")
                }
                if (!r.isSuccessful) {
                    val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    throw ApiError(if (!msg.isNullOrBlank()) msg else "HTTP " + r.code + " " + text.take(120))
                }
                text
            }
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            throw ApiError(e.message ?: "Cannot reach store")
        }
    }

    /** Network on IO, JSON parsing on Default — never on the UI thread. */
    private suspend fun exec(b: Request.Builder): JSONObject {
        val text = execText(b)
        return withContext(Dispatchers.Default) { if (text.isBlank()) JSONObject() else JSONObject(text) }
    }

    private suspend fun get(path: String) = exec(builder(path))
    private suspend fun patch(path: String, body: JSONObject) =
        exec(builder(path).patch(body.toString().toRequestBody(JSON_TYPE)))
    private suspend fun post(path: String, body: JSONObject) =
        exec(builder(path).post(body.toString().toRequestBody(JSON_TYPE)))
    private suspend fun delete(path: String) = exec(builder(path).delete())

    // ---- one-shot load -----------------------------------------------------

    /** Returns the parsed store and the raw text, which is cached for instant start. */
    suspend fun bootstrap(range: String): Pair<Boot, String> {
        val text = execText(builder("/api/admin/app/bootstrap?range=" + range + "&limit=300"))
        val boot = withContext(Dispatchers.Default) { parseBoot(JSONObject(text)) }
        return Pair(boot, text)
    }

    fun parseBoot(o: JSONObject): Boot {
        val oa = o.optJSONArray("orders") ?: JSONArray()
        val pa = o.optJSONArray("products") ?: JSONArray()
        val da = o.optJSONArray("discounts") ?: JSONArray()
        val ba = o.optJSONArray("banners") ?: JSONArray()
        val fa = o.optJSONArray("fabrics") ?: JSONArray()
        val bl = o.optJSONArray("blocked") ?: JSONArray()
        return Boot(
            serverTime = o.str("server_time"),
            orders = (0 until oa.length()).map { parseOrder(oa.getJSONObject(it)) },
            products = (0 until pa.length()).map { parseProduct(pa.getJSONObject(it)) },
            stats = o.optJSONObject("stats")?.let { parseStats(it) },
            discounts = (0 until da.length()).map { parseDiscount(da.getJSONObject(it)) },
            banners = (0 until ba.length()).map { parseBanner(ba.getJSONObject(it)) },
            fabrics = (0 until fa.length()).map { fa.optString(it) }.filter { it.isNotBlank() },
            blocked = (0 until bl.length()).map {
                val b = bl.getJSONObject(it)
                Blocked(b.str("phone"), b.str("reason"), b.str("created_at"))
            },
        )
    }

    // ---- reads -------------------------------------------------------------

    suspend fun orders(since: String? = null, limit: Int = 120): Pair<List<Order>, String> {
        var path = "/api/admin/orders?limit=" + limit
        if (!since.isNullOrBlank()) path += "&since=" + java.net.URLEncoder.encode(since, "UTF-8")
        val o = get(path)
        val list = withContext(Dispatchers.Default) {
            val arr = o.optJSONArray("orders") ?: JSONArray()
            (0 until arr.length()).map { parseOrder(arr.getJSONObject(it)) }
        }
        return Pair(list, o.str("server_time"))
    }

    suspend fun stats(range: String): Stats = parseStats(get("/api/admin/stats?range=" + range))

    suspend fun products(): List<Product> {
        val arr = get("/api/admin/products?limit=500").optJSONArray("products") ?: JSONArray()
        return (0 until arr.length()).map { parseProduct(arr.getJSONObject(it)) }
    }

    suspend fun reviews(): List<Review> {
        val arr = get("/api/admin/app/reviews").optJSONArray("reviews") ?: JSONArray()
        return (0 until arr.length()).map {
            val r = arr.getJSONObject(it)
            Review(
                id = r.optLong("id"),
                product = r.str("product_name"),
                name = r.str("customer_name"),
                city = r.str("city"),
                rating = r.optInt("rating", 5),
                body = r.str("body"),
                approved = r.optInt("is_approved") == 1,
                created = r.str("created_at"),
            )
        }
    }

    // ---- writes ------------------------------------------------------------

    suspend fun setStatus(id: Long, status: String) = patchOrder(id, JSONObject().put("status", status))

    suspend fun patchOrder(id: Long, body: JSONObject) = patch("/api/admin/orders/" + id, body)

    /** One request for any number of orders. */
    suspend fun bulkOrders(ids: List<Long>, body: JSONObject) =
        post("/api/admin/app/orders/bulk", body.put("ids", JSONArray(ids)))

    suspend fun createOrder(body: JSONObject): Long = post("/api/admin/app/orders", body).optLong("id")

    suspend fun setProduct(id: Long, body: JSONObject) = patch("/api/admin/products/" + id, body)

    suspend fun createProduct(body: JSONObject): Long = post("/api/admin/products", body).optLong("id")

    suspend fun deleteProduct(id: Long) = delete("/api/admin/products/" + id)

    suspend fun logWhatsApp(id: Long, template: String) =
        post("/api/admin/orders/" + id + "/whatsapp", JSONObject().put("template", template))

    suspend fun bookPostEx(id: Long): String {
        val o = post("/api/admin/courier/postex", JSONObject().put("orderId", id))
        if (!o.optBoolean("ok", false)) throw ApiError(o.optString("reason", "PostEx refused"))
        return o.optString("tracking")
    }

    /** Latest courier status for a CN, e.g. "Delivered" or "Out for delivery". */
    suspend fun trackPostEx(cn: String): String {
        val o = get("/api/admin/courier/postex/track/" + java.net.URLEncoder.encode(cn, "UTF-8"))
        return o.str("status")
    }

    suspend fun block(phone: String, reason: String) =
        post("/api/admin/app/blocked", JSONObject().put("phone", phone).put("reason", reason))

    suspend fun unblock(phone: String) = delete("/api/admin/app/blocked/" + phone.filter { it.isDigit() })

    suspend fun setReview(id: Long, approved: Boolean) =
        patch("/api/admin/app/reviews/" + id, JSONObject().put("is_approved", if (approved) 1 else 0))

    suspend fun deleteReview(id: Long) = delete("/api/admin/app/reviews/" + id)

    suspend fun upload(file: java.io.File): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("files", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            .build()
        val o = exec(builder("/api/admin/app/upload").post(body))
        val urls = o.optJSONArray("urls") ?: JSONArray()
        if (urls.length() == 0) throw ApiError("Upload returned no link")
        return urls.optString(0)
    }

    // ---- discounts / banners ----------------------------------------------

    suspend fun discounts(): List<Discount> {
        val arr = get("/api/admin/app/discounts").optJSONArray("discounts") ?: JSONArray()
        return (0 until arr.length()).map { parseDiscount(arr.getJSONObject(it)) }
    }

    suspend fun createDiscount(body: JSONObject) = post("/api/admin/app/discounts", body)
    suspend fun setDiscount(id: Long, body: JSONObject) = patch("/api/admin/app/discounts/" + id, body)
    suspend fun deleteDiscount(id: Long) = delete("/api/admin/app/discounts/" + id)

    suspend fun banners(): List<Banner> {
        val arr = get("/api/admin/app/banners").optJSONArray("banners") ?: JSONArray()
        return (0 until arr.length()).map { parseBanner(arr.getJSONObject(it)) }
    }

    suspend fun createBanner(body: JSONObject) = post("/api/admin/app/banners", body)
    suspend fun setBanner(id: Long, body: JSONObject) = patch("/api/admin/app/banners/" + id, body)
    suspend fun deleteBanner(id: Long) = delete("/api/admin/app/banners/" + id)

    suspend fun fabrics(): List<String> {
        val arr = get("/api/admin/app/fabrics").optJSONArray("fabrics") ?: JSONArray()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    // ---- parsing -----------------------------------------------------------

    private fun parseStats(o: JSONObject): Stats {
        val s = o.optJSONArray("series") ?: JSONArray()
        return Stats(
            sales = o.optInt("sales"),
            orders = o.optInt("orders"),
            cod = o.optInt("cod"),
            codCount = o.optInt("cod_count"),
            returnRate = o.optString("return_rate", "-"),
            series = (0 until s.length()).map { s.optInt(it) },
        )
    }

    private fun parseProduct(p: JSONObject) = Product(
        id = p.optLong("id"),
        name = p.str("name"),
        fabric = p.str("fabric"),
        price = p.optInt("price"),
        sale = p.intOrNull("sale_price"),
        stock = p.optInt("stock"),
        active = p.optInt("active", 1) == 1,
        image = p.str("image_url"),
        slug = p.str("slug"),
    )

    private fun parseDiscount(d: JSONObject) = Discount(
        id = d.optLong("id"),
        code = d.str("code"),
        title = d.str("title"),
        type = d.str("type"),
        value = d.intOrNull("value"),
        minCart = d.intOrNull("min_cart_value"),
        usageLimit = d.intOrNull("usage_limit"),
        usedCount = d.optInt("used_count"),
        startDate = d.str("start_date"),
        endDate = d.str("end_date"),
        buyQty = d.intOrNull("buy_quantity"),
        getQty = d.intOrNull("get_quantity"),
        getPercent = d.optInt("get_discount_percent", 100),
        active = d.optInt("is_active", 1) == 1,
    )

    private fun parseBanner(b: JSONObject) = Banner(
        id = b.optLong("id"),
        eyebrow = b.str("eyebrow"),
        heading = b.str("heading"),
        subheading = b.str("subheading"),
        buttonText = b.str("button_text"),
        buttonLink = b.str("button_link"),
        image = b.str("image_url"),
        sortOrder = b.optInt("sort_order"),
        active = b.optInt("is_active", 1) == 1,
    )

    private fun parseOrder(o: JSONObject): Order {
        val arr = when (val raw = o.opt("items")) {
            is JSONArray -> raw
            is String -> runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
            else -> JSONArray()
        }
        val items = ArrayList<LineItem>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            items.add(
                LineItem(
                    name = it.str("name"),
                    size = it.str("size").ifBlank { "-" },
                    qty = it.optInt("qty", 1),
                    price = it.optInt("price"),
                    productId = it.optLong("product_id"),
                )
            )
        }
        return Order(
            id = o.optLong("id"),
            name = o.str("name"),
            phone = o.str("phone"),
            city = o.str("city"),
            address = o.str("address"),
            status = o.str("status").ifBlank { "pending" },
            payment = o.str("payment").ifBlank { "COD" },
            total = o.optInt("total"),
            placed = o.str("created_at"),
            courier = o.str("courier"),
            tracking = o.str("tracking"),
            waStatus = o.str("wa_status").ifBlank { "none" },
            items = items,
            discountCode = o.str("discount_code"),
            discountAmount = o.optInt("discount_amount"),
            riskNote = o.str("risk_note"),
            note = o.str("admin_note"),
            codPaidAt = o.str("cod_paid_at"),
            source = o.str("source"),
            postexStatus = o.str("postex_status"),
        )
    }
}

fun rs(amount: Int): String {
    val neg = amount < 0
    val s = kotlin.math.abs(amount).toString()
    val out = StringBuilder()
    for ((i, ch) in s.reversed().withIndex()) {
        if (i > 0 && i % 3 == 0) out.append(',')
        out.append(ch)
    }
    return (if (neg) "-Rs " else "Rs ") + out.reverse().toString()
}
