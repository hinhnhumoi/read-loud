package com.tung.readloud.ui

import kotlinx.coroutines.flow.first
import com.tung.readloud.follow.NewChapterWorker
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tung.readloud.R
import com.tung.readloud.book.BookStore
import com.tung.readloud.book.BookUrl
import com.tung.readloud.data.EventLog
import com.tung.readloud.data.LibraryRepository
import com.tung.readloud.data.NovelRepository
import com.tung.readloud.data.SeriesKey
import com.tung.readloud.databinding.ActivityMainBinding
import com.tung.readloud.tts.ReaderService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The app's frame: three tabs, and the player that sits above them as a mini player and pulls up to
 * full screen. Also where shared files and links arrive.
 */
class MainActivity : AppCompatActivity() {

    enum class Tab(val menuId: Int, val tag: String) {
        HOME(R.id.tab_home, "home"),
        LIBRARY(R.id.tab_library, "library"),
        SETTINGS(R.id.tab_settings, "settings"),
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var sheet: BottomSheetBehavior<View>
    private lateinit var playerUi: PlayerUi
    private val novels by lazy { NovelRepository(this) }
    private val library by lazy { LibraryRepository(this) }
    private val bookStore by lazy { BookStore(this) }
    private var tab = Tab.HOME

    private val _nowPlaying = MutableStateFlow(NowPlaying(ReaderService.state.value, null))

    /** The chapter on screen everywhere: continue card, mini player and player. */
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val openBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importBook) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        setupTabs(savedInstanceState)
        setupSheet()
        playerUi = PlayerUi(this, binding.mini, binding.player)
        setupBack()
        observe()
        requestNotificationPermission()
        handleIntent(intent)
        lifecycleScope.launch { NewChapterWorker.sync(this@MainActivity) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, tab.name)
    }

    // ---- Layout ---------------------------------------------------------------------------

    /** The window draws edge to edge; keep everything clear of the status and navigation bars. */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupTabs(saved: Bundle?) {
        val restored = saved?.getString(KEY_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name } }
        if (supportFragmentManager.findFragmentByTag(Tab.HOME.tag) == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.content, HomeFragment(), Tab.HOME.tag)
                .add(R.id.content, LibraryFragment(), Tab.LIBRARY.tag)
                .add(R.id.content, SettingsFragment(), Tab.SETTINGS.tag)
                .commitNow()
        }
        binding.bottomNav.setOnItemSelectedListener { item ->
            Tab.entries.firstOrNull { it.menuId == item.itemId }?.let { showTab(it, fromNav = true) }
            true
        }
        showTab(restored ?: Tab.HOME)
    }

    fun showTab(target: Tab, fromNav: Boolean = false) {
        tab = target
        val transaction = supportFragmentManager.beginTransaction()
        Tab.entries.forEach { t ->
            val fragment: Fragment = supportFragmentManager.findFragmentByTag(t.tag) ?: return@forEach
            if (t == target) transaction.show(fragment) else transaction.hide(fragment)
        }
        transaction.commitNowAllowingStateLoss()
        if (!fromNav) binding.bottomNav.selectedItemId = target.menuId
        if (::sheet.isInitialized) {
            if (sheet.state == BottomSheetBehavior.STATE_EXPANDED) collapsePlayer() else placeMiniPlayer()
        }
    }

    // ---- Player sheet ---------------------------------------------------------------------

    private fun setupSheet() {
        sheet = BottomSheetBehavior.from(binding.playerSheet)
        sheet.isHideable = true
        sheet.state = BottomSheetBehavior.STATE_HIDDEN
        sheet.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(view: View, newState: Int) {
                // Once it rests, make sure it rests where the current tab wants it.
                if (newState == BottomSheetBehavior.STATE_COLLAPSED || newState == BottomSheetBehavior.STATE_HIDDEN) placeMiniPlayer()
                syncSheetViews(if (newState == BottomSheetBehavior.STATE_EXPANDED) 1f else 0f)
            }

            override fun onSlide(view: View, offset: Float) = syncSheetViews(offset.coerceIn(0f, 1f))
        })
    }

    /** Crossfades the mini player into the full player and slides the tabs away as the sheet rises. */
    private fun syncSheetViews(offset: Float) {
        binding.player.root.alpha = offset
        binding.player.root.visibility = if (offset > 0f) View.VISIBLE else View.INVISIBLE
        binding.mini.root.alpha = (1f - offset * 2f).coerceIn(0f, 1f)
        binding.mini.root.visibility = if (offset < 0.5f) View.VISIBLE else View.INVISIBLE
        binding.bottomNav.translationY = binding.bottomNav.height * offset
    }

    /** The mini player shows on the library and settings tabs while there is something to resume. */
    private fun placeMiniPlayer() {
        // While it moves, leave it be: playback updates arrive several times a second and would
        // otherwise pull a rising player straight back down.
        val state = sheet.state
        if (state == BottomSheetBehavior.STATE_EXPANDED || state == BottomSheetBehavior.STATE_DRAGGING ||
            state == BottomSheetBehavior.STATE_SETTLING
        ) return
        val wanted = tab != Tab.HOME && _nowPlaying.value.hasSomething
        sheet.skipCollapsed = tab == Tab.HOME
        val target = if (wanted) BottomSheetBehavior.STATE_COLLAPSED else BottomSheetBehavior.STATE_HIDDEN
        if (sheet.state != target) sheet.state = target
    }

    fun expandPlayer() {
        if (!_nowPlaying.value.hasSomething) return
        sheet.skipCollapsed = tab == Tab.HOME
        sheet.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun collapsePlayer() {
        sheet.skipCollapsed = tab == Tab.HOME
        sheet.state = if (tab == Tab.HOME) BottomSheetBehavior.STATE_HIDDEN else BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    sheet.state == BottomSheetBehavior.STATE_EXPANDED -> collapsePlayer()
                    tab != Tab.HOME -> showTab(Tab.HOME)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { novels.migrateLegacy() }
                launch {
                    ReaderService.state.combine(library.items) { state, items ->
                        val item = state.novelId?.let { id -> items.firstOrNull { it.novel.id == id } } ?: items.firstOrNull()
                        NowPlaying(state, item)
                    }.collect { np ->
                        _nowPlaying.value = np
                        playerUi.bind(np)
                        placeMiniPlayer()
                        if (!np.hasSomething && sheet.state == BottomSheetBehavior.STATE_EXPANDED) collapsePlayer()
                    }
                }
                launch { playerUi.runTicker() }
            }
        }
    }

    // ---- Starting things to read ---------------------------------------------------------

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            setIntent(Intent(this, MainActivity::class.java))
            // The novel was just started; the sheet opens once there is something to show in it.
            lifecycleScope.launch {
                nowPlaying.first { it.hasSomething }
                expandPlayer()
            }
            return
        }
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
        extractUrl(text)?.let(::startReading)
    }

    fun showAddSheet() = AddSheet().show(supportFragmentManager, AddSheet.TAG)

    fun openBookPicker() {
        runCatching { openBook.launch(arrayOf(BookStore.EPUB_MIME, BookStore.PDF_MIME, "text/plain", "application/octet-stream")) }
            .onFailure { Toast.makeText(this, R.string.toast_settings_unavailable, Toast.LENGTH_SHORT).show() }
    }

    fun startReading(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(this, R.string.toast_enter_url, Toast.LENGTH_SHORT).show()
            return
        }
        val url = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        ReaderService.start(this, url)
        expandPlayer()
    }

    /** Reads text copied from anywhere as a one-off book. */
    fun readPastedText(text: String) {
        lifecycleScope.launch {
            val title = getString(R.string.add_pasted_title, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()))
            runCatching { bookStore.importText(title, text) }
                .onSuccess { book ->
                    ReaderService.start(this@MainActivity, BookUrl.chapter(book.id, 0), 0, null)
                    expandPlayer()
                }
                .onFailure { e -> showError(R.string.book_open_failed, e) }
        }
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
                val existing = novels.findByKey(SeriesKey.bookKey(book.id))
                if (existing != null && BookUrl.index(existing.currentUrl)?.let { it < book.chapterCount } == true) {
                    ReaderService.start(this@MainActivity, existing.currentUrl, existing.chunkIndex, existing.id)
                } else {
                    ReaderService.start(this@MainActivity, BookUrl.chapter(book.id, 0), 0, existing?.id)
                }
                Toast.makeText(this@MainActivity, getString(R.string.book_opened, book.title, book.chapterCount), Toast.LENGTH_LONG).show()
                expandPlayer()
            } catch (e: CancellationException) {
                progress.dismiss()
                throw e
            } catch (e: Exception) {
                progress.dismiss()
                showError(R.string.book_open_failed, e)
            }
        }
    }

    private fun showError(title: Int, e: Throwable) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(e.message ?: e.javaClass.simpleName)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        private const val EXTRA_OPEN_PLAYER = "open_player"

        /** Back to the main screen, above anything opened from it, with the player open. */
        fun playerIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_PLAYER, true)

        private const val KEY_TAB = "tab"
        private val urlPattern = Regex("https?://\\S+")

        fun extractUrl(text: String): String? = urlPattern.find(text)?.value
    }
}
