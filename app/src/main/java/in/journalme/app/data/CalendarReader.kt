package `in`.journalme.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import java.util.Calendar
import java.util.TimeZone

/** The day's calendar events (an optional day-story source). Declined events are skipped. */
object CalendarReader {
    private const val TAG = "CalendarReader"

    fun events(context: Context, from: Long, to: Long): JSArray {
        val out = JSArray()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return out
        }
        // All-day instances are stored in UTC; widen the window by a day each side, then clip after converting.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from - 86_400_000L)
            ContentUris.appendId(it, to + 86_400_000L)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val selection = "${CalendarContract.Instances.VISIBLE} = 1 AND " +
            "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"
        try {
            context.contentResolver.query(uri, projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(3) == 1
                    var start = c.getLong(1)
                    var end = c.getLong(2)
                    if (allDay) {
                        start = utcMidnightToLocal(start)
                        end = utcMidnightToLocal(end)
                    }
                    if (end < from || start > to) continue
                    out.put(
                        JSObject().apply {
                            put("title", c.getString(0)?.takeIf { it.isNotBlank() } ?: "Untitled event")
                            put("start", start)
                            put("end", end)
                            put("allDay", allDay)
                            c.getString(4)?.takeIf { it.isNotBlank() }?.let { put("location", it) }
                            c.getString(5)?.takeIf { it.isNotBlank() }?.let { put("calendar", it) }
                        },
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Calendar query failed", e)
        }
        return out
    }

    /** All-day events start at 00:00 UTC of their date; move that to 00:00 local of the same date. */
    private fun utcMidnightToLocal(t: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = t }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }
}
