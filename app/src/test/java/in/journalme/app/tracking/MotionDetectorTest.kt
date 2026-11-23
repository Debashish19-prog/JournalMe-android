package `in`.journalme.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MotionDetectorTest {
    private lateinit var d: MotionDetector

    // ~1 m of latitude, for building synthetic tracks.
    private val m = 1.0 / 111_320.0
    private val lat0 = 12.9716
    private val lng0 = 77.5946

    @Before
    fun setUp() {
        d = MotionDetector()
        d.reset(0)
    }

    /** Feed a fix every [stepMs] from [from] to [to], [metresPerFix] north each time. */
    private fun walk(from: Long, to: Long, stepMs: Long, metresPerFix: Double, startM: Double = 0.0): Double {
        var y = startM
        var t = from
        while (t <= to) {
            d.onFix(t, lat0 + y * m, lng0, 8.0)
            y += metresPerFix
            t += stepMs
        }
        return y
    }

    @Test
    fun startsMovingSoTheFirstFixesAreAccurate() {
        assertEquals(MotionDetector.Mode.MOVING, d.mode)
    }

    @Test
    fun becomesStillAfterFourMinutesInOnePlace() {
        walk(0, 3 * 60_000L, 2_000, 0.2) // jitter of a few metres for 3 minutes
        assertEquals(MotionDetector.Mode.MOVING, d.mode)
        walk(3 * 60_000L + 2_000, 4 * 60_000L + 10_000, 2_000, 0.0, startM = 5.0)
        assertEquals(MotionDetector.Mode.STILL, d.mode)
        assertNotNull(d.anchor)
    }

    @Test
    fun staysMovingWhileWalking() {
        walk(0, 20 * 60_000L, 2_000, 2.8) // 1.4 m/s for 20 minutes
        assertEquals(MotionDetector.Mode.MOVING, d.mode)
    }

    @Test
    fun leavesStillWhenFarFromTheAnchor() {
        walk(0, 5 * 60_000L, 2_000, 0.0)
        assertEquals(MotionDetector.Mode.STILL, d.mode)
        // 80 m away: still inside the stay.
        assertNull(d.onFix(6 * 60_000L, lat0 + 80 * m, lng0, 10.0))
        // 250 m away: departed.
        assertEquals(MotionDetector.Mode.MOVING, d.onFix(8 * 60_000L, lat0 + 250 * m, lng0, 10.0))
    }

    @Test
    fun leavesStillOnAClearSpeedReading() {
        walk(0, 5 * 60_000L, 2_000, 0.0)
        assertEquals(MotionDetector.Mode.MOVING, d.onFix(6 * 60_000L, lat0 + 20 * m, lng0, 8.0, speedMps = 5.0))
    }

    @Test
    fun ignoresInaccurateFixes() {
        walk(0, 5 * 60_000L, 2_000, 0.0)
        // A 600 m-off cell-tower fix must not end the stay.
        assertNull(d.onFix(6 * 60_000L, lat0 + 600 * m, lng0, 900.0))
        assertEquals(MotionDetector.Mode.STILL, d.mode)
    }

    @Test
    fun confidentActivityEndsAStay() {
        walk(0, 5 * 60_000L, 2_000, 0.0)
        assertNull(d.onActivity(6 * 60_000L, ActivityCode.WALK, 50)) // not sure enough
        assertEquals(MotionDetector.Mode.MOVING, d.onActivity(6 * 60_000L + 20_000, ActivityCode.VEHICLE, 70))
    }

    @Test
    fun stillActivityShortensTheWait() {
        walk(0, 60_000L, 2_000, 0.0)
        d.onActivity(60_000L, ActivityCode.STILL, 90)
        walk(62_000L, 3 * 60_000L + 2_000, 2_000, 0.0)
        assertEquals(MotionDetector.Mode.STILL, d.mode) // ~2 min, not 4
    }

    @Test
    fun noFixesForAWhileMeansStill() {
        walk(0, 30_000L, 2_000, 1.0)
        assertNull(d.onTick(2 * 60_000L))
        assertEquals(MotionDetector.Mode.STILL, d.onTick(5 * 60_000L))
    }

    @Test
    fun geoDistanceIsRoughlyRight() {
        // 1° of latitude ≈ 111.2 km
        val dist = Geo.distanceM(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, dist, 200.0)
    }
}
