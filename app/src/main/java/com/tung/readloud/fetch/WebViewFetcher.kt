package com.tung.readloud.fetch

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import java.io.IOException

/**
 * Loads a page in a hidden WebView and returns the rendered DOM as HTML. Bot-check pages that solve
 * themselves and reload are waited out; one that never clears ends in [ChallengeRequiredException].
 */
class WebViewFetcher(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(url: String, timeoutMs: Long = TIMEOUT_MS): String = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String>()
        val main = Handler(Looper.getMainLooper())
        var sawChallenge = false
        val webView = WebView(context.applicationContext)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        configure(webView)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, startedUrl: String, favicon: android.graphics.Bitmap?) {
                Log.d(TAG, "WebView page started $startedUrl")
            }

            override fun onPageFinished(view: WebView, loadedUrl: String) {
                Log.d(TAG, "WebView page finished $loadedUrl")
                // View.postDelayed never runs on a WebView that is not attached to a window.
                main.postDelayed({
                    if (result.isCompleted) return@postDelayed
                    view.evaluateJavascript("document.documentElement.outerHTML") { json ->
                        Log.d(TAG, "WebView html ${json?.length ?: 0} chars")
                        val html = runCatching { JSONTokener(json).nextValue() as? String }.getOrNull()
                        when {
                            result.isCompleted -> Unit
                            html.isNullOrBlank() -> result.completeExceptionally(IOException("WebView trả về trang rỗng"))
                            PageFetcher.isChallenge(html) -> {
                                sawChallenge = true
                                Log.i(TAG, "Bot check on $loadedUrl, waiting for it to clear")
                            }
                            else -> result.complete(html)
                        }
                    }
                }, SETTLE_DELAY_MS)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !result.isCompleted) {
                    result.completeExceptionally(IOException("WebView lỗi: ${error.description}"))
                }
            }
        }
        webView.loadUrl(url)
        try {
            withTimeoutOrNull(timeoutMs) { result.await() }
                ?: throw if (sawChallenge) ChallengeRequiredException(url) else IOException("Tải trang quá thời gian chờ")
        } finally {
            main.removeCallbacksAndMessages(null)
            CookieManager.getInstance().flush()
            webView.stopLoading()
            webView.destroy()
        }
    }

    private companion object {
        const val TAG = "ReadLoud"
        const val SETTLE_DELAY_MS = 1500L
        const val TIMEOUT_MS = 40_000L

        @SuppressLint("SetJavaScriptEnabled")
        fun configure(webView: WebView) {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = PageFetcher.USER_AGENT
                blockNetworkImage = true
                loadsImagesAutomatically = false
            }
        }
    }
}
