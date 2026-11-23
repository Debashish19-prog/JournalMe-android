package `in`.journalme.app.tracking

import android.content.Context
import `in`.journalme.app.data.NativeDb

/**
 * Turns the hardware step counter (cumulative since boot) into hourly buckets.
 * Reboot-safe: a counter lower than the last one means the phone restarted,
 * so the new value is all new steps.
 */
object StepCounter {
    private const val MAX_PLAUSIBLE_DELTA = 50_000f

    fun record(context: Context, counter: Float, now: Long) {
        val prefs = TrackerPrefs(context)
        val previous = prefs.lastStepCounter
        prefs.lastStepCounter = counter
        if (previous < 0f) return // first reading: just a baseline
        val delta = if (counter >= previous) counter - previous else counter
        if (delta <= 0f || delta > MAX_PLAUSIBLE_DELTA) return
        NativeDb.get(context).addSteps(TrackerPrefs.startOfHour(now), delta.toInt())
    }

    /** Steps in hourly buckets that start within [from, to]. */
    fun total(db: NativeDb, from: Long, to: Long): Int =
        db.stepHours(from, to).filter { (hour, _) -> hour in from..to }.sumOf { it.second }
}
