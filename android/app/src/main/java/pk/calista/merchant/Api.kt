package pk.calista.merchant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiError(message: String) : Exception(message)

data class LineItem(val name: String, val size: String, val qty: Int, val price: Int)

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
) {
    val firstName: String get() = name.trim().split(" ").firstOrNull() ?: name
    val ref: String get() = "#" + id
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
)

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
    /** Human sentence for the list row — the type codes mean nothing on their own. */
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

object Api {
    var base: String = ""
    var token: String = ""

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun builder(path: String): Request.Builder {
        if (base.isBlank()) throw ApiError("No store address set")
        return Request.Builder()
            .url(base.trimEnd('/') + path)
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
    }

    private suspend fun exec(b: Request.Builder): JSONObject = withContext(Dispatchers.IO) {
        try {
            client.newCall(b.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code == 401) throw ApiError("Admin token rejected (401)")
                if (!r.isSuccessful) throw ApiError("HTTP " + r.code + " " + text.take(120))
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            throw ApiError(e.message ?: "Cannot reach store")
        }
    }

    private suspend fun get(path: String) = exec(builder(path))
    private suspend fun patch(path: String, body: JSONObject) =
        exec(builder(path).patch(body.toString().toRequestBody(JSON_TYPE)))
    private suspend fun post(path: String, body: JSONObject) =
        exec(builder(path).post(body.toString().toRequestBody(JSON_TYPE)))

    // ---- reads -------------------------------------------------------------

    suspend fun orders(since: String? = null, limit: Int = 120): Pair<List<Order>, String> {
        var path = "/api/admin/orders?limit=" + limit
        if (!since.isNullOrBlank()) {
            path += "&since=" + java.net.URLEncoder.encode(since, "UTF-8")
        }
        val o = get(path)
        val arr = o.optJSONArray("orders") ?: JSONArray()
        val list = ArrayList<Order>()
        for (i in 0 until arr.length()) list.add(parseOrder(arr.getJSONObject(i)))
        return Pair(list, o.optString("server_time"))
    }

    suspend fun stats(range: String): Stats {
        val o = get("/api/admin/stats?range=" + range)
        val s = o.optJSONArray("series") ?: JSONArray()
        val series = ArrayList<Int>()
        for (i in 0 until s.length()) series.add(s.optInt(i))
        return Stats(
            sales = o.optInt("sales"),
            orders = o.optInt("orders"),
            cod = o.optInt("cod"),
            codCount = o.optInt("cod_count"),
            returnRate = o.optString("return_rate", "-"),
            series = series,
        )
    }

    suspend fun products(): List<Product> {
        val o = get("/api/admin/products?limit=200")
        val arr = o.optJSONArray("products") ?: JSONArray()
        val list = ArrayList<Product>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            list.add(
                Product(
                    id = p.optLong("id"),
                    name = p.optString("name"),
                    fabric = p.optString("fabric"),
                    price = p.optInt("price"),
                    sale = if (p.isNull("sale_price")) null else p.optInt("sale_price"),
                    stock = p.optInt("stock"),
                    active = p.optInt("active", 1) == 1,
                    image = p.optString("image_url", ""),
                )
            )
        }
        return list
    }

    // ---- writes ------------------------------------------------------------

    suspend fun setStatus(id: Long, status: String) =
        patch("/api/admin/orders/" + id, JSONObject().put("status", status))

    suspend fun setProduct(id: Long, body: JSONObject) =
        patch("/api/admin/products/" + id, body)

    suspend fun createProduct(body: JSONObject): Long {
        val o = post("/api/admin/products", body)
        return o.optLong("id")
    }

    suspend fun deleteProduct(id: Long) = exec(builder("/api/admin/products/" + id).delete())

    suspend fun logWhatsApp(id: Long, template: String) =
        post("/api/admin/orders/" + id + "/whatsapp", JSONObject().put("template", template))

    /** The Worker calls PostEx create-order and returns the CN it gave back. */
    suspend fun bookPostEx(id: Long): String {
        val o = post("/api/admin/courier/postex", JSONObject().put("orderId", id))
        if (!o.optBoolean("ok", false)) throw ApiError(o.optString("reason", "PostEx refused"))
        return o.optString("tracking")
    }

    suspend fun trackPostEx(cn: String) = get("/api/admin/courier/postex/track/" + cn)

    // ---- image upload ------------------------------------------------------

    /** Posts one photo into the store's R2 bucket. Returns the path, e.g. /uploads/1699…jpg */
    suspend fun upload(file: java.io.File): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "files", file.name,
                file.asRequestBody("image/jpeg".toMediaType()),
            )
            .build()
        val o = exec(builder("/api/admin/app/upload").post(body))
        val urls = o.optJSONArray("urls") ?: JSONArray()
        if (urls.length() == 0) throw ApiError("Upload returned no link")
        urls.optString(0)
    }

    // ---- discounts ---------------------------------------------------------

    suspend fun discounts(): List<Discount> {
        val arr = get("/api/admin/app/discounts").optJSONArray("discounts") ?: JSONArray()
        val list = ArrayList<Discount>()
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            list.add(
                Discount(
                    id = d.optLong("id"),
                    code = if (d.isNull("code")) "" else d.optString("code"),
                    title = d.optString("title"),
                    type = d.optString("type"),
                    value = if (d.isNull("value")) null else d.optInt("value"),
                    minCart = if (d.isNull("min_cart_value")) null else d.optInt("min_cart_value"),
                    usageLimit = if (d.isNull("usage_limit")) null else d.optInt("usage_limit"),
                    usedCount = d.optInt("used_count"),
                    startDate = if (d.isNull("start_date")) "" else d.optString("start_date"),
                    endDate = if (d.isNull("end_date")) "" else d.optString("end_date"),
                    buyQty = if (d.isNull("buy_quantity")) null else d.optInt("buy_quantity"),
                    getQty = if (d.isNull("get_quantity")) null else d.optInt("get_quantity"),
                    getPercent = d.optInt("get_discount_percent", 100),
                    active = d.optInt("is_active", 1) == 1,
                )
            )
        }
        return list
    }

    suspend fun createDiscount(body: JSONObject) = post("/api/admin/app/discounts", body)
    suspend fun setDiscount(id: Long, body: JSONObject) =
        patch("/api/admin/app/discounts/" + id, body)
    suspend fun deleteDiscount(id: Long) =
        exec(builder("/api/admin/app/discounts/" + id).delete())

    // ---- banners -----------------------------------------------------------

    suspend fun banners(): List<Banner> {
        val arr = get("/api/admin/app/banners").optJSONArray("banners") ?: JSONArray()
        val list = ArrayList<Banner>()
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            list.add(
                Banner(
                    id = b.optLong("id"),
                    eyebrow = if (b.isNull("eyebrow")) "" else b.optString("eyebrow"),
                    heading = b.optString("heading"),
                    subheading = if (b.isNull("subheading")) "" else b.optString("subheading"),
                    buttonText = if (b.isNull("button_text")) "" else b.optString("button_text"),
                    buttonLink = if (b.isNull("button_link")) "" else b.optString("button_link"),
                    image = if (b.isNull("image_url")) "" else b.optString("image_url"),
                    sortOrder = b.optInt("sort_order"),
                    active = b.optInt("is_active", 1) == 1,
                )
            )
        }
        return list
    }

    suspend fun createBanner(body: JSONObject) = post("/api/admin/app/banners", body)
    suspend fun setBanner(id: Long, body: JSONObject) =
        patch("/api/admin/app/banners/" + id, body)
    suspend fun deleteBanner(id: Long) = exec(builder("/api/admin/app/banners/" + id).delete())

    suspend fun fabrics(): List<String> {
        val arr = get("/api/admin/app/fabrics").optJSONArray("fabrics") ?: JSONArray()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    // ---- parsing -----------------------------------------------------------

    private fun parseOrder(o: JSONObject): Order {
        val itemsRaw = o.opt("items")
        val arr = when (itemsRaw) {
            is JSONArray -> itemsRaw
            is String -> runCatching { JSONArray(itemsRaw) }.getOrElse { JSONArray() }
            else -> JSONArray()
        }
        val items = ArrayList<LineItem>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            items.add(
                LineItem(
                    name = it.optString("name"),
                    size = it.optString("size", "-"),
                    qty = it.optInt("qty", 1),
                    price = it.optInt("price"),
                )
            )
        }
        return Order(
            id = o.optLong("id"),
            name = o.optString("name"),
            phone = o.optString("phone"),
            city = o.optString("city"),
            address = o.optString("address"),
            status = o.optString("status", "pending"),
            payment = o.optString("payment", "COD"),
            total = o.optInt("total"),
            placed = o.optString("created_at"),
            courier = o.optString("courier", ""),
            tracking = o.optString("tracking", ""),
            waStatus = o.optString("wa_status", "none"),
            items = items,
        )
    }
}

fun rs(amount: Int): String {
    val s = amount.toString()
    val out = StringBuilder()
    for ((i, ch) in s.reversed().withIndex()) {
        if (i > 0 && i % 3 == 0) out.append(',')
        out.append(ch)
    }
    return "Rs " + out.reverse().toString()
}
