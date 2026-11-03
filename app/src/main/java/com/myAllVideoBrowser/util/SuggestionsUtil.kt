package com.myAllVideoBrowser.util

import com.myAllVideoBrowser.data.local.model.Suggestion
import com.myAllVideoBrowser.ui.main.settings.SearchEngine
import io.reactivex.rxjava3.core.Observable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class SuggestionsUtils {
    companion object {
        fun getSuggestions(
            okHttpClient: OkHttpClient,
            input: String,
            engine: SearchEngine = SearchEngine.DUCKDUCKGO
        ): Observable<List<Suggestion>> {
            return Observable.create { emitter ->
                val suggestUrlTemplate = engine.suggestUrl
                if (suggestUrlTemplate == null || input.isBlank()) {
                    emitter.onNext(emptyList())
                    emitter.onComplete()
                    return@create
                }

                val result: ArrayList<Suggestion> = ArrayList()
                try {
                    val request = Request.Builder()
                        .url(String.format(suggestUrlTemplate, input))
                        .build()
                    val response = okHttpClient.newCall(request).execute()
                        .use { response -> response.body.string() }

                    val jsn = JSONArray(response)
                    // DuckDuckGo shape: [{"phrase": "..."}, ...]
                    // Google/Bing shape: ["query", ["suggestion1", "suggestion2", ...]]
                    if (jsn.length() > 1 && jsn.opt(1) is JSONArray) {
                        val suggestionsArray = jsn.getJSONArray(1)
                        for (i in 0 until suggestionsArray.length()) {
                            try {
                                result.add(Suggestion(content = suggestionsArray.getString(i)))
                            } catch (_: Throwable) {
                            }
                        }
                    } else {
                        for (i in 0 until jsn.length()) {
                            try {
                                val phraseObj = JSONObject(jsn.get(i).toString())
                                val phrase = phraseObj.get("phrase").toString()
                                result.add(Suggestion(content = phrase))
                            } catch (_: Throwable) {
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore network/parsing failures - suggestions are best-effort
                }
                emitter.onNext(result)
                emitter.onComplete()
            }
        }
    }
}
