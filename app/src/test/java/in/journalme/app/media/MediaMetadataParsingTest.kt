package `in`.journalme.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMetadataParsingTest {
    @Test
    fun parsesIso6709VideoLocations() {
        val (lat, lng) = parseIso6709("+12.9716+077.5946/")!!
        assertEquals(12.9716, lat, 1e-9)
        assertEquals(77.5946, lng, 1e-9)
        val (lat2, lng2) = parseIso6709("-33.8688+151.2093+058.000/")!!
        assertEquals(-33.8688, lat2, 1e-9)
        assertEquals(151.2093, lng2, 1e-9)
    }

    @Test
    fun rejectsMissingOrZeroLocations() {
        assertNull(parseIso6709(null))
        assertNull(parseIso6709(""))
        assertNull(parseIso6709("+00.0000+000.0000/"))
        assertNull(parseIso6709("garbage"))
        assertFalse(validLatLng(0.0, 0.0))
        assertFalse(validLatLng(95.0, 10.0))
        assertTrue(validLatLng(12.97, 77.59))
    }

    @Test
    fun parsesExifTimesWithAndWithoutOffset() {
        val withOffset = exifTime("2026:11:21 18:30:00", "+05:30")
        // 18:30 IST = 13:00 UTC
        assertEquals(1_795_266_000_000L, withOffset)
        assertNotNull(exifTime("2026:11:21 18:30:00", null))
        assertNull(exifTime("0000:00:00 00:00:00", null))
        assertNull(exifTime(null, null))
    }

    @Test
    fun parsesVideoDatesAndIgnoresPlaceholders() {
        assertEquals(1_795_266_000_000L, videoTime("20261121T130000.000Z"))
        assertNull(videoTime("19040101T000000.000Z"))
        assertNull(videoTime(null))
    }
}
