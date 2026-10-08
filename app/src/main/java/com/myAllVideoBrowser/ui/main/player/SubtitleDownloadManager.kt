package com.myAllVideoBrowser.ui.main.player

import android.content.Context
import android.util.Log
import com.myAllVideoBrowser.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class TmdbMediaResult(
    val id: Int,
    val mediaType: String,
    val title: String,
    val releaseYear: String?,
    val poster: String?
)

data class SubtitleInfo(
    val url: String,
    val language: String?,
    val languageDisplay: String?,
    val format: String?,
    val release: String?,
    val downloadCount: Int?
)

/**
 * Online subtitle search + download, using the free Wyzie API
 * (https://sub.wyzie.io) - same source used by the mpv-android-anime4k
 * reference project. Get a free key at https://sub.wyzie.io/redeem and put
 * it in local.properties as `wyzie.apiKey=...`.
 */
class SubtitleDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "SubtitleDownloadManager"
        private const val BASE_URL = "https://sub.wyzie.io"
    }

    sealed class MediaSearchResult {
        data class Success(val media: List<TmdbMediaResult>) : MediaSearchResult()
        data class Error(val message: String) : MediaSearchResult()
    }

    sealed class SubtitleSearchResult {
        data class Success(val subtitles: List<SubtitleInfo>) : SubtitleSearchResult()
        data class Error(val message: String) : SubtitleSearchResult()
    }

    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val apiKey = BuildConfig.WYZIE_API_KEY

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun searchMedia(query: String): MediaSearchResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext MediaSearchResult.Error("Empty query")
        if (apiKey.isBlank()) {
            return@withContext MediaSearchResult.Error(
                "Missing WYZIE_API_KEY - add wyzie.apiKey=... to local.properties"
            )
        }
        try {
            val url = "$BASE_URL/api/tmdb/search?q=${enc(query)}&key=${enc(apiKey)}"
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) {
                return@withContext MediaSearchResult.Error("HTTP ${response.code}")
            }
            val body = response.body?.string() ?: return@withContext MediaSearchResult.Success(emptyList())
            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: JSONArray()

            val media = mutableListOf<TmdbMediaResult>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val id = item.optInt("id", -1)
                val title = item.optString("title", "")
                if (id == -1 || title.isBlank()) continue
                media.add(
                    TmdbMediaResult(
                        id = id,
                        mediaType = item.optString("mediaType", "movie"),
                        title = title,
                        releaseYear = item.optString("releaseYear", null),
                        poster = item.optString("poster", null)
                    )
                )
            }
            MediaSearchResult.Success(media)
        } catch (e: Throwable) {
            Log.e(TAG, "searchMedia failed", e)
            MediaSearchResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun searchSubtitlesByMediaId(mediaId: Int): SubtitleSearchResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext SubtitleSearchResult.Error(
                    "Missing WYZIE_API_KEY - add wyzie.apiKey=... to local.properties"
                )
            }
            try {
                val url = "$BASE_URL/search?id=$mediaId&key=${enc(apiKey)}"
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                if (!response.isSuccessful) {
                    return@withContext SubtitleSearchResult.Error("HTTP ${response.code}")
                }
                val body = response.body?.string()
                    ?: return@withContext SubtitleSearchResult.Success(emptyList())
                val arr = JSONArray(body)

                val subs = mutableListOf<SubtitleInfo>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val subUrl = item.optString("url", "")
                    if (subUrl.isBlank()) continue
                    subs.add(
                        SubtitleInfo(
                            url = subUrl,
                            language = item.optString("language", null),
                            languageDisplay = item.optString("display", null),
                            format = item.optString("format", "srt"),
                            release = item.optString("release", null),
                            downloadCount = if (item.has("downloadCount")) item.optInt(
                                "downloadCount"
                            ) else null
                        )
                    )
                }
                SubtitleSearchResult.Success(subs.sortedByDescending { it.downloadCount ?: 0 })
            } catch (e: Throwable) {
                Log.e(TAG, "searchSubtitlesByMediaId failed", e)
                SubtitleSearchResult.Error(e.message ?: "Unknown error")
            }
        }

    /**
     * Downloads [subtitle] and saves it next to the video, as
     * "<videoBaseName>.<lang>.<ext>" inside [targetDir].
     */
    suspend fun downloadSubtitle(
        subtitle: SubtitleInfo,
        targetDir: File,
        videoBaseName: String
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val response =
                client.newCall(Request.Builder().url(subtitle.url).build()).execute()
            if (!response.isSuccessful) {
                return@withContext DownloadResult.Error("HTTP ${response.code}")
            }
            val bytes = response.body?.bytes()
                ?: return@withContext DownloadResult.Error("Empty response")

            val ext = subtitle.format?.lowercase()?.takeIf { it.isNotBlank() } ?: "srt"
            val lang = subtitle.language ?: "unknown"
            if (!targetDir.exists()) targetDir.mkdirs()
            val outFile = File(targetDir, "$videoBaseName.$lang.$ext")

            FileOutputStream(outFile).use { it.write(bytes) }
            DownloadResult.Success(outFile)
        } catch (e: Throwable) {
            Log.e(TAG, "downloadSubtitle failed", e)
            DownloadResult.Error(e.message ?: "Unknown error")
        }
    }
}
