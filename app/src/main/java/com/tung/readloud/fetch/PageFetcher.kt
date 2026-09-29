package com.tung.readloud.fetch

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.IOException

/** The site shows a bot check the hidden WebView could not get past; the user has to open it once. */
class ChallengeRequiredException(val url: String) : IOException("Trang yêu cầu xác minh trình duyệt")

/**
 * Fetches rendered HTML: direct Jsoup request first, hidden WebView as fallback. Both share the WebView
 * cookie jar and user agent, so passing a bot check once in the WebView also unblocks the direct requests.
 */
class PageFetcher(private val context: Context) {

    suspend fun fetch(url: String): String {
        val direct = runCatching { fetchDirect(url) }
            .onFailure { Log.w(TAG, "Direct fetch failed for $url: $it") }
            .getOrNull()
        if (direct != null && looksUsable(direct)) return direct
        Log.i(TAG, "Falling back to WebView for $url")

        val rendered = WebViewFetcher(context).fetch(url)
        if (isChallenge(rendered)) throw ChallengeRequiredException(url)
        return rendered
    }

    private suspend fun fetchDirect(url: String): String {
        val cookies = withContext(Dispatchers.Main) { runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() }
        return withContext(Dispatchers.IO) {
            val connection = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
                .timeout(20_000)
                .maxBodySize(0)
                .followRedirects(true)
                .ignoreHttpErrors(true)
            if (!cookies.isNullOrBlank()) connection.header("Cookie", cookies)
            val response = connection.execute()
            val body = response.body()
            if (response.statusCode() >= 400 && !isChallenge(body)) {
                throw IOException("HTTP ${response.statusCode()}")
            }
            body
        }
    }

    private fun looksUsable(html: String): Boolean {
        if (isChallenge(html)) return false
        val doc = Jsoup.parse(html)
        return (doc.body()?.text()?.length ?: 0) > MIN_BODY_TEXT
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val MIN_BODY_TEXT = 500
        private const val TAG = "ReadLoud"

        private val challengeTitles = listOf(
            "just a moment", "attention required", "checking your browser", "confirm you are human",
            "verify you are human", "security check", "ddos-guard",
        )

        /** Cloudflare, WordPress.com hashcash and similar "checking your browser" interstitials. */
        fun isChallenge(html: String): Boolean {
            val doc = Jsoup.parse(html)
            val title = doc.title().lowercase()
            if (challengeTitles.any { title.contains(it) }) return true
            if (html.contains("/__challenge") && html.contains("X-Hashcash")) return true
            return doc.selectFirst("#challenge-form, #challenge-running, #cf-challenge-running, .cf-browser-verification, #cf-turnstile") != null
        }
    }
}
