package `in`.journalme.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import `in`.journalme.app.data.NativeDb
import `in`.journalme.app.sync.BackgroundSync
import `in`.journalme.app.tracking.DayTrackerService
import `in`.journalme.app.tracking.TrackerBus
import `in`.journalme.app.tracking.TrackerPrefs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Bridge to the native layer. The TypeScript side of this contract is
 * web/src/platform/journalNative.ts; keep method names and JSON shapes in sync.
 *
 * Permission aliases below are internal: `media` maps to different Android
 * permissions per API level, so checkPermissions/requestPermissions are
 * overridden to report the 7 public keys of NativePermissionStatus.
 */
@CapacitorPlugin(
    name = "JournalNative",
    permissions = [
        Permission(alias = "location", strings = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]),
        Permission(alias = "backgroundLocation", strings = [Manifest.permission.ACCESS_BACKGROUND_LOCATION]),
        Permission(alias = "activity", strings = [Manifest.permission.ACTIVITY_RECOGNITION]),
        Permission(alias = "mediaModern", strings = [Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO]),
        Permission(
            alias = "mediaWithPartial",
            strings = [
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ],
        ),
        Permission(alias = "mediaLegacy", strings = [Manifest.permission.READ_EXTERNAL_STORAGE]),
        Permission(alias = "mediaLocation", strings = [Manifest.permission.ACCESS_MEDIA_LOCATION]),
        Permission(alias = "notifications", strings = [Manifest.permission.POST_NOTIFICATIONS]),
        Permission(alias = "calendar", strings = [Manifest.permission.READ_CALENDAR]),
    ],
)
class JournalNativePlugin : Plugin() {

    /** Heavy reads (fix history, MediaStore + EXIF, thumbnails) stay off Capacitor's shared plugin thread. */
    private val work: ExecutorService = Executors.newFixedThreadPool(2) { r -> Thread(r, "journal-native").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: TrackerPrefs

    /** Background location must be asked for on its own, after foreground location. */
    private var backgroundAfterForeground = false

    private val busListener = object : TrackerBus.Listener {
        override fun onFix(fix: NativeDb.Fix) {
            if (hasListeners("fix")) notifyListeners("fix", NativeQueries.fixJson(fix))
        }

        override fun onStatusChanged() {
            if (hasListeners("status")) work.execute { notifyListeners("status", NativeQueries.status(context)) }
        }
    }

    override fun load() {
        prefs = TrackerPrefs(context)
        Notifications.ensureChannels(context)
        TrackerBus.listener = busListener
        // Opening the app is a good moment to make sure the tracker is alive.
        if (DayTrackerService.shouldRun(context) && !DayTrackerService.isRunning) DayTrackerService.start(context)
        Alarms.scheduleRecap(context)
    }

    override fun handleOnDestroy() {
        if (TrackerBus.listener === busListener) TrackerBus.listener = null
        super.handleOnDestroy()
    }

    // ------------------------------------------------------------ tracking

    @PluginMethod
    fun getStatus(call: PluginCall) {
        work.execute { call.resolve(NativeQueries.status(context)) }
    }

    @PluginMethod
    fun startTracking(call: PluginCall) {
        if (!hasForegroundLocation()) {
            call.reject("Location permission was denied", "PERMISSION_DENIED")
            return
        }
        prefs.accuracy = call.getString("accuracy") ?: TrackerPrefs.ACCURACY_BEST
        prefs.enabled = true
        if (!DayTrackerService.start(context)) {
            prefs.enabled = false
            call.reject("Android did not allow the tracker to start", "START_FAILED")
            return
        }
        Alarms.scheduleWatchdog(context)
        resolveStatusSoon(call)
    }

    @PluginMethod
    fun stopTracking(call: PluginCall) {
        prefs.enabled = false
        Alarms.cancelWatchdog(context)
        if (prefs.recording) DayTrackerService.start(context) else DayTrackerService.stop(context)
        resolveStatusSoon(call)
    }

    @PluginMethod
    fun setRecording(call: PluginCall) {
        val active = call.getBoolean("active") ?: false
        if (active && !hasForegroundLocation()) {
            call.reject("Location permission was denied", "PERMISSION_DENIED")
            return
        }
        prefs.recording = active
        when {
            active || prefs.enabled -> DayTrackerService.start(context) // start, or switch the running service's mode
            else -> DayTrackerService.stop(context)
        }
        call.resolve()
    }

    @SuppressLint("MissingPermission") // checked in hasForegroundLocation()
    @PluginMethod
    fun getCurrentPosition(call: PluginCall) {
        if (!hasForegroundLocation()) {
            call.reject("Location permission was denied", "PERMISSION_DENIED")
            return
        }
        val high = call.getBoolean("highAccuracy") ?: true
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val request = CurrentLocationRequest.Builder()
            .setPriority(if (high) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setDurationMillis(20_000L)
            .setMaxUpdateAgeMillis(30_000L)
            .build()
        val fallback = {
            fused.lastLocation
                .addOnSuccessListener { last ->
                    if (last != null) call.resolve(locationJson(last)) else call.reject("Location is unavailable", "UNAVAILABLE")
                }
                .addOnFailureListener { call.reject("Location is unavailable", "UNAVAILABLE") }
        }
        try {
            fused.getCurrentLocation(request, CancellationTokenSource().token)
                .addOnSuccessListener { loc -> if (loc != null) call.resolve(locationJson(loc)) else fallback() }
                .addOnFailureListener { fallback() }
        } catch (e: SecurityException) {
            call.reject("Location permission was denied", "PERMISSION_DENIED")
        }
    }

    // ------------------------------------------------------------- history

    @PluginMethod
    fun getFixes(call: PluginCall) {
        val (from, to) = range(call) ?: return
        work.execute { call.resolve(NativeQueries.fixes(context, from, to)) }
    }

    @PluginMethod
    fun getActivities(call: PluginCall) {
        val (from, to) = range(call) ?: return
        work.execute { call.resolve(NativeQueries.activities(context, from, to)) }
    }

    @PluginMethod
    fun getSteps(call: PluginCall) {
        val (from, to) = range(call) ?: return
        work.execute { call.resolve(NativeQueries.steps(context, from, to)) }
    }

    // --------------------------------------------------------------- media

    @PluginMethod
    fun queryMedia(call: PluginCall) {
        val (from, to) = range(call) ?: return
        work.execute {
            try {
                call.resolve(NativeQueries.media(context, from, to))
            } catch (e: Exception) {
                call.reject("Could not read photos and videos: ${e.message}", "MEDIA_FAILED", e)
            }
        }
    }

    @PluginMethod
    fun getThumbnails(call: PluginCall) {
        val uris = try {
            call.getArray("uris")?.toList<String>() ?: emptyList()
        } catch (e: Exception) {
            call.reject("uris must be an array of strings")
            return
        }
        val size = call.getInt("size") ?: 320
        work.execute { call.resolve(NativeQueries.thumbnails(context, uris, size)) }
    }

    @PluginMethod
    fun getCalendarEvents(call: PluginCall) {
        val (from, to) = range(call) ?: return
        work.execute { call.resolve(NativeQueries.calendar(context, from, to)) }
    }

    // ---------------------------------------------------------------- sync

    @PluginMethod
    fun getNetwork(call: PluginCall) {
        call.resolve(NativeQueries.network(context))
    }

    @PluginMethod
    fun getBackgroundSync(call: PluginCall) {
        call.resolve(BackgroundSync.status(context))
    }

    // ------------------------------------------------------------ settings

    @PluginMethod
    fun setDailyRecap(call: PluginCall) {
        val at = call.data.opt("at")
        prefs.recapAt = if (at is Number) at.toInt().coerceIn(0, 24 * 60 - 1) else -1
        Alarms.scheduleRecap(context)
        call.resolve()
    }

    @SuppressLint("BatteryLife") // personal, sideloaded app: the direct prompt is the point
    @PluginMethod
    fun requestBatteryExemption(call: PluginCall) {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(context.packageName) == true) {
            call.resolve()
            return
        }
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        launch(call, direct, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    @PluginMethod
    fun openSettings(call: PluginCall) {
        val pkg = context.packageName
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        val intent = when (call.getString("target")) {
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            else -> appDetails
        }
        launch(call, intent, appDetails)
    }

    private fun launch(call: PluginCall, intent: Intent, fallback: Intent) {
        main.post {
            val starter = activity ?: context
            try {
                starter.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                call.resolve()
            } catch (e: ActivityNotFoundException) {
                try {
                    starter.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    call.resolve()
                } catch (e2: Exception) {
                    call.reject("Settings screen not available", "UNAVAILABLE")
                }
            }
        }
    }

    // --------------------------------------------------------- permissions

    @PluginMethod
    override fun checkPermissions(call: PluginCall) {
        call.resolve(permissionStatus())
    }

    @PluginMethod
    override fun requestPermissions(call: PluginCall) {
        val wanted = try {
            call.getArray("permissions")?.toList<String>()
        } catch (e: Exception) {
            null
        }?.takeIf { it.isNotEmpty() } ?: PUBLIC_KEYS

        val aliases = linkedSetOf<String>()
        backgroundAfterForeground = false
        for (key in wanted) {
            when (key) {
                "location" -> aliases += "location"
                "backgroundLocation" ->
                    if (hasForegroundLocation()) {
                        aliases += "backgroundLocation"
                    } else {
                        aliases += "location"
                        backgroundAfterForeground = true
                    }
                "activity" -> aliases += "activity"
                "media" -> aliases += mediaAlias(forRequest = true)
                "mediaLocation" -> aliases += "mediaLocation"
                "notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) aliases += "notifications"
                "calendar" -> aliases += "calendar"
            }
        }
        // Android 11+ ignores a background-location request bundled with anything else.
        if ("backgroundLocation" in aliases && aliases.size > 1) {
            aliases -= "backgroundLocation"
            backgroundAfterForeground = true
        }
        if (aliases.isEmpty()) {
            call.resolve(permissionStatus())
            return
        }
        requestPermissionForAliases(aliases.toTypedArray(), call, "onPermissionsResult")
    }

    @PermissionCallback
    private fun onPermissionsResult(call: PluginCall) {
        if (backgroundAfterForeground && hasForegroundLocation() && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            backgroundAfterForeground = false
            requestPermissionForAliases(arrayOf("backgroundLocation"), call, "onPermissionsResult")
            return
        }
        backgroundAfterForeground = false
        // Newly granted activity/notification permissions take effect in a running tracker on its next start command.
        if (DayTrackerService.isRunning) DayTrackerService.start(context)
        call.resolve(permissionStatus())
    }

    private fun permissionStatus(): JSObject = JSObject().apply {
        put("location", state("location"))
        put("backgroundLocation", state("backgroundLocation"))
        put("activity", state("activity"))
        put("media", state(mediaAlias(forRequest = false)))
        put("mediaLocation", state("mediaLocation"))
        put(
            "notifications",
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> state("notifications")
                NotificationManagerCompat.from(context).areNotificationsEnabled() -> PermissionState.GRANTED.toString()
                else -> PermissionState.DENIED.toString()
            },
        )
        put("calendar", state("calendar"))
    }

    /** Full photo+video access alias for this API level (partial "Select photos" access doesn't count as granted). */
    private fun mediaAlias(forRequest: Boolean): String = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && forRequest -> "mediaWithPartial"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> "mediaModern"
        else -> "mediaLegacy"
    }

    private fun state(alias: String): String = (getPermissionState(alias) ?: PermissionState.PROMPT).toString()

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasForegroundLocation() =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    // ------------------------------------------------------------- helpers

    /** Give a just-started service a moment to come up so `running`/`mode` are accurate. */
    private fun resolveStatusSoon(call: PluginCall) {
        main.postDelayed({ work.execute { call.resolve(NativeQueries.status(context)) } }, 600)
    }

    /** `{from, to}` epoch-ms options; rejects the call and returns null when missing. */
    private fun range(call: PluginCall): Pair<Long, Long>? {
        val from = (call.data.opt("from") as? Number)?.toLong()
        val to = (call.data.opt("to") as? Number)?.toLong()
        if (from == null || to == null || to < from) {
            call.reject("from and to (epoch ms, from ≤ to) are required")
            return null
        }
        return from to to
    }

    private fun locationJson(l: Location): JSObject = JSObject().apply {
        put("t", l.time)
        put("lat", l.latitude)
        put("lng", l.longitude)
        put("accuracy", l.accuracy.toDouble())
        if (l.hasAltitude()) put("altitude", l.altitude)
        if (l.hasSpeed()) put("speed", l.speed.toDouble())
    }

    companion object {
        private val PUBLIC_KEYS =
            listOf("location", "activity", "media", "mediaLocation", "notifications", "calendar")
    }
}
