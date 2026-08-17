package pk.calista.merchant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Prepares a camera or gallery picture for upload: honours the EXIF rotation the camera
 * records, caps the long edge at 1600px and re-encodes as JPEG, so a 6MB phone photo
 * becomes roughly 300KB before it crosses the network.
 */
object Photo {

    private const val MAX_EDGE = 1600

    suspend fun prepare(ctx: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longEdge / sample > MAX_EDGE * 2) sample *= 2

            var bmp = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return@runCatching null

            bmp = rotate(bmp, bytes)

            val scale = MAX_EDGE.toFloat() / maxOf(bmp.width, bmp.height)
            if (scale < 1f) {
                bmp = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true,
                )
            }

            val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
            val out = File(dir, "shot-" + System.currentTimeMillis() + ".jpg")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            out
        }.getOrNull()
    }

    /** Phones store orientation in EXIF rather than rotating the pixels. */
    private fun rotate(bmp: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(bytes.inputStream())
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return bmp
        }
        return runCatching {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }.getOrDefault(bmp)
    }

    /** A file the camera app can write its full-size shot into. */
    fun cameraTarget(ctx: Context): File {
        val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
        return File(dir, "camera-" + System.currentTimeMillis() + ".jpg")
    }
}
