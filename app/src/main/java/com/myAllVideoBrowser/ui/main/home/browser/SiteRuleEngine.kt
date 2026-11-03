package com.myAllVideoBrowser.ui.main.home.browser

import com.myAllVideoBrowser.data.local.model.SiteRule
import com.myAllVideoBrowser.util.AppLogger

/**
 * hikerView-style rule engine: instead of the generic, best-effort media
 * scanner (see injectMediaScanner in CustomWebViewClient.kt) trying to guess
 * where a video URL might be on ANY site, a SiteRule lets the user write a
 * small, site-specific JS snippet that knows exactly where to look on ONE
 * site (a particular JSON key, a specific DOM element, a custom decoding
 * step, etc). This trades generality for precision.
 *
 * The rule's JS runs directly inside the page's own WebView context (via
 * evaluateJavascript), so it has full access to `document`/`window` exactly
 * as the page's own scripts do, and reports results back through the
 * existing AndroidBridge.reportMediaUrl - the same pipeline the generic
 * scanner and the network sniffer already feed into, so anything a rule
 * finds shows up in the detected-videos list the same way.
 */
object SiteRuleEngine {

    /**
     * Returns all enabled rules whose urlPattern matches the given page URL.
     * More than one rule can match (e.g. a broad rule + a more specific
     * one) - all matching rules are run.
     */
    fun findMatchingRules(rules: List<SiteRule>, pageUrl: String): List<SiteRule> {
        return rules.filter { rule ->
            if (!rule.enabled || rule.urlPattern.isBlank() || rule.script.isBlank()) {
                return@filter false
            }
            try {
                Regex(rule.urlPattern).containsMatchIn(pageUrl)
            } catch (e: Throwable) {
                AppLogger.e("SiteRuleEngine: invalid urlPattern in rule '${rule.name}': ${e.message}")
                false
            }
        }
    }

    /**
     * Wraps the user's rule script so that:
     *  - it can call a simple `reportVideo(url)` helper instead of needing
     *    to know about the AndroidBridge JS interface directly,
     *  - a mistake or exception in one rule can't crash the page or block
     *    other rules from running,
     *  - errors are logged (via console.error, visible in
     *    chrome://inspect / WebView remote debugging) rather than failing
     *    silently, which matters since these scripts are user-edited.
     */
    fun buildInjectionScript(rule: SiteRule): String {
        return """
            (function() {
                function reportVideo(url) {
                    try {
                        if (!url) return;
                        if (window.AndroidBridge && window.AndroidBridge.reportMediaUrl) {
                            window.AndroidBridge.reportMediaUrl(url);
                        }
                    } catch (e) {}
                }
                try {
                    ${rule.script}
                } catch (e) {
                    console.error('[SiteRule "${rule.name.replace("\"", "'")}"] error: ' + e.message);
                }
            })();
        """.trimIndent()
    }
}
