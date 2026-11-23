package `in`.journalme.app.receivers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import `in`.journalme.app.Alarms
import `in`.journalme.app.Notifications
import `in`.journalme.app.R
import `in`.journalme.app.data.NativeDb
import `in`.journalme.app.media.MediaScanner
import `in`.journalme.app.tracking.DayTrackerService
import `in`.journalme.app.tracking.Io
import `in`.journalme.app.tracking.StepCounter
import `in`.journalme.app.tracking.TrackerPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The evening "How was your Saturday?" nudge: today's distance, steps and
 * photos at a glance; tapping opens the app on today's day view.
 */
class RecapReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission") // Notifications.canPost() checks POST_NOTIFICATIONS
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Alarms.scheduleRecap(app) // tomorrow's
        if (!Notifications.canPost(app)) return
        val pending = goAsync()
        Io.writes.execute {
            try {
                val now = System.currentTimeMillis()
                val dayStart = TrackerPrefs.startOfDay(now)
                val distance = DayTrackerService.instance?.todayDistanceM(now) ?: TrackerPrefs(app).todayDistanceM(now)
                val steps = StepCounter.total(NativeDb.get(app), dayStart, now)
                val media = MediaScanner(app).countBetween(dayStart, now)
                val parts = buildList {
                    if (distance >= 100) add(Notifications.formatDistance(distance))
                    if (steps > 0) add("${Notifications.formatCount(steps)} steps")
                    if (media > 0) add(if (media == 1) "1 photo" else "${Notifications.formatCount(media)} photos")
                }
                val weekday = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(now))
                val text = if (parts.isEmpty()) "Tap to write about your day." else parts.joinToString(" · ") + " · Tap to see your day"
                Notifications.ensureChannels(app)
                val notification = NotificationCompat.Builder(app, Notifications.CHANNEL_RECAP)
                    .setSmallIcon(R.drawable.ic_stat_journal)
                    .setContentTitle("How was your $weekday?")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(Notifications.openAppIntent(app, Notifications.TODAY_URL, requestCode = 2))
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .build()
                NotificationManagerCompat.from(app).notify(Notifications.ID_RECAP, notification)
            } finally {
                pending.finish()
            }
        }
    }
}
