package `in`.journalme.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import `in`.journalme.app.Alarms
import `in`.journalme.app.tracking.DayTrackerService
import `in`.journalme.app.tracking.TrackerPrefs

/** After a reboot or an app update: resume day tracking and re-arm alarms (alarms don't survive reboots). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = TrackerPrefs(context)
        // A manual recording can't survive a reboot (the app that started it is gone).
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED) prefs.recording = false
        if (DayTrackerService.shouldRun(context)) DayTrackerService.start(context)
        Alarms.scheduleRecap(context)
        Alarms.scheduleWatchdog(context)
    }
}
