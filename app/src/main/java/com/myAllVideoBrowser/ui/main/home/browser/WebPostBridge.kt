package com.myAllVideoBrowser.ui.main.home.browser

class WebPostBridge(
    private val onIntercept: (url: String, body: String) -> Boolean,
    private val onMediaUrlFound: (url: String) -> Unit = {}
) {
    companion object {
        const val BRIDGE_NAME = "AndroidBridge"
    }
    @android.webkit.JavascriptInterface
    fun shouldInterceptPost(url: String, body: String): Boolean {
        // This runs on a background thread (JavaBridge thread)
        // Return true to BLOCK the request
        // Return false to let the browser CONTINUE the request
        return onIntercept(url, body)
    }

    @android.webkit.JavascriptInterface
    fun reportMediaUrl(url: String) {
        // Called by the injected media-scanner script (see
        // injectMediaScanner) whenever it finds an .m3u8/.mpd URL sitting in
        // the page or assigned to a <video> element - possibly before that
        // URL has ever been requested over the network. Runs on the
        // JavaBridge thread, same as shouldInterceptPost above.
        onMediaUrlFound(url)
    }
}
