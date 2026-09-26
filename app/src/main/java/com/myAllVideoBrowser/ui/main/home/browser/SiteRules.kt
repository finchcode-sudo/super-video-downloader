package com.myAllVideoBrowser.ui.main.home.browser

import com.myAllVideoBrowser.data.local.model.SiteRule

/**
 * hikerView-style per-site sniffing rules - no settings UI, this file IS
 * the UI. To add a rule for a new site, add another SiteRule() entry below.
 *
 * Each rule:
 *  - urlPattern: a regex matched against the page URL. The rule only runs
 *    on pages whose URL matches.
 *  - script: JS that runs inside the page itself (same `document`/`window`
 *    the page's own scripts see). Call reportVideo(url) for every media URL
 *    found; it's fed into the same detection pipeline as everything else
 *    (network sniffing, the generic DOM scanner), so results show up in the
 *    detected-videos list the normal way.
 *
 * Keep scripts defensive - wrap risky lookups in try/catch inside your
 * script too, since one bad site can change its page structure at any time.
 */
object SiteRules {

    val ALL: List<SiteRule> = listOf(
        // Example / template - disabled by default. Copy this block to add
        // a real rule; set enabled = true once the script is written.
        SiteRule(
            name = "Example (template, disabled)",
            urlPattern = "https?://example\\.com/.*",
            script = """
                // Example: find the first .m3u8 URL sitting anywhere in the
                // page's HTML source and report it.
                var match = document.documentElement.outerHTML.match(/https?:[^"'<>\s]+\.m3u8[^"'<>\s]*/);
                if (match) {
                    reportVideo(match[0]);
                }
            """.trimIndent(),
            enabled = false
        )
    )
}
