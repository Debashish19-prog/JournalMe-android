package `in`.journalme.app.tracking

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One background thread for small database writes from the service and receivers. */
object Io {
    val writes: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "journal-io").apply { isDaemon = true } }
}
