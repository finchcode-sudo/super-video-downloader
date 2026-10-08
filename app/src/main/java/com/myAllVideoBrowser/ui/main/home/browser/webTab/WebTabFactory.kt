package com.myAllVideoBrowser.ui.main.home.browser.webTab

import android.util.Patterns
import com.myAllVideoBrowser.ui.main.home.browser.BrowserViewModel
import com.myAllVideoBrowser.ui.main.settings.SearchEngine

class WebTabFactory {
    companion object {
        fun createWebTabFromInput(input: String): WebTab {
            if (input.isNotEmpty()) {
                return if (input.startsWith("http://") || input.startsWith("https://")) {
                    WebTab(input, null, null, emptyMap())
                } else if (Patterns.WEB_URL.matcher(input).matches()) {
                    WebTab("https://$input", null, null, emptyMap())
                } else {
                    val searchUrlTemplate =
                        BrowserViewModel.instance?.settingsModel?.selectedSearchEngine?.get()?.searchUrl
                            ?: SearchEngine.DUCKDUCKGO.searchUrl
                    WebTab(
                        String.format(searchUrlTemplate, input),
                        null,
                        null,
                        emptyMap())
                }
            }

            return WebTab.HOME_TAB
        }
    }
}
