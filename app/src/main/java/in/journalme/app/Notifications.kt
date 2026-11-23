package `in`.journalme.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.util.Locale

/** Notification channels, the tracker's ongoing notification, and shared formatting. */
object Notifications {
    const val CHANNEL_TRACKING = "day_tracking"
    const val CHANNEL_RECAP = "daily_recap"
    const val ID_TRACKING = 1001
    const val ID_RECAP = 1002

    /** Opened from the daily recap: the web app routes it to today's day view. */
    const val TODAY_URL = "journalme://day/today"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, "Day tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Journal Me notes where you go during the day."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECAP, "Daily recap", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "An evening nudge with your day's places, distance, steps and photos."
            },
        )
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Opens the app (optionally at a journalme:// deep link). */
    fun openAppIntent(context: Context, url: String? = null, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (url != null) {
                action = Intent.ACTION_VIEW
                data = Uri.parse(url)
            }
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun tracking(context: Context, mode: String, distanceM: Double, steps: Int): android.app.Notification {
        val title = if (mode == "recording") "Recording a route" else "Noting your day"
        val parts = buildList {
            add(formatDistance(distanceM))
            if (steps > 0) add("${formatCount(steps)} steps")
        }
        return NotificationCompat.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_journal)
            .setContentTitle(title)
            .setContentText(parts.joinToString(" · "))
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun formatDistance(m: Double): String =
        if (m < 1000) "${Math.round(m)} m" else String.format(Locale.getDefault(), "%.1f km", m / 1000.0)

    fun formatCount(n: Int): String = NumberFormat.getIntegerInstance().format(n)
}
