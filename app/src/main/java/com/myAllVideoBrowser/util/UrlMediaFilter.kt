package com.myAllVideoBrowser.util

/**
 * Precision filters borrowed from hikerView's UrlDetector.java. Extension/
 * keyword based video detection (".m3u8" in the URL, etc.) is fast and
 * usually right, but two shapes of URL reliably fool it:
 *
 *  1. A thumbnail/preview file whose name happens to contain a video
 *     extension as a substring, e.g. "cover.mp4.jpg" or "poster.m3u8.png"
 *     (common on sites that generate preview images by appending an image
 *     extension onto the original video's filename).
 *  2. A redirector/proxy/thumbnail-service URL that just carries another
 *     URL as a query parameter, e.g. "https://site.com/go.php?url=https://
 *     example.com/video.mp4" - the outer request itself isn't media, even
 *     though "video.mp4" appears inside it.
 *
 * Run isFalsePositive(url) as an early-reject gate before treating a URL as
 * a detected video/audio candidate.
 */
object UrlMediaFilter {

    // A real video/audio extension followed by an image extension means the
    // media extension was just part of a generated filename, not the actual
    // file type - the trailing extension is the real one.
    private val TRAILING_NON_MEDIA_EXTENSIONS = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".ico",
        ".txt", ".json", ".html", ".htm"
    )

    private val MEDIA_MARKERS = listOf(
        ".mp4", ".m3u8", ".mpd", ".flv", ".avi", ".mov", ".mkv", ".webm",
        ".mp3", ".wav", ".flac", ".m4a", ".aac"
    )

    // Wrapper/redirector patterns: the request carries a URL as a query
    // param rather than being the media itself.
    private val WRAPPED_URL_MARKERS = listOf(
        ".php?url=http", "/?url=http", "?url=http", "&url=http",
        "?src=http", "&src=http", "?redirect=http", "&redirect=http"
    )

    fun isFalsePositive(url: String): Boolean {
        val lower = url.lowercase().substringBefore('#')

        for (wrapped in WRAPPED_URL_MARKERS) {
            if (lower.contains(wrapped)) return true
        }

        val pathOnly = lower.substringBefore('?')
        for (marker in MEDIA_MARKERS) {
            val idx = pathOnly.indexOf(marker)
            if (idx == -1) continue
            val afterMarker = pathOnly.substring(idx + marker.length)
            // If something that looks like a *different*, non-media
            // extension immediately follows the media marker (e.g.
            // "...mp4.jpg"), the media marker was just a substring of the
            // generated filename, not the real file type.
            if (TRAILING_NON_MEDIA_EXTENSIONS.any { afterMarker.startsWith(it) }) {
                return true
            }
        }

        return false
    }
}
