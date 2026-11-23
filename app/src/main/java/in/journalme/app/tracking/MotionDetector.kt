package `in`.journalme.app.tracking

/**
 * Decides whether the phone is staying put or on the move, so the tracker can
 * use high-accuracy GPS only while it matters (Strava-grade routes) and a
 * low-power provider while you stay somewhere (visits).
 *
 *  MOVING → STILL  every good fix of the last [stillAfterMs] is within
 *                  [stillRadiusM] of their centroid; or activity recognition
 *                  has said STILL for [stillByActivityMs] and the fixes since
 *                  agree; or no fix at all arrived for [stillAfterMs] (a
 *                  distance filter stops updates when you don't move).
 *  STILL → MOVING  a good fix lands more than [departRadiusM] from the stay's
 *                  anchor, the fix itself reports a clear speed, or activity
 *                  recognition is confident you are walking/riding/driving.
 *
 * Pure Kotlin (no Android types) so it is covered by JVM unit tests.
 */
class MotionDetector(
    private val stillRadiusM: Double = 60.0,
    private val stillAfterMs: Long = 4 * 60_000L,
    private val stillByActivityMs: Long = 2 * 60_000L,
    private val departRadiusM: Double = 100.0,
    private val maxUsefulAccuracyM: Double = 100.0,
) {
    enum class Mode { STILL, MOVING }

    data class Point(val t: Long, val lat: Double, val lng: Double, val accuracy: Double)

    var mode: Mode = Mode.MOVING
        private set

    /** Where the current stay is (only while STILL). */
    var anchor: Point? = null
        private set

    private val window = ArrayDeque<Point>()
    private var lastFixT = 0L
    private var activityStillSince: Long? = null

    /** Start fresh (e.g. after the service restarts). Starts MOVING so the first fixes are accurate. */
    fun reset(now: Long) {
        mode = Mode.MOVING
        anchor = null
        window.clear()
        lastFixT = now
        activityStillSince = null
    }

    /** Feed one location fix. Returns the new mode if it changed, else null. */
    fun onFix(t: Long, lat: Double, lng: Double, accuracy: Double, speedMps: Double? = null): Mode? {
        if (accuracy > maxUsefulAccuracyM) return null
        lastFixT = maxOf(lastFixT, t)
        val p = Point(t, lat, lng, accuracy)
        return when (mode) {
            Mode.STILL -> {
                val a = anchor ?: return switchTo(Mode.MOVING, t)
                val d = Geo.distanceM(a.lat, a.lng, lat, lng)
                val fast = speedMps != null && speedMps > 2.5 && accuracy <= 30
                if (d > departRadiusM + minOf(accuracy, 50.0) || fast) switchTo(Mode.MOVING, t, p) else null
            }
            Mode.MOVING -> {
                window.addLast(p)
                trim(t)
                if (isSettled(t, stillAfterMs) || isSettledByActivity(t)) switchTo(Mode.STILL, t) else null
            }
        }
    }

    /** Feed one activity-recognition result. Returns the new mode if it changed, else null. */
    fun onActivity(t: Long, code: Int, confidence: Int): Mode? {
        if (code == ActivityCode.STILL && confidence >= 75) {
            if (activityStillSince == null) activityStillSince = t
        } else if (code != ActivityCode.UNKNOWN) {
            activityStillSince = null
        }
        if (mode == Mode.STILL && ActivityCode.isMoving(code)) {
            val needed = if (ActivityCode.isOnFoot(code)) 75 else 60
            if (confidence >= needed) return switchTo(Mode.MOVING, t)
        }
        if (mode == Mode.MOVING && isSettledByActivity(t)) return switchTo(Mode.STILL, t)
        return null
    }

    /** Call periodically (about once a minute): handles "no fixes because nothing moved". */
    fun onTick(now: Long): Mode? {
        if (mode == Mode.MOVING && window.isNotEmpty() && now - lastFixT >= stillAfterMs) {
            return switchTo(Mode.STILL, now)
        }
        return null
    }

    private fun isSettled(now: Long, spanMs: Long): Boolean {
        val first = window.firstOrNull() ?: return false
        if (now - first.t < spanMs) return false
        val recent = window.filter { it.t >= now - spanMs } + first
        return withinRadius(recent)
    }

    private fun isSettledByActivity(now: Long): Boolean {
        val since = activityStillSince ?: return false
        if (now - since < stillByActivityMs) return false
        val recent = window.filter { it.t >= since }
        return recent.isNotEmpty() && withinRadius(recent)
    }

    private fun withinRadius(points: List<Point>): Boolean {
        val lat = points.sumOf { it.lat } / points.size
        val lng = points.sumOf { it.lng } / points.size
        return points.all { Geo.distanceM(lat, lng, it.lat, it.lng) <= stillRadiusM + minOf(it.accuracy, 40.0) }
    }

    /** Keep a little more than one still-window of fixes. */
    private fun trim(now: Long) {
        val keepFrom = now - stillAfterMs - 60_000L
        while (window.size > 2 && window[1].t < keepFrom) window.removeFirst()
    }

    private fun switchTo(next: Mode, t: Long, departure: Point? = null): Mode {
        if (next == Mode.STILL) {
            val pts = window.filter { it.t >= t - stillAfterMs }.ifEmpty { window.toList() }
            anchor = if (pts.isEmpty()) {
                anchor
            } else {
                Point(t, pts.sumOf { it.lat } / pts.size, pts.sumOf { it.lng } / pts.size, pts.minOf { it.accuracy })
            }
            window.clear()
        } else {
            anchor = null
            window.clear()
            departure?.let { window.addLast(it) }
        }
        lastFixT = maxOf(lastFixT, t)
        mode = next
        return next
    }
}
