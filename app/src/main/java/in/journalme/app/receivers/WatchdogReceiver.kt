package `in`.journalme.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import `in`.journalme.app.tracking.DayTrackerService

/**
 * Restarts the tracker if it was killed. Background service starts are only
 * allowed when the app is exempt from battery optimisation (the setup screen
 * asks for that); otherwise this is a no-op until the app is next opened.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (DayTrackerService.isRunning || !DayTrackerService.shouldRun(context)) return
        DayTrackerService.start(context)
    }
}
