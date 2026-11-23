package `in`.journalme.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import `in`.journalme.app.receivers.RecapReceiver
import `in`.journalme.app.receivers.WatchdogReceiver
import `in`.journalme.app.tracking.TrackerPrefs
import java.util.Calendar

/** Inexact alarms only: no SCHEDULE_EXACT_ALARM permission needed. */
object Alarms {
    private const val RECAP_WINDOW_MS = 10 * 60_000L

    private fun recapIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            10,
            Intent(context, RecapReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun watchdogIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            11,
            Intent(context, WatchdogReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Arm (or cancel) the next daily recap from the saved time. */
    fun scheduleRecap(context: Context, now: Long = System.currentTimeMillis()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = recapIntent(context)
        am.cancel(pi)
        val at = TrackerPrefs(context).recapAt
        if (at < 0) return
        am.setWindow(AlarmManager.RTC_WAKEUP, nextOccurrence(at, now), RECAP_WINDOW_MS, pi)
    }

    /** Next time-of-day [minutes] after [now] (today if still ahead, else tomorrow). */
    fun nextOccurrence(minutes: Int, now: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, (minutes / 60).coerceIn(0, 23))
            set(Calendar.MINUTE, (minutes % 60).coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now + 60_000L) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** Every ~15 min, restart the tracker if Android (or an OEM battery manager) killed it. */
    fun scheduleWatchdog(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = watchdogIntent(context)
        am.cancel(pi)
        if (!TrackerPrefs(context).enabled) return
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            pi,
        )
    }

    fun cancelWatchdog(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(watchdogIntent(context))
    }
}
