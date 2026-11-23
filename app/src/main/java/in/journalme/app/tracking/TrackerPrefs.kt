package `in`.journalme.app.tracking

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Calendar

/**
 * Tracker settings and counters that must survive process death and reboots
 * (the service, receivers and plugin all run without the WebView).
 */
class TrackerPrefs(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("journal_tracker", Context.MODE_PRIVATE)

    /** The user turned day tracking on. */
    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) = p.edit { putBoolean("enabled", v) }

    /** "best" or "balanced". */
    var accuracy: String
        get() = p.getString("accuracy", ACCURACY_BEST) ?: ACCURACY_BEST
        set(v) = p.edit { putString("accuracy", if (v == ACCURACY_BALANCED) ACCURACY_BALANCED else ACCURACY_BEST) }

    /** A manual (Strava-style) route recording is in progress. */
    var recording: Boolean
        get() = p.getBoolean("recording", false)
        set(v) = p.edit { putBoolean("recording", v) }

    /** Daily recap time in minutes after midnight, or -1 when off. */
    var recapAt: Int
        get() = p.getInt("recapAt", -1)
        set(v) = p.edit { putInt("recapAt", v) }

    // ---- today's running distance (reset at local midnight) ---------------

    /** Metres moved today; 0 if the stored counter belongs to an earlier day. */
    fun todayDistanceM(now: Long = System.currentTimeMillis()): Double =
        if (p.getLong("distanceDay", 0L) == startOfDay(now)) p.getFloat("distanceM", 0f).toDouble() else 0.0

    fun saveTodayDistance(day: Long, metres: Double) =
        p.edit {
            putLong("distanceDay", day)
            putFloat("distanceM", metres.toFloat())
        }

    // ---- step counter baseline (TYPE_STEP_COUNTER counts since boot) -------

    var lastStepCounter: Float
        get() = p.getFloat("stepCounter", -1f)
        set(v) = p.edit { putFloat("stepCounter", v) }

    // ---- latest activity-recognition result ---------------------------------

    fun saveActivity(t: Long, code: Int, confidence: Int) =
        p.edit {
            putLong("actT", t)
            putInt("act", code)
            putInt("actConf", confidence)
        }

    /** Latest activity code if it is at most [maxAgeMs] old at [t], else UNKNOWN. */
    fun activityAt(t: Long, maxAgeMs: Long = 2 * 60_000L): Int {
        val at = p.getLong("actT", 0L)
        return if (at > 0 && t - at in -maxAgeMs..maxAgeMs) p.getInt("act", ActivityCode.UNKNOWN) else ActivityCode.UNKNOWN
    }

    var lastPruneDay: Long
        get() = p.getLong("lastPruneDay", 0L)
        set(v) = p.edit { putLong("lastPruneDay", v) }

    companion object {
        const val ACCURACY_BEST = "best"
        const val ACCURACY_BALANCED = "balanced"

        fun startOfDay(t: Long): Long =
            Calendar.getInstance().run {
                timeInMillis = t
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                timeInMillis
            }

        fun startOfHour(t: Long): Long =
            Calendar.getInstance().run {
                timeInMillis = t
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                timeInMillis
            }
    }
}
