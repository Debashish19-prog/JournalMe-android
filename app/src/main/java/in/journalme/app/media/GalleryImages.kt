package `in`.journalme.app.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Serves gallery photos to the WebView as JPEG, sized for where they're shown:
 *
 *     https://localhost/_jm_image_?uri=content%3A%2F%2Fmedia%2F…&px=1024
 *
 * For a video, `t` (ms) asks for the frame at that time instead of the cover
 * frame; the AI journal writer uses it to look inside a clip.
 *
 * Android's WebView can't decode HEIC/HEIF, the default format of Samsung and
 * many other cameras, so pointing an <img> at the original shows a broken
 * image. Decoding here also keeps a 50 MP original from being loaded whole into
 * the WebView for a small tile. The web side builds these URLs with
 * `galleryImageUrl()` in web/src/platform/index.ts.
 */
class GalleryImages(context: Context) {

    companion object {
        const val PATH = "/_jm_image_"
        private const val TAG = "GalleryImages"

        /** Up to this size, MediaProvider's cached thumbnail is sharp enough and much faster than the original. */
        private const val THUMBNAIL_PX = 512
        private const val MAX_PX = 4096
    }

    private val app: Context = context.applicationContext
    private val resolver: ContentResolver = app.contentResolver

    /** Encoded images by "uri@px", so scrolling back and paging the viewer don't decode again. */
    private val cache = object : LruCache<String, Encoded>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Encoded) = value.bytes.size
    }

    private class Encoded(val bytes: ByteArray, val mime: String)

    /** True for this app's own image URLs; anything else goes to Capacitor's local server. */
    fun handles(url: Uri, host: String): Boolean = url.host == host && url.path == PATH

    /** Called on a WebView background thread, so decoding here doesn't block the UI. */
    fun respond(url: Uri): WebResourceResponse {
        val source = url.getQueryParameter("uri")?.let(Uri::parse)
        if (source == null || source.scheme != ContentResolver.SCHEME_CONTENT || source.authority != MediaStore.AUTHORITY) {
            return failure(400, "Bad Request")
        }
        val px = (url.getQueryParameter("px")?.toIntOrNull() ?: 1024).coerceIn(64, MAX_PX)
        val frameAt = url.getQueryParameter("t")?.toLongOrNull()?.coerceAtLeast(0L)?.takeIf { isVideo(source) }
        val key = "$source@$px" + (frameAt?.let { "@$it" } ?: "")
        val image = cache.get(key) ?: try {
            encode(if (frameAt != null) frame(source, px, frameAt) else decode(source, px)).also { cache.put(key, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't decode $source at $px px: ${e.message}")
            return failure(404, "Not Found")
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory decoding $source at $px px")
            return failure(503, "Service Unavailable")
        }
        return WebResourceResponse(
            image.mime,
            null,
            200,
            "OK",
            mapOf("Cache-Control" to "private, max-age=86400"),
            ByteArrayInputStream(image.bytes),
        )
    }

    private fun decode(uri: Uri, px: Int): Bitmap {
        // Small sizes, and videos at any size: MediaProvider's thumbnail (oriented, cached).
        if (px <= THUMBNAIL_PX || isVideo(uri)) return scaleDown(resolver.loadThumbnail(uri, Size(px, px), null), px)
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                // ImageDecoder applies the EXIF orientation itself; the size it reports is already oriented.
                val w = info.size.width
                val h = info.size.height
                val longest = max(w, h)
                if (longest > px) {
                    val f = px.toDouble() / longest
                    decoder.setTargetSize(max(1, (w * f).roundToInt()), max(1, (h * f).roundToInt()))
                }
                // Software pixels: hardware bitmaps can't be re-encoded.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            // A format ImageDecoder doesn't know (some RAW files): the thumbnail is better than nothing.
            Log.w(TAG, "Full decode failed for $uri, using thumbnail: ${e.message}")
            scaleDown(resolver.loadThumbnail(uri, Size(px, px), null), px)
        }
    }

    /** The frame nearest `atMs` (a sync frame, so it's fast), at most `px` on its longest side. */
    private fun frame(uri: Uri, px: Int, atMs: Long): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(app, uri)
            // A square bound keeps the aspect ratio whichever way the video is rotated.
            val bitmap = retriever.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px, px)
            if (bitmap != null) return scaleDown(bitmap, px)
        } finally {
            retriever.release()
        }
        return scaleDown(resolver.loadThumbnail(uri, Size(px, px), null), px)
    }

    private fun encode(bitmap: Bitmap): Encoded {
        // PNG only where there's transparency (stickers, some screenshots); photos are JPEG.
        val png = bitmap.hasAlpha()
        val out = ByteArrayOutputStream()
        bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return Encoded(out.toByteArray(), if (png) "image/png" else "image/jpeg")
    }

    private fun isVideo(uri: Uri) = uri.pathSegments.contains("video")

    private fun scaleDown(b: Bitmap, px: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= px) return b
        val f = px.toDouble() / longest
        val scaled = Bitmap.createScaledBitmap(b, max(1, (b.width * f).roundToInt()), max(1, (b.height * f).roundToInt()), true)
        if (scaled !== b) b.recycle()
        return scaled
    }

    private fun failure(status: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
