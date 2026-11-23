package `in`.journalme.app.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import `in`.journalme.app.Alarms
import `in`.journalme.app.Notifications
import `in`.journalme.app.data.NativeDb
import `in`.journalme.app.sync.BackgroundSync

/**
 * All-day location tracker (foreground service, type "location").
 *
 * Modes:
 *  - moving     high-accuracy GPS every 1–2 s ("best") or 5 s / 5 m ("balanced"),
 *               so walks, runs, rides and drives come out Strava-grade.
 *  - still      low-power fixes every 2 min (best) / 5 min (balanced) while you
 *               stay somewhere; [MotionDetector] decides when to switch.
 *  - recording  a manual route recording: 1 s fixes regardless of motion.
 *
 * Also records activity recognition (via [ActivityReceiver]) and the hardware
 * step counter. Everything goes into [NativeDb]; the web layer reads it later.
 */
class DayTrackerService : Service() {

    companion object {
        private const val TAG = "DayTracker"
        private const val TICK_MS = 60_000L
        private const val NOTIFY_EVERY_MS = 60_000L
        private const val PERSIST_EVERY_MS = 30_000L
        private const val RETENTION_MS = 120L * 24 * 3_600_000L
        private const val MAX_STORED_ACCURACY_M = 1000f
        private const val DISTANCE_MAX_ACCURACY_M = 35.0

        @Volatile
        var instance: DayTrackerService? = null
            private set

        val isRunning: Boolean get() = instance != null

        fun shouldRun(context: Context): Boolean = TrackerPrefs(context).let { it.enabled || it.recording }

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /**
         * Start the service, or make a running one re-read its settings.
         * Returns false when Android refuses (no location permission, or a
         * background start without an exemption).
         */
        fun start(context: Context): Boolean {
            if (!hasLocationPermission(context)) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, DayTrackerService::class.java))
                true
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException (Android 12+) or IllegalStateException.
                Log.w(TAG, "Could not start the tracker: ${e.message}")
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DayTrackerService::class.java))
        }
    }

    private lateinit var prefs: TrackerPrefs
    private lateinit var db: NativeDb
    private lateinit var fused: FusedLocationProviderClient
    private val handler = Handler(Looper.getMainLooper())
    private val detector = MotionDetector()

    /** "still" | "moving" | "recording" while running. */
    @Volatile
    var mode: String = "moving"
        private set

    private var requestedKey: String? = null
    private var lastGood: NativeDb.Fix? = null
    private var distanceDay = 0L
    private var distanceM = 0.0
    private var lastNotifyAt = 0L
    private var lastPersistAt = 0L
    private var activityUpdatesOn = false
    private var stepSensorOn = false

    // ----------------------------------------------------------------- life

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = TrackerPrefs(this)
        db = NativeDb.get(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        Notifications.ensureChannels(this)
        val now = System.currentTimeMillis()
        distanceDay = TrackerPrefs.startOfDay(now)
        distanceM = prefs.todayDistanceM(now)
        detector.reset(now)
        // The tracker keeps the app's process alive all day, so it also runs the Telegram backup.
        BackgroundSync.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground promptly after startForegroundService().
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_TRACKING,
                Notifications.tracking(this, targetMode(), distanceM, 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            // SecurityException (location permission revoked) or a refused background start.
            Log.w(TAG, "startForeground failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!shouldRun(this) || !hasLocationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        applyMode()
        startActivityUpdates()
        startStepCounter()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
        Alarms.scheduleWatchdog(this)
        refreshNotification(force = true)
        TrackerBus.statusChanged()
        return START_STICKY
    }

    override fun onDestroy() {
        BackgroundSync.detach(this)
        handler.removeCallbacksAndMessages(null)
        fused.removeLocationUpdates(locationCallback)
        stopActivityUpdates()
        stopStepCounter()
        prefs.saveTodayDistance(distanceDay, distanceM)
        instance = null
        requestedKey = null
        TrackerBus.statusChanged()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Today's moved distance (metres) as the service counts it. */
    fun todayDistanceM(now: Long = System.currentTimeMillis()): Double =
        if (TrackerPrefs.startOfDay(now) == distanceDay) distanceM else 0.0

    // ------------------------------------------------------------- location

    private fun targetMode(): String = when {
        prefs.recording -> "recording"
        detector.mode == MotionDetector.Mode.STILL -> "still"
        else -> "moving"
    }

    private fun locationRequest(mode: String, accuracy: String): LocationRequest {
        val best = accuracy != TrackerPrefs.ACCURACY_BALANCED
        return when (mode) {
            "recording" ->
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                    .setMinUpdateIntervalMillis(1_000L)
                    .setWaitForAccurateLocation(true)
                    .build()
            "still" ->
                if (best) {
                    LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 120_000L)
                        .setMinUpdateIntervalMillis(60_000L)
                        .build()
                } else {
                    LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 300_000L)
                        .setMinUpdateIntervalMillis(120_000L)
                        .build()
                }
            else ->
                if (best) {
                    LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
                        .setMinUpdateIntervalMillis(1_000L)
                        .setWaitForAccurateLocation(true)
                        .build()
                } else {
                    LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
                        .setMinUpdateIntervalMillis(2_000L)
                        .setMinUpdateDistanceMeters(5f)
                        .setWaitForAccurateLocation(true)
                        .build()
                }
        }
    }

    /** (Re)request location updates if the mode or accuracy setting changed. */
    @SuppressLint("MissingPermission") // checked in hasLocationPermission()
    fun applyMode() {
        if (!hasLocationPermission(this)) {
            stopSelf()
            return
        }
        val target = targetMode()
        val key = "$target/${prefs.accuracy}"
        if (key == requestedKey) return
        try {
            fused.removeLocationUpdates(locationCallback)
            fused.requestLocationUpdates(locationRequest(target, prefs.accuracy), locationCallback, Looper.getMainLooper())
            requestedKey = key
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission lost", e)
            stopSelf()
            return
        }
        val changed = mode != target
        mode = target
        if (changed) {
            refreshNotification(force = true)
            TrackerBus.statusChanged()
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) onLocation(location)
        }
    }

    private fun onLocation(loc: Location) {
        if (!loc.hasAccuracy() || loc.accuracy > MAX_STORED_ACCURACY_M) return
        val altitude = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && loc.hasMslAltitude() -> loc.mslAltitudeMeters
            loc.hasAltitude() -> loc.altitude
            else -> null
        }
        val fix = NativeDb.Fix(
            t = loc.time,
            lat = loc.latitude,
            lng = loc.longitude,
            accuracy = loc.accuracy.toDouble(),
            altitude = altitude,
            speed = if (loc.hasSpeed()) loc.speed.toDouble() else null,
            bearing = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            activity = prefs.activityAt(loc.time),
        )
        Io.writes.execute { db.insertFix(fix) }
        countDistance(fix)
        if (detector.onFix(fix.t, fix.lat, fix.lng, fix.accuracy, fix.speed) != null) applyMode()
        TrackerBus.fix(fix)
        refreshNotification(force = false)
        persistDistance(force = false)
    }

    /** Running distance for the notification/status; the web layer computes the real routes. */
    private fun countDistance(fix: NativeDb.Fix) {
        val day = TrackerPrefs.startOfDay(fix.t)
        if (day != distanceDay) {
            distanceDay = day
            distanceM = 0.0
            lastGood = null
        }
        if (fix.accuracy > DISTANCE_MAX_ACCURACY_M) return
        val prev = lastGood
        if (prev == null || mode == "still") {
            lastGood = fix
            return
        }
        val d = Geo.distanceM(prev.lat, prev.lng, fix.lat, fix.lng)
        val dt = (fix.t - prev.t) / 1000.0
        if (dt <= 0 || d < 3.0) return // jitter: keep measuring from the previous point
        lastGood = fix
        if (d / dt > 70.0) return // a jump, not movement
        distanceM += d
    }

    // ------------------------------------------------------------- activity

    /** Called by [ActivityReceiver] on the main thread. */
    fun onActivity(t: Long, code: Int, confidence: Int) {
        if (detector.onActivity(t, code, confidence) != null) applyMode()
    }

    private fun hasActivityPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked in hasActivityPermission()
    private fun startActivityUpdates() {
        if (activityUpdatesOn || !hasActivityPermission()) return
        try {
            ActivityRecognition.getClient(this)
                .requestActivityUpdates(20_000L, ActivityReceiver.pendingIntent(this))
                .addOnFailureListener { Log.w(TAG, "Activity updates unavailable: ${it.message}") }
            activityUpdatesOn = true
        } catch (e: SecurityException) {
            Log.w(TAG, "Activity recognition not permitted", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopActivityUpdates() {
        if (!activityUpdatesOn) return
        activityUpdatesOn = false
        try {
            ActivityRecognition.getClient(this).removeActivityUpdates(ActivityReceiver.pendingIntent(this))
        } catch (e: SecurityException) {
            Log.w(TAG, "removeActivityUpdates", e)
        }
    }

    // ---------------------------------------------------------------- steps

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val counter = event.values.firstOrNull() ?: return
            val now = System.currentTimeMillis()
            Io.writes.execute { StepCounter.record(applicationContext, counter, now) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun startStepCounter() {
        if (stepSensorOn || !hasActivityPermission()) return
        val sm = getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        // Batched in hardware: wakes us at most every few minutes.
        stepSensorOn = sm.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, 5 * 60 * 1_000_000)
    }

    private fun stopStepCounter() {
        if (!stepSensorOn) return
        getSystemService(SensorManager::class.java)?.unregisterListener(stepListener)
        stepSensorOn = false
    }

    // -------------------------------------------------------- housekeeping

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (detector.onTick(now) != null) applyMode()
            if (TrackerPrefs.startOfDay(now) != distanceDay) {
                distanceDay = TrackerPrefs.startOfDay(now)
                distanceM = 0.0
                lastGood = null
            }
            // Permissions may have been granted since we started.
            startActivityUpdates()
            startStepCounter()
            refreshNotification(force = false)
            persistDistance(force = false)
            pruneIfDue(now)
            BackgroundSync.onTick(this@DayTrackerService)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun persistDistance(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < PERSIST_EVERY_MS) return
        lastPersistAt = now
        prefs.saveTodayDistance(distanceDay, distanceM)
    }

    @SuppressLint("MissingPermission") // Notifications.canPost() checks POST_NOTIFICATIONS
    private fun refreshNotification(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyAt < NOTIFY_EVERY_MS) return
        lastNotifyAt = now
        val currentMode = mode
        val metres = distanceM
        val app = applicationContext
        Io.writes.execute {
            val dayStart = TrackerPrefs.startOfDay(now)
            val steps = StepCounter.total(db, dayStart, now)
            if (instance != null && Notifications.canPost(app)) {
                NotificationManagerCompat.from(app)
                    .notify(Notifications.ID_TRACKING, Notifications.tracking(app, currentMode, metres, steps))
            }
        }
    }

    private fun pruneIfDue(now: Long) {
        val today = TrackerPrefs.startOfDay(now)
        if (prefs.lastPruneDay == today) return
        prefs.lastPruneDay = today
        Io.writes.execute { db.prune(now - RETENTION_MS) }
    }
}
