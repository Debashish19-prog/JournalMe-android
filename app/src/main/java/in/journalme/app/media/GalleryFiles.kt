package `in`.journalme.app.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream

/**
 * Serves byte ranges of gallery originals to the WebView, so uploads to
 * Telegram stream straight from the gallery:
 *
 *     https://localhost/_jm_file_?uri=content%3A%2F%2Fmedia%2F…&stat=1           {"size":…, "mime":…}
 *     https://localhost/_jm_file_?uri=content%3A%2F%2Fmedia%2F…&start=0&end=…    those bytes
 *
 * Each read seeks straight to `start` in the file (no reading from the top),
 * returns at most [MAX_READ] bytes, and copies nothing to disk: a 4 GB video
 * goes to Telegram a few MB at a time without the app taking any space. The
 * web side is web/src/platform/galleryFiles.ts.
 */
class GalleryFiles(context: Context) {

    companion object {
        const val PATH = "/_jm_file_"
        private const val TAG = "GalleryFiles"
        const val MAX_READ = 8 * 1024 * 1024
    }

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    fun handles(url: Uri, host: String): Boolean = url.host == host && url.path == PATH

    /** Called on a WebView background thread. */
    fun respond(url: Uri): WebResourceResponse {
        val source = url.getQueryParameter("uri")?.let(Uri::parse)
        if (source == null || source.scheme != ContentResolver.SCHEME_CONTENT || source.authority != MediaStore.AUTHORITY) {
            return failure(400, "Bad Request")
        }
        return try {
            if (url.getQueryParameter("stat") != null) stat(source) else read(source, url)
        } catch (e: Exception) {
            // Deleted from the gallery, or access revoked.
            Log.w(TAG, "Couldn't read $source: ${e.message}")
            failure(404, "Not Found")
        }
    }

    private fun stat(source: Uri): WebResourceResponse {
        val size = resolver.openFileDescriptor(source, "r")?.use { it.statSize } ?: return failure(404, "Not Found")
        val json = JSONObject().put("size", size).put("mime", resolver.getType(source) ?: JSONObject.NULL)
        return ok("application/json", json.toString().toByteArray())
    }

    private fun read(source: Uri, url: Uri): WebResourceResponse {
        val start = url.getQueryParameter("start")?.toLongOrNull() ?: return failure(400, "Bad Request")
        val end = url.getQueryParameter("end")?.toLongOrNull() ?: return failure(400, "Bad Request")
        if (start < 0 || end < start || end - start > MAX_READ) return failure(416, "Range Not Satisfiable")
        val bytes = resolver.openFileDescriptor(source, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                val channel = input.channel
                if (end > channel.size()) return failure(416, "Range Not Satisfiable")
                channel.position(start)
                val out = ByteArray((end - start).toInt())
                var at = 0
                while (at < out.size) {
                    val n = input.read(out, at, out.size - at)
                    if (n < 0) break
                    at += n
                }
                if (at < out.size) out.copyOf(at) else out
            }
        } ?: return failure(404, "Not Found")
        return ok("application/octet-stream", bytes)
    }

    private fun ok(mime: String, bytes: ByteArray) =
        WebResourceResponse(mime, null, 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes))

    private fun failure(status: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
