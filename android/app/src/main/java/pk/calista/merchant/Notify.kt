package pk.calista.merchant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** New-order chime and heads-up notification. */
object Notify {

    private const val CHANNEL = "calista_orders"

    fun ensure(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(
                    CHANNEL, "New orders", NotificationManager.IMPORTANCE_HIGH,
                )
                ch.description = "Chimes the moment a customer places an order"
                ch.enableVibration(true)
                ch.vibrationPattern = longArrayOf(0, 180, 90, 180)
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun newOrder(ctx: Context, title: String, text: String, id: Int = 1001) {
        ensure(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(longArrayOf(0, 180, 90, 180))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }
}
