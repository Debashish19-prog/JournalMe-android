package `in`.journalme.app

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.getcapacitor.Bridge
import com.getcapacitor.BridgeActivity
import com.getcapacitor.BridgeWebViewClient
import `in`.journalme.app.media.GalleryFiles
import `in`.journalme.app.media.GalleryImages
import `in`.journalme.app.sync.BackgroundSync

/**
 * Hosts the web app (web/dist) in Capacitor's WebView and registers the
 * native bridge. Deep links (journalme://day/today from the daily recap)
 * reach the web app through @capacitor/app's appUrlOpen / getLaunchUrl.
 */
class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        registerPlugin(JournalNativePlugin::class.java)
        super.onCreate(savedInstanceState)
        bridge.setWebViewClient(JournalWebViewClient(bridge, GalleryImages(this), GalleryFiles(this)))
    }

    /** On screen, the app syncs itself; a background run in progress stops. */
    override fun onStart() {
        super.onStart()
        BackgroundSync.onAppVisible()
    }

    /** Leaving the app: whatever it queued finishes in the background. */
    override fun onStop() {
        super.onStop()
        BackgroundSync.onAppHidden(this)
    }
}

/**
 * Capacitor's client, plus gallery photos decoded to JPEG ([GalleryImages])
 * and byte ranges of gallery originals for streamed uploads ([GalleryFiles]).
 */
private class JournalWebViewClient(
    private val bridge: Bridge,
    private val images: GalleryImages,
    private val files: GalleryFiles,
) : BridgeWebViewClient(bridge) {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (images.handles(request.url, bridge.host)) return images.respond(request.url)
        if (files.handles(request.url, bridge.host)) return files.respond(request.url)
        return super.shouldInterceptRequest(view, request)
    }
}
