package `in`.journalme.app.media

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import `in`.journalme.app.data.NativeDb
import java.io.ByteArrayOutputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Reads the day's photos and videos from MediaStore: everything on the phone,
 * not just the camera roll (WhatsApp, Telegram, downloads, Quick Share…).
 *
 * For each item it returns the raw signals the web layer uses to tell where it
 * came from (RELATIVE_PATH, OWNER_PACKAGE_NAME, file name, EXIF make/model) and
 * where it was taken (EXIF GPS / video ISO-6709 location). EXIF is read once per
 * file version and cached in [NativeDb].
 */
class MediaScanner(private val context: Context) {

    companion object {
        private const val TAG = "MediaScanner"
        private const val KIND_PHOTO = "photo"
        private const val KIND_VIDEO = "video"
    }

    private val resolver: ContentResolver = context.contentResolver
    private val db = NativeDb.get(context)

    private val imagesUri: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val videoUri: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** Photos and videos with from ≤ takenAt ≤ to, oldest first. */
    fun query(from: Long, to: Long): JSArray {
        val locationAccess = hasPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)
        val items = mutableListOf<Pair<Long, JSObject>>()
        scan(KIND_PHOTO, imagesUri, from, to, locationAccess, items)
        scan(KIND_VIDEO, videoUri, from, to, locationAccess, items)
        items.sortBy { it.first }
        return JSArray().apply { items.forEach { put(it.second) } }
    }

    /** How many photos/videos fall in [from, to] (for the daily recap; no EXIF reads). */
    fun countBetween(from: Long, to: Long): Int {
        var n = 0
        for (uri in listOf(imagesUri, videoUri)) {
            try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), selection(), selectionArgs(from, to), null)
                    ?.use { n += it.count }
            } catch (e: Exception) {
                Log.w(TAG, "count failed", e)
            }
        }
        return n
    }

    /** JPEG data URLs keyed by uri; only MediaStore content:// uris are accepted. */
    fun thumbnails(uris: List<String>, size: Int): JSObject {
        val px = size.coerceIn(32, 1024)
        val out = JSObject()
        for (u in uris) {
            val uri = Uri.parse(u)
            if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != MediaStore.AUTHORITY) continue
            try {
                val bitmap = resolver.loadThumbnail(uri, Size(px, px), null)
                val scaled = scaleDown(bitmap, px)
                val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
                out.put(u, "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "No thumbnail for $u: ${e.message}")
            }
        }
        return out
    }

    // ---------------------------------------------------------------------

    private fun selection(): String {
        val taken = MediaStore.MediaColumns.DATE_TAKEN
        val added = MediaStore.MediaColumns.DATE_ADDED
        return "($taken BETWEEN ? AND ?) OR (($taken IS NULL OR $taken = 0) AND $added BETWEEN ? AND ?)"
    }

    // DATE_TAKEN is in ms, DATE_ADDED in seconds.
    private fun selectionArgs(from: Long, to: Long) =
        arrayOf(from.toString(), to.toString(), (from / 1000).toString(), (to / 1000).toString())

    private fun scan(
        kind: String,
        collection: Uri,
        from: Long,
        to: Long,
        locationAccess: Boolean,
        out: MutableList<Pair<Long, JSObject>>,
    ) {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.ORIENTATION)
            add(MediaStore.MediaColumns.DURATION)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.MediaColumns.IS_FAVORITE)
        }.toTypedArray()

        val cursor: Cursor = try {
            resolver.query(
                collection,
                projection,
                selection(),
                selectionArgs(from, to),
                "${MediaStore.MediaColumns.DATE_TAKEN} ASC, ${MediaStore.MediaColumns.DATE_ADDED} ASC",
            ) ?: return
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed for $kind", e)
            return
        }

        cursor.use {
            val col = { name: String -> it.getColumnIndex(name) }
            val iId = col(MediaStore.MediaColumns._ID)
            val iName = col(MediaStore.MediaColumns.DISPLAY_NAME)
            val iMime = col(MediaStore.MediaColumns.MIME_TYPE)
            val iSize = col(MediaStore.MediaColumns.SIZE)
            val iW = col(MediaStore.MediaColumns.WIDTH)
            val iH = col(MediaStore.MediaColumns.HEIGHT)
            val iOrientation = col(MediaStore.MediaColumns.ORIENTATION)
            val iDuration = col(MediaStore.MediaColumns.DURATION)
            val iTaken = col(MediaStore.MediaColumns.DATE_TAKEN)
            val iAdded = col(MediaStore.MediaColumns.DATE_ADDED)
            val iModified = col(MediaStore.MediaColumns.DATE_MODIFIED)
            val iPath = col(MediaStore.MediaColumns.RELATIVE_PATH)
            val iBucket = col(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val iOwner = col(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            val iFavorite = col("is_favorite")

            while (it.moveToNext()) {
                val id = it.getLong(iId)
                val uri = ContentUris.withAppendedId(collection, id)
                val mime = it.str(iMime) ?: if (kind == KIND_PHOTO) "image/*" else "video/*"
                val addedAt = it.long(iAdded)?.times(1000) ?: 0L
                val modified = it.long(iModified) ?: 0L
                val dateTaken = it.long(iTaken)?.takeIf { t -> t > 0 }

                val meta = metadata(kind, id, modified, locationAccess, uri)
                val takenAt = dateTaken ?: meta?.takenAt ?: addedAt
                if (takenAt < from || takenAt > to) continue

                val item = JSObject()
                item.put("id", id)
                item.put("uri", uri.toString())
                item.put("kind", kind)
                item.put("mime", mime)
                item.put("name", it.str(iName) ?: "$kind-$id")
                item.put("size", it.long(iSize) ?: 0L)
                it.long(iW)?.takeIf { w -> w > 0 }?.let { w -> item.put("width", w) }
                it.long(iH)?.takeIf { h -> h > 0 }?.let { h -> item.put("height", h) }
                if (kind == KIND_VIDEO) it.long(iDuration)?.takeIf { d -> d > 0 }?.let { d -> item.put("durationMs", d) }
                if (kind == KIND_PHOTO) it.long(iOrientation)?.let { o -> item.put("orientation", o) }
                item.put("takenAt", takenAt)
                item.put("addedAt", addedAt)
                it.str(iPath)?.let { p -> item.put("path", p) }
                it.str(iBucket)?.let { b -> item.put("bucket", b) }
                it.str(iOwner)?.let { o -> item.put("owner", o) }
                if (iFavorite >= 0 && !it.isNull(iFavorite)) item.put("isFavorite", it.getInt(iFavorite) == 1)
                meta?.make?.let { m -> item.put("make", m) }
                meta?.model?.let { m -> item.put("model", m) }
                meta?.software?.let { s -> item.put("software", s) }
                if (meta?.lat != null && meta.lng != null) {
                    item.put("lat", meta.lat)
                    item.put("lng", meta.lng)
                }
                out += takenAt to item
            }
        }
    }

    /** EXIF (photos) or container metadata (videos), from cache when the file hasn't changed. */
    private fun metadata(kind: String, id: Long, modified: Long, locationAccess: Boolean, uri: Uri): NativeDb.MediaMeta? {
        db.mediaMeta(id, kind, modified, locationAccess)?.let { return it }
        val meta = try {
            if (kind == KIND_PHOTO) readExif(uri, locationAccess) else readVideo(uri, locationAccess)
        } catch (e: Exception) {
            Log.w(TAG, "No metadata for $uri: ${e.message}")
            null
        } ?: NativeDb.MediaMeta(null, null, null, null, null, null)
        try {
            db.putMediaMeta(id, kind, modified, locationAccess, meta)
        } catch (e: Exception) {
            Log.w(TAG, "Could not cache metadata", e)
        }
        return meta
    }

    /** Un-redacted original (real GPS) needs ACCESS_MEDIA_LOCATION; without it Android zeroes the location. */
    private fun source(uri: Uri, locationAccess: Boolean): Uri =
        if (locationAccess) MediaStore.setRequireOriginal(uri) else uri

    private fun readExif(uri: Uri, locationAccess: Boolean): NativeDb.MediaMeta? {
        val exif = resolver.openInputStream(source(uri, locationAccess))?.use { ExifInterface(it) } ?: return null
        val latLng = if (locationAccess) exif.latLong?.takeIf { validLatLng(it[0], it[1]) } else null
        return NativeDb.MediaMeta(
            make = exif.getAttribute(ExifInterface.TAG_MAKE).clean(),
            model = exif.getAttribute(ExifInterface.TAG_MODEL).clean(),
            software = exif.getAttribute(ExifInterface.TAG_SOFTWARE).clean(),
            lat = latLng?.get(0),
            lng = latLng?.get(1),
            takenAt = exifTime(
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            ),
        )
    }

    private fun readVideo(uri: Uri, locationAccess: Boolean): NativeDb.MediaMeta? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, source(uri, locationAccess))
            val loc = if (locationAccess) parseIso6709(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)) else null
            return NativeDb.MediaMeta(
                make = null,
                model = null,
                software = null,
                lat = loc?.first,
                lng = loc?.second,
                takenAt = videoTime(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)),
            )
        } finally {
            r.release()
        }
    }

    // ------------------------------------------------------------- helpers

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun scaleDown(b: Bitmap, px: Int): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= px * 1.25) return b
        val f = px.toDouble() / longest
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * f).toInt()), maxOf(1, (b.height * f).toInt()), true)
    }

    private fun Cursor.str(i: Int): String? = if (i < 0 || isNull(i)) null else getString(i)?.takeIf { it.isNotBlank() }

    private fun Cursor.long(i: Int): Long? = if (i < 0 || isNull(i)) null else getLong(i)

    private fun String?.clean(): String? = this?.trim()?.trim('\u0000')?.takeIf { it.isNotEmpty() }
}

/** Valid, non-(0,0) coordinates. */
internal fun validLatLng(lat: Double, lng: Double): Boolean =
    lat in -90.0..90.0 && lng in -180.0..180.0 && !(kotlin.math.abs(lat) < 1e-6 && kotlin.math.abs(lng) < 1e-6)

/** "+12.9716+077.5946/" or "+12.9716+077.5946+920.000/" → (lat, lng). */
internal fun parseIso6709(s: String?): Pair<Double, Double>? {
    if (s.isNullOrBlank()) return null
    val m = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(s.trim()) ?: return null
    val lat = m.groupValues[1].toDoubleOrNull() ?: return null
    val lng = m.groupValues[2].toDoubleOrNull() ?: return null
    return if (validLatLng(lat, lng)) lat to lng else null
}

/** EXIF "yyyy:MM:dd HH:mm:ss" (local time unless an offset like "+05:30" is given) → epoch ms. */
internal fun exifTime(value: String?, offset: String?): Long? {
    if (value.isNullOrBlank() || value.startsWith("0000")) return null
    return try {
        val f = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        if (!offset.isNullOrBlank() && Regex("""^[+-]\d{2}:\d{2}$""").matches(offset.trim())) {
            f.timeZone = TimeZone.getTimeZone("GMT${offset.trim()}")
        }
        f.parse(value.trim())?.time
    } catch (e: ParseException) {
        null
    }
}

/** MediaMetadataRetriever date "20261121T101530.000Z" (UTC) → epoch ms; ignores the 1904/1970 placeholders. */
internal fun videoTime(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    for (pattern in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss")) {
        try {
            val f = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val t = f.parse(value.trim())?.time ?: continue
            return if (t > 946_684_800_000L) t else null // before 2000 = placeholder
        } catch (_: ParseException) {
            // try the next pattern
        }
    }
    return null
}
