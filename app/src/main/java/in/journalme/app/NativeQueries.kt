package `in`.journalme.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import `in`.journalme.app.data.CalendarReader
import `in`.journalme.app.data.NativeDb
import `in`.journalme.app.media.MediaScanner
import `in`.journalme.app.sync.BackgroundSync
import `in`.journalme.app.tracking.DayTrackerService
import `in`.journalme.app.tracking.StepCounter
import `in`.journalme.app.tracking.TrackerPrefs
import org.json.JSONObject

/**
 * The native layer's read-only queries, shared by [JournalNativePlugin] (the
 * app is open) and the background worker's bridge (the app is closed; see
 * [BackgroundSync]). JSON shapes match web/src/platform/journalNative.ts.
 * Safe to call from any background thread.
 */
object NativeQueries {

    fun status(context: Context): JSObject {
        val prefs = TrackerPrefs(context)
        val now = System.currentTimeMillis()
        val dayStart = TrackerPrefs.startOfDay(now)
        val db = NativeDb.get(context)
        val service = DayTrackerService.instance
        val pm = context.getSystemService(PowerManager::class.java)
        val lm = context.getSystemService(LocationManager::class.java)
        val distance = service?.todayDistanceM(now) ?: prefs.todayDistanceM(now)
        return JSObject().apply {
            put("enabled", prefs.enabled)
            put("running", service != null)
            put("mode", service?.mode ?: "off")
            put("accuracy", prefs.accuracy)
            db.lastFix()?.let { put("lastFix", fixJson(it)) }
            put("todayFixes", db.countFixes(dayStart, now + 60_000L))
            put("todayDistanceM", Math.round(distance).toDouble())
            put("todaySteps", StepCounter.total(db, dayStart, now))
            put("batteryOptimized", pm?.isIgnoringBatteryOptimizations(context.packageName) != true)
            put("locationEnabled", lm != null && LocationManagerCompat.isLocationEnabled(lm))
            put("partialMediaAccess", partialMediaAccess(context))
            put("recapAt", if (prefs.recapAt < 0) JSONObject.NULL else prefs.recapAt)
            put(
                "device",
                JSObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("brand", Build.BRAND)
                    .put("model", Build.MODEL)
                    .put("sdk", Build.VERSION.SDK_INT),
            )
        }
    }

    fun fixes(context: Context, from: Long, to: Long): JSObject =
        JSObject().put("fixes", NativeDb.get(context).fixesJson(from, to))

    fun activities(context: Context, from: Long, to: Long): JSObject =
        JSObject().put("samples", NativeDb.get(context).activitiesJson(from, to))

    fun steps(context: Context, from: Long, to: Long): JSObject {
        val firstHour = TrackerPrefs.startOfHour(from)
        val buckets = NativeDb.get(context).stepHours(from, to).filter { (hour, _) -> hour in firstHour..to }
        val hours = JSArray()
        for ((hour, count) in buckets) hours.put(JSArray().apply { put(hour); put(count) })
        return JSObject().put("total", buckets.sumOf { it.second }).put("hours", hours)
    }

    fun media(context: Context, from: Long, to: Long): JSObject =
        JSObject().put("items", MediaScanner(context).query(from, to))

    fun thumbnails(context: Context, uris: List<String>, size: Int): JSObject =
        JSObject().put("thumbs", MediaScanner(context).thumbnails(uris, size))

    fun calendar(context: Context, from: Long, to: Long): JSObject =
        JSObject().put("events", CalendarReader.events(context, from, to))

    /** Connected at all, and whether the connection is unmetered (Wi-Fi/Ethernet). */
    fun network(context: Context): JSObject {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val unmetered = caps != null &&
            (
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                    (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
                        )
                )
        return JSObject().put("connected", connected).put("unmetered", connected && unmetered)
    }

    /**
     * Permission states without Capacitor (the background worker has no
     * plugin): "granted", or "prompt" for anything not granted.
     */
    fun permissions(context: Context): JSObject {
        fun has(vararg p: String) = p.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        fun state(ok: Boolean) = if (ok) "granted" else "prompt"
        val media = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            has(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            has(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            has(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        return JSObject().apply {
            put("location", state(has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)))
            put("backgroundLocation", state(has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)))
            put("activity", state(has(Manifest.permission.ACTIVITY_RECOGNITION)))
            put("media", state(media))
            put("mediaLocation", state(has(Manifest.permission.ACCESS_MEDIA_LOCATION)))
            put("notifications", state(notifications))
            put("calendar", state(has(Manifest.permission.READ_CALENDAR)))
        }
    }

    fun partialMediaAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED

    fun fixJson(f: NativeDb.Fix): JSObject = JSObject().apply {
        put("t", f.t)
        put("lat", f.lat)
        put("lng", f.lng)
        put("accuracy", f.accuracy)
        f.altitude?.let { put("altitude", it) }
        f.speed?.let { put("speed", it) }
    }
}
