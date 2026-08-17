package pk.calista.merchant

import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Renders one order as an A5-ish invoice PDF and hands it to the Android share sheet. */
object Invoice {

    fun share(ctx: Context, o: Order) {
        val doc = PdfDocument()
        val w = 595
        val h = 842
        val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
        val c = page.canvas

        val head = Paint().apply { color = AColor.BLACK; textSize = 26f; isFakeBoldText = true }
        val sub = Paint().apply { color = AColor.DKGRAY; textSize = 11f }
        val body = Paint().apply { color = AColor.BLACK; textSize = 12f }
        val bold = Paint().apply { color = AColor.BLACK; textSize = 13f; isFakeBoldText = true }
        val rule = Paint().apply { color = AColor.LTGRAY; strokeWidth = 1f }

        var y = 64f
        c.drawText("CALISTA", 40f, y, head)
        c.drawText("Invoice " + o.ref, w - 190f, y, bold)
        y += 16f
        c.drawText("Embroidered Lawn & Chiffon", 40f, y, sub)
        c.drawText(o.placed.take(19).replace("T", " "), w - 190f, y, sub)
        y += 22f
        c.drawLine(40f, y, w - 40f, y, rule)

        y += 30f
        c.drawText("BILL TO", 40f, y, sub)
        y += 18f
        c.drawText(o.name, 40f, y, bold)
        y += 16f
        c.drawText(o.localPhone, 40f, y, body)
        y += 16f
        for (line in wrap(o.address + ", " + o.city, 62)) {
            c.drawText(line, 40f, y, body)
            y += 15f
        }

        y += 18f
        c.drawLine(40f, y, w - 40f, y, rule)
        y += 20f
        c.drawText("ITEM", 40f, y, sub)
        c.drawText("QTY", w - 190f, y, sub)
        c.drawText("AMOUNT", w - 120f, y, sub)
        y += 10f
        c.drawLine(40f, y, w - 40f, y, rule)
        y += 22f

        for (li in o.items) {
            c.drawText(li.name.take(42), 40f, y, body)
            c.drawText("x" + li.qty, w - 190f, y, body)
            c.drawText(rs(li.price * li.qty), w - 120f, y, body)
            y += 16f
            if (li.size.isNotBlank() && li.size != "-") {
                c.drawText("size " + li.size, 40f, y, sub)
                y += 16f
            }
            y += 4f
        }

        y += 8f
        c.drawLine(40f, y, w - 40f, y, rule)
        y += 26f
        c.drawText("TOTAL", w - 190f, y, bold)
        c.drawText(rs(o.total), w - 120f, y, bold)
        y += 20f
        c.drawText(o.payment, w - 120f, y, sub)

        y += 46f
        c.drawText("Courier: " + o.courier.ifBlank { "—" }, 40f, y, body)
        y += 16f
        c.drawText("Tracking: " + o.tracking.ifBlank { "—" }, 40f, y, body)

        c.drawText("Thank you for shopping with Calista.", 40f, h - 56f, sub)
        doc.finishPage(page)

        val dir = File(ctx.cacheDir, "invoices").apply { mkdirs() }
        val file = File(dir, "Calista-" + o.id + ".pdf")
        runCatching {
            FileOutputStream(file).use { doc.writeTo(it) }
            doc.close()
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Calista invoice " + o.ref)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(send, "Send invoice").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun wrap(text: String, width: Int): List<String> {
        val out = ArrayList<String>()
        var line = StringBuilder()
        for (word in text.split(" ")) {
            if (line.length + word.length + 1 > width) {
                out.add(line.toString()); line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(" ")
            line.append(word)
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return out
    }
}
