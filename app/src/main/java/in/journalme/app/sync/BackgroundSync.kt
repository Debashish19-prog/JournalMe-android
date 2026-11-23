package `in`.journalme.app.sync

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.getcapacitor.JSObject
import `in`.journalme.app.NativeQueries
import `in`.journalme.app.media.GalleryFiles
import `in`.journalme.app.media.GalleryImages
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Syncs with Telegram while the app is closed: no screen, nobody in the loop.
 *
 * A run opens the web app's background page (web/worker.html, built into the
 * APK's assets) in a WebView with no window, at the same origin as the app
 * (https://localhost), so it shares the app's storage and Telegram login. The
 * page processes days and pushes/pulls exactly like the open app (see
 * web/src/worker/main.ts), reading native data through [Bridge] and gallery
 * originals through [GalleryFiles], then reports back and is closed.
 *
 * Runs are started by the day tracker (it keeps the process alive all day):
 *  - every [EVERY_MS], or sooner after a failed run ([RETRY_MS]);
 *  - a few seconds after you leave the app, to finish what it queued;
 *  - when Wi-Fi comes back while photos/videos are waiting for it.
 * Never while the app is on screen: it syncs itself then, and a run in
 * progress stops when you open it.
 */
object BackgroundSync {
    private const val TAG = "BackgroundSync"
    private const val ORIGIN = "https://localhost"
    private const val HOST = "localhost"
    private const val EVERY_MS = 30 * 60_000L
    private const val RETRY_MS = 10 * 60_000L
    private const val SIGNED_OUT_MS = 6 * 3_600_000L
    /** Long enough for gigabytes of video on a slow connection, short enough to never get stuck. */
    private const val MAX_RUN_MS = 4 * 3_600_000L

    private val main = Handler(Looper.getMainLooper())

    /** The app's screens are showing (set by MainActivity). */
    @Volatile
    var appVisible = false
        private set

    private var run: Run? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    // ------------------------------------------------------------ triggers

    /** The app came to the front: it syncs itself, so a background run stops. */
    fun onAppVisible() {
        appVisible = true
        main.post { run?.finish(null, "the app was opened") }
    }

    /** You left the app: finish what it queued, in the background. */
    fun onAppHidden(context: Context) {
        appVisible = false
        request(context, "left the app", delayMs = 5_000L)
    }

    /** Called every minute by the day tracker; starts a run when one is due. */
    fun onTick(context: Context) {
        if (due(context)) request(context, "scheduled")
    }

    /** Watch for unmetered networks while the tracker runs (photos/videos may be waiting for Wi-Fi). */
    fun attach(context: Context) {
        if (wifiCallback != null) return
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (prefs(app).getInt("waitingForWifi", 0) > 0) request(app, "Wi-Fi is back", delayMs = 10_000L)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            wifiCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't watch for Wi-Fi: ${e.message}")
        }
    }

    fun detach(context: Context) {
        val callback = wifiCallback ?: return
        wifiCallback = null
        try {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterNetworkCallback: ${e.message}")
        }
    }

    /** For the Day Tracking page (shape: BackgroundSyncStatus in journalNative.ts). */
    fun status(context: Context): JSObject {
        val p = prefs(context)
        return JSObject().apply {
            put("running", run != null)
            if (p.contains("lastRunAt")) put("lastRunAt", p.getLong("lastRunAt", 0L))
            if (p.contains("lastOk")) put("lastOk", p.getBoolean("lastOk", false))
            p.getString("lastError", null)?.let { put("lastError", it) }
            put("pending", p.getInt("pending", 0))
            put("waitingForWifi", p.getInt("waitingForWifi", 0))
        }
    }

    // ------------------------------------------------------------- running

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("background_sync", Context.MODE_PRIVATE)

    private fun due(context: Context): Boolean {
        if (appVisible || run != null) return false
        val p = prefs(context)
        val wait = when {
            p.getBoolean("signedOut", false) -> SIGNED_OUT_MS
            !p.getBoolean("lastOk", true) || p.getInt("pending", 0) > 0 -> RETRY_MS
            else -> EVERY_MS
        }
        return System.currentTimeMillis() - p.getLong("lastStartAt", 0L) >= wait
    }

    private fun request(context: Context, reason: String, delayMs: Long = 0L) {
        val app = context.applicationContext
        main.postDelayed({
            if (appVisible || run != null) return@postDelayed
            Log.i(TAG, "Starting a background sync: $reason")
            prefs(app).edit().putLong("lastStartAt", System.currentTimeMillis()).apply()
            run = try {
                Run(app).also { it.begin() }
            } catch (e: Exception) {
                // No WebView on this device (being updated, disabled…): try again later.
                Log.w(TAG, "Couldn't open the background page: ${e.message}")
                saveResult(app, ok = false, error = "Couldn't start: ${e.message}", pending = null, waiting = null, signedOut = false)
                null
            }
        }, delayMs)
    }

    private fun saveResult(context: Context, ok: Boolean, error: String?, pending: Int?, waiting: Int?, signedOut: Boolean) {
        prefs(context).edit().apply {
            putLong("lastRunAt", System.currentTimeMillis())
            putBoolean("lastOk", ok)
            if (error != null) putString("lastError", error) else remove("lastError")
            if (pending != null) putInt("pending", pending)
            if (waiting != null) putInt("waitingForWifi", waiting)
            putBoolean("signedOut", signedOut)
        }.apply()
    }

    /** One background run: a windowless WebView on the main thread, closed by [finish]. */
    private class Run(private val app: Context) {
        private var webView: WebView? = null
        private val timeout = Runnable { finish(null, "took too long") }

        @SuppressLint("SetJavaScriptEnabled") // our own bundled page
        fun begin() {
            val wv = WebView(app)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            // Keep the page's renderer from being killed just because nothing is on screen.
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            wv.webViewClient = WorkerClient(app) { finish(null, "the page's renderer stopped") }
            wv.addJavascriptInterface(Bridge(app, this), "JMWorker")
            webView = wv
            main.postDelayed(timeout, MAX_RUN_MS)
            wv.loadUrl("$ORIGIN/worker.html")
        }

        /** Close the page; `resultJson` is the worker's report, or null when stopped early. */
        fun finish(resultJson: String?, stoppedBecause: String?) {
            if (run !== this) return
            run = null
            main.removeCallbacks(timeout)
            webView?.let {
                it.stopLoading()
                it.removeJavascriptInterface("JMWorker")
                it.destroy()
            }
            webView = null
            if (resultJson == null) {
                Log.i(TAG, "Background sync stopped: $stoppedBecause")
                if (stoppedBecause != "the app was opened") {
                    saveResult(app, ok = false, error = "Stopped: $stoppedBecause", pending = null, waiting = null, signedOut = false)
                }
                return
            }
            val r = try {
                JSONObject(resultJson)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", "Bad report from the page")
            }
            Log.i(TAG, "Background sync finished: $resultJson")
            saveResult(
                app,
                ok = r.optBoolean("ok", false),
                error = r.optString("error").takeIf { it.isNotEmpty() },
                pending = r.optInt("pending", 0).coerceAtLeast(0),
                waiting = r.optInt("waitingForWifi", 0),
                signedOut = r.optBoolean("signedOut", false),
            )
        }
    }

    /**
     * What the page can ask the phone for: the read-only half of the
     * JournalNative plugin (web/src/platform/worker.ts). Called on a WebView
     * binder thread; answers synchronously as JSON.
     */
    private class Bridge(private val app: Context, private val owner: Run) {
        @JavascriptInterface
        fun call(method: String, argsJson: String): String = try {
            val args = JSONObject(argsJson.ifEmpty { "{}" })
            fun range() = args.getLong("from") to args.getLong("to")
            val data: JSONObject = when (method) {
                "getStatus" -> NativeQueries.status(app)
                "checkPermissions" -> NativeQueries.permissions(app)
                "getFixes" -> range().let { (f, t) -> NativeQueries.fixes(app, f, t) }
                "getActivities" -> range().let { (f, t) -> NativeQueries.activities(app, f, t) }
                "getSteps" -> range().let { (f, t) -> NativeQueries.steps(app, f, t) }
                "queryMedia" -> range().let { (f, t) -> NativeQueries.media(app, f, t) }
                "getThumbnails" -> NativeQueries.thumbnails(app, strings(args.optJSONArray("uris")), args.optInt("size", 320))
                "getCalendarEvents" -> range().let { (f, t) -> NativeQueries.calendar(app, f, t) }
                "getNetwork" -> NativeQueries.network(app)
                "getBackgroundSync" -> status(app)
                else -> throw IllegalArgumentException("$method isn't available in the background")
            }
            JSONObject().put("ok", true).put("data", data).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e.toString()).toString()
        }

        @JavascriptInterface
        fun progress(text: String) {
            Log.d(TAG, text)
        }

        @JavascriptInterface
        fun done(resultJson: String) {
            main.post { owner.finish(resultJson, null) }
        }

        private fun strings(a: JSONArray?): List<String> =
            if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    }

    /**
     * Serves the app's own files to the page, as Capacitor does for the app:
     * the built web app from assets/public, gallery photos ([GalleryImages])
     * and gallery originals ([GalleryFiles]). Everything else (Telegram, place
     * names, weather) goes to the network as usual.
     */
    private class WorkerClient(private val app: Context, private val onGone: () -> Unit) : WebViewClient() {
        private val images = GalleryImages(app)
        private val files = GalleryFiles(app)

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url: Uri = request.url
            if (url.scheme != "https" || url.host != HOST) return null
            if (images.handles(url, HOST)) return images.respond(url)
            if (files.handles(url, HOST)) return files.respond(url)
            val path = (url.path ?: "/").trimStart('/').ifEmpty { "index.html" }
            if (path.contains("..")) return notFound()
            return try {
                val mime = mimeFor(path)
                val text = mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml"
                WebResourceResponse(mime, if (text) "utf-8" else null, app.assets.open("public/$path"))
            } catch (e: IOException) {
                notFound()
            }
        }

        /** The renderer crashed or was killed: end the run instead of taking the app down with it. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            main.post(onGone)
            return true
        }

        private fun notFound() =
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

        private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json", "map" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
    }
}
