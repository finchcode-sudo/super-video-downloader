package com.myAllVideoBrowser.data.local.model

import java.util.UUID

/**
 * A user-defined rule for extracting media URLs from a specific site, in the
 * spirit of hikerView's JS rule engine: instead of passively waiting for a
 * network request or guessing with a generic scanner, the user writes a
 * small JS snippet tailored to one site's page structure, which runs inside
 * the page's own WebView context (so it has full access to `document`,
 * `window`, any JSON blobs the site embeds, etc.) and reports whatever
 * media URLs it finds back to the app.
 *
 * The script has access to a `reportVideo(url)` helper function (see
 * SiteRuleEngine.buildInjectionScript) - it does not need to know about the
 * underlying JS bridge.
 */
data class SiteRule(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    // Regex matched against the current page URL to decide whether this
    // rule applies, e.g. "https?://(www\\.)?example\\.com/.*"
    var urlPattern: String = "",
    // JS source. Typically calls reportVideo(url) one or more times.
    // Example:
    //   var m = document.body.innerHTML.match(/https?:[^"']+\.m3u8/);
    //   if (m) reportVideo(m[0]);
    var script: String = "",
    var enabled: Boolean = true
)
