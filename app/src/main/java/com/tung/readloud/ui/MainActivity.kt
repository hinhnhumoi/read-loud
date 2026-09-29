package com.tung.readloud.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.OnBackPressedCallback
import androidx.core.content.IntentCompat
import androidx.core.view.GravityCompat
import android.widget.TextView
import com.tung.readloud.R
import com.tung.readloud.book.BookStore
import com.tung.readloud.book.BookUrl
import com.tung.readloud.data.EventLog
import com.tung.readloud.data.Novel
import com.tung.readloud.data.SeriesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tung.readloud.data.NovelRepository
import com.tung.readloud.data.ProgressStore
import com.tung.readloud.data.VoiceEngine
import com.tung.readloud.data.VoiceSettings
import com.tung.readloud.tts.speech.OnlineVoices
import com.tung.readloud.databinding.ActivityMainBinding
import com.tung.readloud.tts.PlaybackStatus
import com.tung.readloud.tts.ReaderService
import com.tung.readloud.tts.ReaderState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.URI
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val store by lazy { ProgressStore(this) }
    private val library by lazy { NovelRepository(this) }
    private var voiceSettings: VoiceSettings? = null
    private val novelAdapter = NovelAdapter(
        onClick = { novel -> ReaderService.start(this, novel.currentUrl, novel.chunkIndex, novel.id) },
        onLongClick = { novel, anchor -> NovelActions.showMenu(this, novel, anchor) },
    )

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val bookStore by lazy { BookStore(this) }
    private val openBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importBook) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requestNotificationPermission()
        setupDrawer()
        setupControls()
        observe()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val fileUri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" }
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (fileUri != null) {
            setIntent(Intent(this, MainActivity::class.java))
            importBook(fileUri)
            return
        }
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        setIntent(Intent(this, MainActivity::class.java))
        val url = extractUrl(text) ?: return
        binding.urlInput.setText(url)
        startReading(url)
    }

    private fun setupControls() {
        binding.novelList.layoutManager = LinearLayoutManager(this)
        binding.novelList.adapter = novelAdapter
        binding.novelList.isNestedScrollingEnabled = false

        binding.urlLayout.setEndIconOnClickListener { pasteFromClipboard() }
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                startReading(binding.urlInput.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        binding.btnRead.setOnClickListener { startReading(binding.urlInput.text?.toString().orEmpty()) }
        binding.btnOpenFile.setOnClickListener { openBookPicker() }
        binding.btnToggle.setOnClickListener {
            when (ReaderService.state.value.status) {
                PlaybackStatus.PLAYING, PlaybackStatus.LOADING -> ReaderService.send(this, ReaderService.ACTION_PAUSE)
                PlaybackStatus.PAUSED -> ReaderService.send(this, ReaderService.ACTION_PLAY)
                else -> {
                    val typed = binding.urlInput.text?.toString().orEmpty().trim()
                    val recent = novelAdapter.currentList.firstOrNull()
                    if (typed.isEmpty() && recent != null) {
                        ReaderService.start(this, recent.currentUrl, recent.chunkIndex, recent.id)
                    } else {
                        startReading(typed)
                    }
                }
            }
        }
        binding.btnNext.setOnClickListener { ReaderService.send(this, ReaderService.ACTION_NEXT) }
        binding.btnPrevChapter.setOnClickListener { ReaderService.send(this, ReaderService.ACTION_PREV_CHAPTER) }
        binding.btnStop.setOnClickListener { ReaderService.send(this, ReaderService.ACTION_STOP) }
        binding.btnPrevChunk.setOnClickListener { ReaderService.send(this, ReaderService.ACTION_PREV_CHUNK) }
        binding.btnNextChunk.setOnClickListener { ReaderService.send(this, ReaderService.ACTION_NEXT_CHUNK) }
        binding.btnSleep.setOnClickListener { showSleepDialog() }
        binding.btnReadText.setOnClickListener { startActivity(Intent(this, ReaderActivity::class.java)) }
        binding.btnDownload.setOnClickListener { onDownloadClicked() }
        binding.voiceRow.setOnClickListener { startActivity(Intent(this, VoiceActivity::class.java)) }
        binding.rulesRow.setOnClickListener { startActivity(Intent(this, RulesActivity::class.java)) }
        binding.btnInstallVoice.setOnClickListener { openTtsSettings() }
        binding.btnVerify.setOnClickListener {
            val s = ReaderService.state.value
            val url = s.verifyUrl ?: return@setOnClickListener
            startActivity(VerifyActivity.intent(this, url, resume = true, chunkIndex = s.verifyResumeIndex, novelId = s.novelId))
        }
        binding.btnBattery.setOnClickListener { showBatteryDialog() }
        binding.btnSeeAll.setOnClickListener { startActivity(Intent(this, LibraryActivity::class.java)) }

        binding.rateSlider.addOnChangeListener { _, value, fromUser ->
            binding.rateValue.text = getString(R.string.rate_value, value.format1())
            if (fromUser) onVoiceChanged()
        }
        binding.pitchSlider.addOnChangeListener { _, value, fromUser ->
            binding.pitchValue.text = value.format1()
            if (fromUser) onVoiceChanged()
        }
        binding.rateValue.text = getString(R.string.rate_value, binding.rateSlider.value.format1())
        binding.pitchValue.text = binding.pitchSlider.value.format1()
    }

    private fun pasteFromClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val url = text?.let(::extractUrl)
        if (url == null) toast(R.string.toast_no_url_in_clipboard) else binding.urlInput.setText(url)
    }

    private fun onVoiceChanged() {
        val rate = binding.rateSlider.value
        val pitch = binding.pitchSlider.value
        lifecycleScope.launch {
            store.setSpeechRate(rate)
            store.setPitch(pitch)
        }
        if (ReaderService.state.value.status != PlaybackStatus.IDLE) {
            ReaderService.send(this, ReaderService.ACTION_SET_VOICE) {
                putExtra(ReaderService.EXTRA_RATE, rate)
                putExtra(ReaderService.EXTRA_PITCH, pitch)
            }
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ReaderService.state.collect { render(it) } }
                launch {
                    library.migrateLegacy()
                    library.novels.collect { novels ->
                        novelAdapter.submitList(novels.take(HOME_RECENT_COUNT))
                        binding.librarySection.isVisible = novels.isNotEmpty()
                    }
                }
                launch {
                    binding.rateSlider.value = store.speechRate.first().snap(binding.rateSlider.valueFrom, binding.rateSlider.valueTo)
                    binding.pitchSlider.value = store.pitch.first().snap(binding.pitchSlider.valueFrom, binding.pitchSlider.valueTo)
                }
                launch {
                    store.voiceSettings.collect { voiceSettings = it; binding.voiceSummary.text = voiceSummary(it) }
                }
                launch {
                    while (true) {
                        renderSleep(ReaderService.state.value)
                        delay(1_000)
                    }
                }
            }
        }
    }

    private fun render(s: ReaderState) {
        val active = s.status == PlaybackStatus.PLAYING || s.status == PlaybackStatus.LOADING
        val hasChapter = s.status != PlaybackStatus.IDLE

        binding.statusText.text = when (s.status) {
            PlaybackStatus.IDLE -> getString(R.string.status_idle)
            PlaybackStatus.LOADING -> s.message ?: getString(R.string.status_loading)
            PlaybackStatus.PLAYING -> getString(R.string.status_label_reading)
            PlaybackStatus.PAUSED -> getString(R.string.status_label_paused)
            PlaybackStatus.ERROR -> getString(R.string.status_label_error)
            PlaybackStatus.FINISHED -> getString(R.string.status_label_finished)
        }
        binding.chapterTitle.text = when {
            s.title != null -> s.title
            s.status == PlaybackStatus.ERROR || s.status == PlaybackStatus.FINISHED -> s.message ?: getString(R.string.no_chapter)
            else -> getString(R.string.no_chapter)
        }
        val host = when {
            BookUrl.isBook(s.url) -> getString(R.string.book_source)
            else -> s.url?.let { runCatching { URI(it).host }.getOrNull() }
        }
        binding.hostText.text = host
        binding.hostText.isVisible = host != null

        val showProgress = s.chunkCount > 0
        binding.progress.isVisible = showProgress
        binding.progressText.isVisible = showProgress
        if (showProgress) {
            binding.progress.max = s.chunkCount
            binding.progress.setProgressCompat(s.chunkIndex + 1, true)
            binding.progressText.text = when (s.status) {
                PlaybackStatus.ERROR, PlaybackStatus.FINISHED -> s.message ?: getString(R.string.progress_text, s.chunkIndex + 1, s.chunkCount)
                else -> listOfNotNull(
                    getString(R.string.progress_text, s.chunkIndex + 1, s.chunkCount),
                    s.bufferedChapters.takeIf { it > 0 }?.let { getString(R.string.buffer_note, it) },
                ).joinToString(" · ")
            }
        }

        binding.btnToggle.setIconResource(if (active) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnToggle.contentDescription = getString(if (active) R.string.action_pause else R.string.action_play)
        binding.btnNext.isEnabled = hasChapter
        binding.btnPrevChapter.isEnabled = hasChapter
        binding.btnStop.isEnabled = hasChapter
        binding.btnPrevChunk.isEnabled = s.chunks.isNotEmpty()
        binding.btnNextChunk.isEnabled = s.chunks.isNotEmpty()
        binding.btnReadText.isEnabled = s.chunks.isNotEmpty()
        binding.btnSleep.isEnabled = hasChapter
        binding.btnDownload.isEnabled = hasChapter || novelAdapter.currentList.isNotEmpty()
        binding.btnDownload.setText(if (s.download != null) R.string.download_cancel else R.string.action_download)
        val download = s.download
        binding.downloadText.text = when {
            download == null -> s.downloadMessage
            download.chunksTotal > 0 -> getString(
                R.string.download_progress_chunks, download.chaptersDone + 1, download.chaptersTotal, download.chunksDone, download.chunksTotal,
            )
            else -> getString(R.string.download_progress, download.chaptersDone + 1, download.chaptersTotal)
        }
        binding.downloadText.isVisible = !binding.downloadText.text.isNullOrBlank()
        binding.engineNote.text = s.engineNote
        binding.engineNote.isVisible = s.engineNote != null
        binding.btnInstallVoice.isVisible = s.ttsNeedsData
        binding.btnVerify.isVisible = s.verifyUrl != null && s.status == PlaybackStatus.ERROR
        if (s.url != null && !BookUrl.isBook(s.url) && binding.urlInput.text.isNullOrBlank()) binding.urlInput.setText(s.url)
        novelAdapter.activeId = if (hasChapter) s.novelId else null
        renderSleep(s)
    }

    private fun renderSleep(s: ReaderState) {
        val deadline = s.sleepDeadline
        binding.btnSleep.text = when {
            s.sleepAfterChapter -> getString(R.string.sleep_after_chapter_short)
            deadline != null -> {
                val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 1000
                getString(R.string.sleep_countdown, remaining / 60, remaining % 60)
            }
            else -> getString(R.string.action_sleep)
        }
    }

    private fun showSleepDialog() {
        val labels = arrayOf(
            getString(R.string.sleep_off),
            getString(R.string.sleep_minutes, 15),
            getString(R.string.sleep_minutes, 30),
            getString(R.string.sleep_minutes, 60),
            getString(R.string.sleep_after_chapter),
        )
        val values = intArrayOf(0, 15, 30, 60, ReaderService.SLEEP_END_OF_CHAPTER)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sleep_title)
            .setItems(labels) { _, which ->
                ReaderService.send(this, ReaderService.ACTION_SET_SLEEP) { putExtra(ReaderService.EXTRA_SLEEP_MINUTES, values[which]) }
            }
            .show()
    }

    private fun voiceSummary(v: VoiceSettings): String = when (v.engine) {
        VoiceEngine.EDGE -> getString(R.string.voice_summary_edge, OnlineVoices.label(v.onlineVoice))
        VoiceEngine.AZURE -> if (v.azureKey.isBlank()) {
            getString(R.string.voice_summary_azure_no_key)
        } else {
            getString(R.string.voice_summary_azure, OnlineVoices.label(v.onlineVoice))
        }
        VoiceEngine.SYSTEM -> getString(R.string.voice_summary_system, v.systemVoice ?: getString(R.string.voice_summary_system_default))
    }

    private fun onDownloadClicked() {
        if (ReaderService.state.value.download != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.download_cancel_title)
                .setMessage(R.string.download_cancel_message)
                .setPositiveButton(R.string.download_cancel) { _, _ -> ReaderService.send(this, ReaderService.ACTION_CANCEL_DOWNLOAD) }
                .setNegativeButton(R.string.download_keep, null)
                .show()
            return
        }
        val counts = intArrayOf(5, 10, 20)
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_title)
            .setItems(counts.map { getString(R.string.download_option, it) }.toTypedArray()) { _, which ->
                ReaderService.send(this, ReaderService.ACTION_DOWNLOAD) { putExtra(ReaderService.EXTRA_COUNT, counts[which]) }
            }
        if (voiceSettings?.engine == VoiceEngine.SYSTEM) builder.setMessage(R.string.download_system_note)
        builder.show()
    }

    private fun setupDrawer() {
        binding.toolbar.setNavigationOnClickListener { binding.drawer.openDrawer(GravityCompat.START) }
        // DrawerLayout paints the status bar with colorPrimaryDark by default; match the other screens instead.
        binding.drawer.setStatusBarBackgroundColor(
            com.google.android.material.color.MaterialColors.getColor(binding.drawer, com.google.android.material.R.attr.colorSurface),
        )
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        binding.navView.getHeaderView(0).findViewById<TextView>(R.id.drawerVersion).text = getString(R.string.drawer_version, version)
        binding.navView.setNavigationItemSelectedListener { item ->
            binding.drawer.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.nav_library -> startActivity(Intent(this, LibraryActivity::class.java))
                R.id.nav_open_file -> openBookPicker()
                R.id.nav_voice -> startActivity(Intent(this, VoiceActivity::class.java))
                R.id.nav_rules -> startActivity(Intent(this, RulesActivity::class.java))
                R.id.nav_battery -> showBatteryDialog()
                R.id.nav_event_log -> showEventLog()
            }
            // Only "home" stays highlighted; the others open a screen or a dialog.
            false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawer.isDrawerOpen(GravityCompat.START)) {
                    binding.drawer.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    /** The playback log, so a stall on the phone can be looked at, or sent, without a computer. */
    private fun showEventLog() {
        val text = EventLog.read().takeLast(EVENT_LOG_SHOWN_CHARS).ifBlank { getString(R.string.event_log_empty) }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val view = TextView(this).apply {
            this.text = text
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, 0)
        }
        val scroll = androidx.core.widget.NestedScrollView(this).apply { addView(view) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.event_log_title)
            .setView(scroll)
            .setPositiveButton(R.string.event_log_share) { _, _ ->
                val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, EventLog.read())
                startActivity(Intent.createChooser(share, getString(R.string.event_log_title)))
            }
            .setNeutralButton(R.string.event_log_clear) { _, _ -> EventLog.clear() }
            .setNegativeButton(R.string.action_close, null)
            .show()
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun openBookPicker() {
        runCatching { openBook.launch(arrayOf(BookStore.EPUB_MIME, BookStore.PDF_MIME, "text/plain", "application/octet-stream")) }
            .onFailure { toast(R.string.toast_settings_unavailable) }
    }

    /** Stores the picked book, then resumes it where it was left or starts at its first chapter. */
    private fun importBook(uri: Uri) {
        val progress = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.book_opening)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            try {
                val book = bookStore.import(contentResolver, uri)
                progress.dismiss()
                val existing = library.findByKey(SeriesKey.bookKey(book.id))
                if (existing != null && BookUrl.index(existing.currentUrl)?.let { it < book.chapterCount } == true) {
                    ReaderService.start(this@MainActivity, existing.currentUrl, existing.chunkIndex, existing.id)
                } else {
                    ReaderService.start(this@MainActivity, BookUrl.chapter(book.id, 0), 0, existing?.id)
                }
                Toast.makeText(this@MainActivity, getString(R.string.book_opened, book.title, book.chapterCount), Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                progress.dismiss()
                throw e
            } catch (e: Exception) {
                progress.dismiss()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.book_open_failed)
                    .setMessage(e.message ?: e.javaClass.simpleName)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun startReading(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            toast(R.string.toast_enter_url)
            return
        }
        val url = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        hideKeyboard()
        ReaderService.start(this, url)
    }

    private fun showBatteryDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_message)
            .setPositiveButton(R.string.battery_open_settings) { _, _ -> openBatterySettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openBatterySettings() {
        val pm = getSystemService(PowerManager::class.java)
        val packageUri = Uri.parse("package:$packageName")
        val candidates = buildList {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
            }
            if (Build.MANUFACTURER.contains("honor", true) || Build.MANUFACTURER.contains("huawei", true)) {
                add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
                add(Intent().setComponent(ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
        for (intent in candidates) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        toast(R.string.toast_settings_unavailable)
    }

    private fun openTtsSettings() {
        val intents = listOf(Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
        for (intent in intents) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun extractUrl(text: String): String? = urlPattern.find(text)?.value

    private fun Float.format1(): String = String.format(Locale.US, "%.1f", this)

    private fun Float.snap(min: Float, max: Float): Float = ((this * 10).roundToInt() / 10f).coerceIn(min, max)

    private companion object {
        val urlPattern = Regex("https?://\\S+")
        const val HOME_RECENT_COUNT = 3
        const val EVENT_LOG_SHOWN_CHARS = 20_000
    }
}
