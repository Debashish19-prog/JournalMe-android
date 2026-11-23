package `in`.journalme.app.tracking

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import `in`.journalme.app.data.NativeDb

/**
 * Receives Google Activity Recognition updates (every ~20 s while the device
 * moves; the system pauses them while it is still). Each result is stored for
 * route classification (walk / run / ride / drive) and fed to the tracker so
 * it can switch GPS on as soon as you set off.
 */
class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val (code, confidence) = classify(result.probableActivities)
        if (code == ActivityCode.UNKNOWN) return
        val t = result.time
        val app = context.applicationContext
        TrackerPrefs(app).saveActivity(t, code, confidence)
        val pending = goAsync()
        Io.writes.execute {
            try {
                NativeDb.get(app).insertActivity(t, code, confidence)
            } finally {
                pending.finish()
            }
        }
        DayTrackerService.instance?.onActivity(t, code, confidence)
    }

    companion object {
        fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                7,
                Intent(context, ActivityReceiver::class.java),
                // Mutable: Play services adds the result as extras.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )

        /** Most likely activity; prefers WALKING/RUNNING over the vaguer ON_FOOT when it's listed. */
        fun classify(activities: List<DetectedActivity>): Pair<Int, Int> {
            val useful = activities
                .filter { it.type != DetectedActivity.TILTING && it.type != DetectedActivity.UNKNOWN }
                .sortedByDescending { it.confidence }
            val top = useful.firstOrNull() ?: return ActivityCode.UNKNOWN to 0
            if (top.type == DetectedActivity.ON_FOOT) {
                val specific = useful.firstOrNull { it.type == DetectedActivity.WALKING || it.type == DetectedActivity.RUNNING }
                if (specific != null && specific.confidence >= 30) return code(specific.type) to top.confidence
            }
            return code(top.type) to top.confidence
        }

        private fun code(type: Int): Int = when (type) {
            DetectedActivity.STILL -> ActivityCode.STILL
            DetectedActivity.WALKING -> ActivityCode.WALK
            DetectedActivity.RUNNING -> ActivityCode.RUN
            DetectedActivity.ON_BICYCLE -> ActivityCode.CYCLE
            DetectedActivity.IN_VEHICLE -> ActivityCode.VEHICLE
            DetectedActivity.ON_FOOT -> ActivityCode.FOOT
            else -> ActivityCode.UNKNOWN
        }
    }
}
