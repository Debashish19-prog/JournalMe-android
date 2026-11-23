package `in`.journalme.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.getcapacitor.JSArray
import org.json.JSONObject

/**
 * On-device store for raw day data: GPS fixes, activity samples, hourly steps,
 * and a cache of photo/video metadata (EXIF is slow to read).
 *
 * Raw data never leaves the phone; the web layer derives visits, routes and
 * day stories from it and only those sync to Telegram. WAL mode lets the
 * plugin read while the tracker service writes.
 */
class NativeDb private constructor(context: Context) :
    SQLiteOpenHelper(context, NAME, null, VERSION) {

    companion object {
        private const val NAME = "journal_native.db"
        private const val VERSION = 1

        @Volatile private var instance: NativeDb? = null

        fun get(context: Context): NativeDb =
            instance ?: synchronized(this) {
                instance ?: NativeDb(context.applicationContext).also { instance = it }
            }
    }

    /** One stored fix (see NativeFixRow in web/src/platform/journalNative.ts). */
    data class Fix(
        val t: Long,
        val lat: Double,
        val lng: Double,
        val accuracy: Double,
        val altitude: Double?,
        val speed: Double?,
        val bearing: Double?,
        val activity: Int,
    )

    /** Cached EXIF / video metadata for one MediaStore item. */
    data class MediaMeta(
        val make: String?,
        val model: String?,
        val software: String?,
        val lat: Double?,
        val lng: Double?,
        val takenAt: Long?,
    )

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE fixes (
                t INTEGER NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, acc REAL NOT NULL,
                alt REAL, speed REAL, bearing REAL, act INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL("CREATE INDEX fixes_t ON fixes(t)")
        db.execSQL("CREATE TABLE activities (t INTEGER NOT NULL, act INTEGER NOT NULL, conf INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX activities_t ON activities(t)")
        db.execSQL("CREATE TABLE steps (hour INTEGER PRIMARY KEY, count INTEGER NOT NULL)")
        db.execSQL(
            """CREATE TABLE media_meta (
                id INTEGER NOT NULL, kind TEXT NOT NULL, modified INTEGER NOT NULL, loc_access INTEGER NOT NULL,
                make TEXT, model TEXT, software TEXT, lat REAL, lng REAL, taken INTEGER,
                PRIMARY KEY (id, kind))""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---------------------------------------------------------------- fixes

    fun insertFix(f: Fix) {
        val v = ContentValues(8).apply {
            put("t", f.t)
            put("lat", f.lat)
            put("lng", f.lng)
            put("acc", f.accuracy)
            put("alt", f.altitude)
            put("speed", f.speed)
            put("bearing", f.bearing)
            put("act", f.activity)
        }
        writableDatabase.insert("fixes", null, v)
    }

    /** Rows as compact JSON arrays: [t, lat, lng, acc, alt|null, speed|null, bearing|null, act]. */
    fun fixesJson(from: Long, to: Long): JSArray {
        val out = JSArray()
        readableDatabase.rawQuery(
            "SELECT t, lat, lng, acc, alt, speed, bearing, act FROM fixes WHERE t BETWEEN ? AND ? ORDER BY t",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val row = JSArray()
                row.put(c.getLong(0))
                row.put(c.getDouble(1))
                row.put(c.getDouble(2))
                row.put(round1(c.getDouble(3)))
                row.put(if (c.isNull(4)) JSONObject.NULL else round1(c.getDouble(4)))
                row.put(if (c.isNull(5)) JSONObject.NULL else round2(c.getDouble(5)))
                row.put(if (c.isNull(6)) JSONObject.NULL else round1(c.getDouble(6)))
                row.put(c.getInt(7))
                out.put(row)
            }
        }
        return out
    }

    fun countFixes(from: Long, to: Long): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM fixes WHERE t BETWEEN ? AND ?", arrayOf(from.toString(), to.toString()))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun lastFix(): Fix? =
        readableDatabase.rawQuery(
            "SELECT t, lat, lng, acc, alt, speed, bearing, act FROM fixes ORDER BY t DESC LIMIT 1",
            null,
        ).use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                Fix(
                    t = c.getLong(0),
                    lat = c.getDouble(1),
                    lng = c.getDouble(2),
                    accuracy = c.getDouble(3),
                    altitude = if (c.isNull(4)) null else c.getDouble(4),
                    speed = if (c.isNull(5)) null else c.getDouble(5),
                    bearing = if (c.isNull(6)) null else c.getDouble(6),
                    activity = c.getInt(7),
                )
            }
        }

    // ----------------------------------------------------------- activities

    fun insertActivity(t: Long, activity: Int, confidence: Int) {
        val v = ContentValues(3).apply {
            put("t", t)
            put("act", activity)
            put("conf", confidence)
        }
        writableDatabase.insert("activities", null, v)
    }

    fun activitiesJson(from: Long, to: Long): JSArray {
        val out = JSArray()
        readableDatabase.rawQuery(
            "SELECT t, act, conf FROM activities WHERE t BETWEEN ? AND ? ORDER BY t",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val row = JSArray()
                row.put(c.getLong(0))
                row.put(c.getInt(1))
                row.put(c.getInt(2))
                out.put(row)
            }
        }
        return out
    }

    // ---------------------------------------------------------------- steps

    /** Add steps to the bucket of the hour starting at [hourStart]. (No UPSERT: Android 10 ships SQLite 3.22.) */
    fun addSteps(hourStart: Long, delta: Int) {
        if (delta <= 0) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val updated = db.compileStatement("UPDATE steps SET count = count + ? WHERE hour = ?").use { s ->
                s.bindLong(1, delta.toLong())
                s.bindLong(2, hourStart)
                s.executeUpdateDelete()
            }
            if (updated == 0) {
                db.insert("steps", null, ContentValues(2).apply {
                    put("hour", hourStart)
                    put("count", delta)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Hourly buckets whose hour starts in [from − 1 h, to]; the caller clips. */
    fun stepHours(from: Long, to: Long): List<Pair<Long, Int>> {
        val out = mutableListOf<Pair<Long, Int>>()
        readableDatabase.rawQuery(
            "SELECT hour, count FROM steps WHERE hour > ? AND hour <= ? ORDER BY hour",
            arrayOf((from - 3_600_000L).toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) out += c.getLong(0) to c.getInt(1)
        }
        return out
    }

    // ------------------------------------------------------------ media meta

    /** Cached metadata if it was read from the same file version with the same location access. */
    fun mediaMeta(id: Long, kind: String, modified: Long, locationAccess: Boolean): MediaMeta? =
        readableDatabase.rawQuery(
            "SELECT make, model, software, lat, lng, taken FROM media_meta WHERE id = ? AND kind = ? AND modified = ? AND loc_access = ?",
            arrayOf(id.toString(), kind, modified.toString(), if (locationAccess) "1" else "0"),
        ).use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                MediaMeta(
                    make = c.getString(0),
                    model = c.getString(1),
                    software = c.getString(2),
                    lat = if (c.isNull(3)) null else c.getDouble(3),
                    lng = if (c.isNull(4)) null else c.getDouble(4),
                    takenAt = if (c.isNull(5)) null else c.getLong(5),
                )
            }
        }

    fun putMediaMeta(id: Long, kind: String, modified: Long, locationAccess: Boolean, meta: MediaMeta) {
        val v = ContentValues(10).apply {
            put("id", id)
            put("kind", kind)
            put("modified", modified)
            put("loc_access", if (locationAccess) 1 else 0)
            put("make", meta.make)
            put("model", meta.model)
            put("software", meta.software)
            put("lat", meta.lat)
            put("lng", meta.lng)
            put("taken", meta.takenAt)
        }
        writableDatabase.insertWithOnConflict("media_meta", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------------------------------------------------------------- prune

    /** Drop raw data older than [before] (epoch ms). */
    fun prune(before: Long) {
        val db = writableDatabase
        val arg = arrayOf(before.toString())
        db.delete("fixes", "t < ?", arg)
        db.delete("activities", "t < ?", arg)
        db.delete("steps", "hour < ?", arg)
    }

    private fun round1(x: Double) = Math.round(x * 10.0) / 10.0
    private fun round2(x: Double) = Math.round(x * 100.0) / 100.0
}
