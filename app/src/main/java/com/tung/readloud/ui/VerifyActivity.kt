package com.tung.readloud.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isVisible
import com.tung.readloud.R
import com.tung.readloud.databinding.ActivityVerifyBinding
import com.tung.readloud.fetch.PageFetcher
import com.tung.readloud.tts.ReaderService
import org.json.JSONTokener
import java.net.URI

/**
 * Shows a page whose bot check the hidden WebView could not pass, so the user can do it by hand. The
 * WebView shares cookies and user agent with [PageFetcher], so once the check clears, reading can go on.
 */
class VerifyActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVerifyBinding
    private val main = Handler(Looper.getMainLooper())
    private lateinit var url: String
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }
        binding = ActivityVerifyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.subtitle = runCatching { URI(url).host }.getOrNull()
        NotificationManagerCompat.from(this).cancel(ReaderService.VERIFY_NOTIFICATION_ID)

        val web = binding.web
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Clearance cookies are tied to the user agent, so it must match the app's own requests.
            userAgentString = PageFetcher.USER_AGENT
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, startedUrl: String, favicon: Bitmap?) {
                binding.progress.isVisible = true
            }

            override fun onPageFinished(view: WebView, loadedUrl: String) {
                binding.progress.isVisible = false
                main.postDelayed({ check(byUser = false) }, SETTLE_DELAY_MS)
            }
        }
        binding.btnDone.setOnClickListener { check(byUser = true) }
        web.loadUrl(url)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::binding.isInitialized) binding.web.destroy()
        super.onDestroy()
    }

    /** Passes once the page shows real content instead of the check. */
    private fun check(byUser: Boolean) {
        if (done) return
        binding.web.evaluateJavascript("document.documentElement.outerHTML") { json ->
            val html = runCatching { JSONTokener(json).nextValue() as? String }.getOrNull().orEmpty()
            when {
                done -> Unit
                html.isNotBlank() && !PageFetcher.isChallenge(html) -> succeed()
                byUser -> Toast.makeText(this, R.string.verify_still, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun succeed() {
        done = true
        CookieManager.getInstance().flush()
        if (intent.getBooleanExtra(EXTRA_RESUME, false)) {
            val novelId = intent.getLongExtra(EXTRA_NOVEL_ID, -1L).takeIf { it > 0 }
            ReaderService.start(this, url, intent.getIntExtra(EXTRA_INDEX, 0), novelId)
            Toast.makeText(this, R.string.verify_ok, Toast.LENGTH_SHORT).show()
        }
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_RESUME = "resume"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_NOVEL_ID = "novel_id"
        private const val SETTLE_DELAY_MS = 1500L

        /** Verifies [url]; with [resume], reading restarts there at [chunkIndex] once the check clears. */
        fun intent(context: Context, url: String, resume: Boolean = false, chunkIndex: Int = 0, novelId: Long? = null): Intent =
            Intent(context, VerifyActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_RESUME, resume)
                .putExtra(EXTRA_INDEX, chunkIndex)
                .putExtra(EXTRA_NOVEL_ID, novelId ?: -1L)
    }
}
