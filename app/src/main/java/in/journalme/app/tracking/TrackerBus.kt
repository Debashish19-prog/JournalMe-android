package `in`.journalme.app.tracking

import `in`.journalme.app.data.NativeDb

/**
 * In-process link between the tracker service / receivers and the Capacitor
 * plugin. The plugin registers while the app's WebView is alive; the service
 * works the same whether or not anyone is listening.
 */
object TrackerBus {
    interface Listener {
        fun onFix(fix: NativeDb.Fix)
        fun onStatusChanged()
    }

    @Volatile var listener: Listener? = null

    fun fix(fix: NativeDb.Fix) {
        listener?.onFix(fix)
    }

    fun statusChanged() {
        listener?.onStatusChanged()
    }
}
