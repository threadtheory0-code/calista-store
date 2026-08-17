package pk.calista.merchant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Draws one order as a single shareable card — every item photo, the sizes, the total —
 * so the customer sees exactly what they bought inside WhatsApp.
 */
object OrderCard {

    private val http = OkHttpClient()

    private const val W = 1000
    private const val PAD = 56
    private const val ROW = 232
    private const val THUMB_W = 168
    private const val THUMB_H = 210

    suspend fun build(ctx: Context, o: Order, photo: (String) -> String): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val shots = o.items.map { load(photo(it.name)) }
                val h = PAD + 150 + o.items.size * ROW + 250
                val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(AColor.parseColor("#0E0E0E"))

                val gold = AColor.parseColor("#B8923E")
                val goldSoft = AColor.parseColor("#E2D2A6")
                val ivory = AColor.parseColor("#FAFAF8")
                val faint = AColor.parseColor("#7A7A78")

                val brand = Paint().apply {
                    color = ivory; textSize = 54f; isFakeBoldText = true; isAntiAlias = true
                    letterSpacing = 0.14f
                }
                val kicker = Paint().apply {
                    color = gold; textSize = 22f; isAntiAlias = true; letterSpacing = 0.18f
                    isFakeBoldText = true
                }
                val name = Paint().apply { color = ivory; textSize = 32f; isAntiAlias = true }
                val meta = Paint().apply { color = faint; textSize = 25f; isAntiAlias = true }
                val price = Paint().apply {
                    color = goldSoft; textSize = 33f; isFakeBoldText = true; isAntiAlias = true
                }
                val rule = Paint().apply { color = AColor.parseColor("#2A2A28") }
                val frame = Paint().apply {
                    color = AColor.parseColor("#3A3A36"); style = Paint.Style.STROKE; strokeWidth = 2f
                }

                var y = PAD + 46f
                c.drawText("CALISTA", PAD.toFloat(), y, brand)
                c.drawText(
                    "ORDER " + o.ref,
                    W - PAD - meta.measureText("ORDER " + o.ref) - 0f, y - 6f,
                    kicker,
                )
                y += 30f
                c.drawText(o.name + " · " + o.city, PAD.toFloat(), y, meta)
                y += 34f
                c.drawRect(PAD.toFloat(), y, (W - PAD).toFloat(), y + 2f, rule)
                y += 40f

                o.items.forEachIndexed { i, li ->
                    val top = y
                    val shot = shots.getOrNull(i)
                    val box = RectF(
                        PAD.toFloat(), top,
                        (PAD + THUMB_W).toFloat(), (top + THUMB_H),
                    )
                    if (shot != null) {
                        c.drawBitmap(shot, srcCrop(shot), box, null)
                    } else {
                        c.drawRect(box, Paint().apply { color = AColor.parseColor("#1A1A1A") })
                    }
                    c.drawRect(box, frame)

                    val tx = (PAD + THUMB_W + 34).toFloat()
                    var ty = top + 44f
                    for (line in wrap(li.name, name, W - tx - PAD - 200f)) {
                        c.drawText(line, tx, ty, name)
                        ty += 40f
                    }
                    ty += 6f
                    if (li.size.isNotBlank() && li.size != "-") {
                        c.drawText("Size " + li.size, tx, ty, meta)
                        ty += 34f
                    }
                    c.drawText("Quantity " + li.qty, tx, ty, meta)
                    val amount = rs(li.price * li.qty)
                    c.drawText(
                        amount,
                        W - PAD - price.measureText(amount),
                        top + 44f,
                        price,
                    )
                    y = top + ROW
                    c.drawRect(PAD.toFloat(), y - 26f, (W - PAD).toFloat(), y - 25f, rule)
                }

                y += 30f
                val totalLabel = Paint().apply {
                    color = ivory; textSize = 34f; isAntiAlias = true; isFakeBoldText = true
                }
                val totalVal = Paint().apply {
                    color = gold; textSize = 46f; isAntiAlias = true; isFakeBoldText = true
                }
                c.drawText("TOTAL", PAD.toFloat(), y, totalLabel)
                val t = rs(o.total)
                c.drawText(t, W - PAD - totalVal.measureText(t), y + 6f, totalVal)
                y += 46f
                c.drawText(
                    o.payment + " · keep the amount ready for the rider",
                    PAD.toFloat(), y, meta,
                )
                y += 60f
                c.drawRect(PAD.toFloat(), y, (W - PAD).toFloat(), y + 2f, rule)
                y += 44f
                c.drawText("Thank you for shopping with Calista", PAD.toFloat(), y, meta)

                val dir = File(ctx.cacheDir, "invoices").apply { mkdirs() }
                val file = File(dir, "Calista-order-" + o.id + ".png")
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 92, it) }
                file
            }.getOrNull()
        }

    /**
     * Opens the customer's own WhatsApp chat with the card attached and the message as
     * its caption. "jid" is WhatsApp's chat address - 923001234567@s.whatsapp.net - and
     * sending it with the attachment skips the contact picker.
     *
     * Order of attempts: WhatsApp → WhatsApp Business → the general share sheet.
     */
    fun share(ctx: Context, file: File, text: String, waDigits: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val jid = if (waDigits.isBlank()) null else waDigits + "@s.whatsapp.net"

        fun attempt(pkg: String?, withJid: Boolean): Boolean = runCatching {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                if (pkg != null) setPackage(pkg)
                if (withJid && jid != null) putExtra("jid", jid)
            }
            if (i.resolveActivity(ctx.packageManager) == null) return false
            ctx.startActivity(i)
            true
        }.getOrDefault(false)

        if (attempt("com.whatsapp", true)) return
        if (attempt("com.whatsapp.w4b", true)) return
        if (attempt("com.whatsapp", false)) return
        if (attempt("com.whatsapp.w4b", false)) return

        run {
            val any = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(
                Intent.createChooser(any, "Send order card")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun load(url: String): Bitmap? {
        if (url.isBlank()) return null
        return runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) null else BitmapFactory.decodeStream(r.body?.byteStream())
            }
        }.getOrNull()
    }

    /** Centre-crop source rect so the photo fills the frame without stretching. */
    private fun srcCrop(b: Bitmap): Rect {
        val target = THUMB_W.toFloat() / THUMB_H
        val ratio = b.width.toFloat() / b.height
        return if (ratio > target) {
            val w = (b.height * target).toInt()
            Rect((b.width - w) / 2, 0, (b.width + w) / 2, b.height)
        } else {
            val h = (b.width / target).toInt()
            Rect(0, (b.height - h) / 2, b.width, (b.height + h) / 2)
        }
    }

    private fun wrap(text: String, paint: Paint, max: Float): List<String> {
        val out = ArrayList<String>()
        var line = StringBuilder()
        for (word in text.split(" ")) {
            val test = if (line.isEmpty()) word else line.toString() + " " + word
            if (paint.measureText(test) > max && line.isNotEmpty()) {
                out.add(line.toString()); line = StringBuilder(word)
            } else {
                line = StringBuilder(test)
            }
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return out.take(2)
    }
}
