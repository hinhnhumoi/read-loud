package com.tung.readloud.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.tung.readloud.R
import com.tung.readloud.book.BookStore
import com.tung.readloud.book.BookUrl
import com.tung.readloud.data.Bookmark
import com.tung.readloud.data.BookmarkRepository
import com.tung.readloud.data.ChapterCache
import com.tung.readloud.data.Novel
import com.tung.readloud.data.NovelRepository
import com.tung.readloud.data.ProgressStore
import com.tung.readloud.data.RuleRepository
import com.tung.readloud.data.VoiceEngine
import com.tung.readloud.data.VoiceSettings
import com.tung.readloud.databinding.ActivityNovelDetailBinding
import com.tung.readloud.databinding.ItemBookmarkBinding
import com.tung.readloud.databinding.ItemChapterBinding
import com.tung.readloud.fetch.ChallengeRequiredException
import com.tung.readloud.fetch.PageFetcher
import com.tung.readloud.fetch.TocLoader
import com.tung.readloud.follow.NewChapterWorker
import com.tung.readloud.parse.SiteConfigs
import com.tung.readloud.parse.TocParser
import com.tung.readloud.tts.PlaybackStatus
import com.tung.readloud.tts.ReaderService
import com.tung.readloud.tts.speech.OnlineVoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * One novel: how far along it is, a way to carry on, and three tabs — its chapters, its bookmarks,
 * and the voice, speed and reading rules it uses instead of the general ones.
 */
class NovelDetailActivity : AppCompatActivity() {

    private enum class Pane { TOC, MARKS, OWN }

    private lateinit var binding: ActivityNovelDetailBinding
    private val repo by lazy { NovelRepository(this) }
    private val bookmarks by lazy { BookmarkRepository(this) }
    private val rules by lazy { RuleRepository(this) }
    private val store by lazy { ProgressStore(this) }
    private val fetcher by lazy { PageFetcher(this) }
    private val chapterCache by lazy { ChapterCache(this) }

    private var novelId = -1L
    private var novel: Novel? = null
    /** Which novel the player has, and whether it is playing; the rest of its state does not show here. */
    private var playing: Pair<Long?, Boolean> = null to false
    private var bookFormat = ""
    private var globalVoice = VoiceSettings()
    private var entries: List<TocParser.Entry> = emptyList()
    private var loadJob: Job? = null
    private var scrolledToCurrent = false
    private var markCount = 0

    private val chapterAdapter = ChapterAdapter { entry -> listen(entry.url, 0) }
    private val markAdapter = BookmarkAdapter(onOpen = { listen(it.chapterUrl, it.chunkIndex) }, onMenu = ::showMarkMenu)

    /** Arguments of the last [load], repeated once the user has passed a bot check. */
    private var lastLoad: Pair<Boolean, String?> = false to null
    private val verify = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) load(lastLoad.first, lastLoad.second)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNovelDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        novelId = intent.getLongExtra(EXTRA_NOVEL_ID, -1L)
        if (novelId <= 0) {
            finish()
            return
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnMore.setOnClickListener(::showMenu)
        binding.btnListen.setOnClickListener { continueListening() }
        binding.btnDownload.setOnClickListener {
            Dialogs.showDownload(this, globalVoice.engine == VoiceEngine.SYSTEM, novelId)
        }
        binding.followCard.setOnClickListener { setFollow(!binding.followSwitch.isChecked) }
        binding.followSwitch.setOnCheckedChangeListener { button, checked -> if (button.isPressed) setFollow(checked) }
        setupTabs(savedInstanceState?.getString(KEY_PANE) ?: intent.getStringExtra(EXTRA_PANE))
        setupToc()
        setupMarks()
        setupOwn()

        lifecycleScope.launch {
            val found = repo.findById(novelId)
            if (found == null) {
                finish()
                return@launch
            }
            novel = found
            loadStoredToc(found)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    val player = ReaderService.state
                        .map { it.novelId to (it.status == PlaybackStatus.PLAYING || it.status == PlaybackStatus.LOADING) }
                        .distinctUntilChanged()
                    repo.observe(novelId).filterNotNull().combine(player) { n, p -> n to p }.collect { (n, p) ->
                        novel = n
                        playing = p
                        render()
                    }
                }
                launch {
                    bookmarks.observe(novelId).collect {
                        markAdapter.submitList(it)
                        markCount = it.size
                        binding.marksEmpty.isVisible = it.isEmpty()
                        binding.tabs.getTabAt(Pane.MARKS.ordinal)?.text =
                            if (it.isEmpty()) getString(R.string.tab_marks) else getString(R.string.tab_marks_count, it.size)
                    }
                }
                launch {
                    rules.rulesFor(novelId).collect {
                        binding.ownRulesValue.text = if (it.isEmpty()) getString(R.string.own_rules_none) else getString(R.string.settings_rules_count, it.size)
                    }
                }
                launch {
                    store.voiceSettings.collect {
                        globalVoice = it
                        render()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PANE, Pane.entries[binding.tabs.selectedTabPosition.coerceAtLeast(0)].name)
    }

    // ---- Header ---------------------------------------------------------------------------

    private val isBook get() = novel?.let { BookUrl.isBook(it.currentUrl) } == true

    private fun currentIndex(): Int? {
        val n = novel ?: return null
        if (BookUrl.isBook(n.currentUrl)) return BookUrl.index(n.currentUrl)
        val key = TocParser.normalize(n.currentUrl)
        return entries.indexOfFirst { TocParser.normalize(it.url) == key }.takeIf { it >= 0 }
    }

    private fun render() {
        val n = novel ?: return
        val book = isBook
        binding.cover.showTitle = true
        binding.cover.title = n.name
        binding.name.text = n.name
        binding.source.text = if (book) {
            if (bookFormat.isBlank()) getString(R.string.library_filter_book) else getString(R.string.detail_source_book, bookFormat)
        } else {
            getString(R.string.detail_source_web, n.host)
        }
        val index = currentIndex()
        binding.chapters.text = when {
            entries.isEmpty() -> getString(R.string.detail_no_toc)
            index != null -> getString(R.string.detail_chapters, entries.size, index)
            else -> getString(R.string.detail_chapters_count, entries.size)
        }
        binding.listened.isVisible = n.listenedMs >= MINUTE_MS
        binding.listened.text = getString(R.string.detail_listened, formatDuration(this, n.listenedMs))

        val playingThis = playing.first == n.id && playing.second
        binding.btnListen.text = when {
            playingThis -> getString(R.string.detail_open_player)
            index != null -> getString(R.string.detail_listen_chapter, index + 1)
            else -> getString(R.string.detail_listen)
        }
        binding.btnListen.setIconResource(if (playingThis) R.drawable.ic_waveform else R.drawable.ic_play)

        binding.followCard.isVisible = !book
        binding.followSwitch.isChecked = n.followNew
        binding.followHint.setText(if (n.tocUrl == null) R.string.follow_needs_toc else R.string.follow_hint)

        chapterAdapter.update(index, if (book) null else n.seenTocCount, if (book) emptySet() else chapterAdapter.offline)
        if (!scrolledToCurrent && index != null && chapterAdapter.itemCount > 0 && binding.jump.text.isNullOrBlank()) {
            scrolledToCurrent = true
            (binding.tocList.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(index, resources.getDimensionPixelSize(R.dimen.reader_scroll_offset))
        }
        renderOwn(n)
    }

    private fun continueListening() {
        val n = novel ?: return
        val state = ReaderService.state.value
        val playingThis = state.novelId == n.id && (state.status == PlaybackStatus.PLAYING || state.status == PlaybackStatus.LOADING)
        if (!playingThis) {
            if (state.novelId == n.id && state.status == PlaybackStatus.PAUSED && state.chunks.isNotEmpty()) {
                ReaderService.send(this, ReaderService.ACTION_PLAY)
            } else {
                ReaderService.start(this, n.currentUrl, n.chunkIndex, n.id)
            }
        }
        openPlayer()
    }

    private fun listen(url: String, chunk: Int) {
        ReaderService.start(this, url, chunk, novelId)
        openPlayer()
    }

    private fun openPlayer() {
        startActivity(MainActivity.playerIntent(this))
        finish()
    }

    private fun setFollow(on: Boolean) {
        val n = novel ?: return
        binding.followSwitch.isChecked = on
        lifecycleScope.launch {
            repo.setFollow(n.id, on)
            NewChapterWorker.sync(this@NovelDetailActivity)
        }
        // Following needs a chapter list to compare against; find one now if there is none.
        if (on && n.tocUrl == null && loadJob?.isActive != true) {
            selectPane(Pane.TOC)
            load(rediscover = false)
        }
    }

    private fun showMenu(anchor: View) {
        val n = novel ?: return
        val menu = PopupMenu(this, anchor)
        if (!isBook) {
            menu.menu.add(0, MENU_RELOAD, 0, R.string.toc_reload)
            menu.menu.add(0, MENU_TOC_URL, 1, R.string.toc_change_url)
            if (n.followNew) menu.menu.add(0, MENU_CHECK_NOW, 2, R.string.follow_check_now)
        }
        menu.menu.add(0, MENU_RENAME, 2, R.string.novel_rename)
        menu.menu.add(0, MENU_DELETE, 3, R.string.novel_delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RELOAD -> {
                    selectPane(Pane.TOC)
                    load(rediscover = false)
                }
                MENU_TOC_URL -> askTocUrl()
                MENU_CHECK_NOW -> {
                    NewChapterWorker.checkNow(this)
                    Toast.makeText(this, R.string.follow_checking, Toast.LENGTH_SHORT).show()
                }
                MENU_RENAME -> NovelActions.rename(this, n)
                MENU_DELETE -> NovelActions.confirmDelete(this, n) { finish() }
            }
            true
        }
        menu.show()
    }

    // ---- Tabs -----------------------------------------------------------------------------

    private fun setupTabs(initial: String?) {
        listOf(R.string.tab_toc, R.string.tab_marks, R.string.tab_own).forEach { binding.tabs.addTab(binding.tabs.newTab().setText(it)) }
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPane(Pane.entries[tab.position])
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        selectPane(Pane.entries.firstOrNull { it.name == initial } ?: Pane.TOC)
    }

    private fun selectPane(pane: Pane) {
        binding.tabs.getTabAt(pane.ordinal)?.select()
        showPane(pane)
    }

    private fun showPane(pane: Pane) {
        binding.tocPane.isVisible = pane == Pane.TOC
        binding.marksPane.isVisible = pane == Pane.MARKS
        binding.ownPane.isVisible = pane == Pane.OWN
    }

    // ---- Mục lục --------------------------------------------------------------------------

    private fun setupToc() {
        binding.tocList.layoutManager = LinearLayoutManager(this)
        binding.tocList.adapter = chapterAdapter
        binding.jump.doAfterTextChanged { chapterAdapter.filter(it?.toString().orEmpty()) }
        binding.btnFindToc.setOnClickListener { load(rediscover = true) }
        binding.btnPasteToc.setOnClickListener { askTocUrl() }
    }

    private suspend fun loadStoredToc(n: Novel) {
        val bookId = BookUrl.bookId(n.currentUrl)
        if (bookId != null) {
            bookFormat = withContext(Dispatchers.IO) { BookStore(this@NovelDetailActivity).info(bookId)?.format }.orEmpty()
            runCatching { withContext(Dispatchers.IO) { BookStore(this@NovelDetailActivity).toc(bookId) } }
                .onSuccess { show(it) }
                .onFailure { showTocStatus(it.message ?: getString(R.string.toc_empty), busy = false, actions = false) }
            return
        }
        val stored = repo.tocEntries(n.id).map { TocParser.Entry(it.title, it.url) }
        when {
            stored.isNotEmpty() -> show(stored)
            // A list page is known but was never read: read it now. Without one, looking is left to the user.
            n.tocUrl != null -> load(rediscover = false)
            else -> showTocStatus(getString(R.string.detail_no_toc), busy = false, actions = true)
        }
    }

    private fun load(rediscover: Boolean, overrideUrl: String? = null) {
        val current = novel ?: return
        lastLoad = rediscover to overrideUrl
        loadJob?.cancel()
        showTocStatus(getString(R.string.toc_finding), busy = true, actions = false)
        loadJob = lifecycleScope.launch {
            try {
                val tocUrl = overrideUrl ?: current.tocUrl.takeUnless { rediscover } ?: discover(current)
                if (tocUrl == null) {
                    showTocStatus(getString(R.string.toc_not_found), busy = false, actions = true)
                    return@launch
                }
                val loaded = TocLoader(fetcher).load(tocUrl) { done, known ->
                    binding.tocStatus.text = getString(R.string.toc_loading_page, done, known)
                }
                if (loaded.isEmpty()) {
                    showTocStatus(getString(R.string.toc_empty), busy = false, actions = true)
                    return@launch
                }
                repo.saveToc(current.id, tocUrl, loaded)
                show(loaded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChallengeRequiredException) {
                showTocStatus(getString(R.string.verify_needed), busy = false, actions = true)
                verify.launch(VerifyActivity.intent(this@NovelDetailActivity, e.url))
            } catch (e: Exception) {
                showTocStatus(getString(R.string.toc_failed, e.message ?: e.javaClass.simpleName), busy = false, actions = true)
            }
        }
    }

    private suspend fun discover(current: Novel): String? {
        val url = current.currentUrl
        val html = fetcher.fetch(url)
        return TocParser.findTocUrl(Jsoup.parse(html, url), url, SiteConfigs.forUrl(url))
    }

    private suspend fun show(list: List<TocParser.Entry>) {
        entries = list
        binding.tocStatusGroup.isVisible = false
        binding.jump.isVisible = list.isNotEmpty()
        val saved = if (isBook) {
            emptySet()
        } else {
            withContext(Dispatchers.IO) { list.filter { chapterCache.contains(it.url) }.map { TocParser.normalize(it.url) }.toSet() }
        }
        chapterAdapter.submit(list, saved)
        chapterAdapter.filter(binding.jump.text?.toString().orEmpty())
        scrolledToCurrent = false
        render()
    }

    /** Status over an empty list; when the list is already there, a failure is only a toast. */
    private fun showTocStatus(message: String, busy: Boolean, actions: Boolean) {
        if (entries.isNotEmpty() && !busy) {
            binding.tocStatusGroup.isVisible = false
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        binding.tocStatusGroup.isVisible = true
        binding.tocProgress.isVisible = busy
        binding.tocStatus.text = message
        binding.tocActions.isVisible = actions && !isBook
    }

    private fun askTocUrl() {
        val input = EditText(this).apply {
            setText(novel?.tocUrl.orEmpty())
            hint = getString(R.string.toc_url_hint)
            setSingleLine()
        }
        val padding = resources.getDimensionPixelSize(R.dimen.dialog_padding)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.toc_change_url)
            .setMessage(R.string.toc_url_message)
            .setView(input, padding, 0, padding, 0)
            .setPositiveButton(R.string.toc_load) { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http")) {
                    selectPane(Pane.TOC)
                    load(rediscover = false, overrideUrl = url)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Đánh dấu -------------------------------------------------------------------------

    private fun setupMarks() {
        binding.marksList.layoutManager = LinearLayoutManager(this)
        binding.marksList.adapter = markAdapter
    }

    private fun showMarkMenu(mark: Bookmark, anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_MARK_LISTEN, 0, R.string.mark_listen)
        menu.menu.add(0, MENU_MARK_NOTE, 1, if (mark.note.isBlank()) R.string.mark_add_note else R.string.mark_edit_note)
        menu.menu.add(0, MENU_MARK_DELETE, 2, R.string.mark_delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_MARK_LISTEN -> listen(mark.chapterUrl, mark.chunkIndex)
                MENU_MARK_NOTE -> editNote(this, mark.id, mark.note)
                MENU_MARK_DELETE -> lifecycleScope.launch { bookmarks.delete(mark.id) }
            }
            true
        }
        menu.show()
    }

    // ---- Cài đặt riêng --------------------------------------------------------------------

    private fun setupOwn() {
        binding.ownVoiceRow.setOnClickListener { chooseVoice() }
        binding.ownSpeedRow.setOnClickListener { chooseSpeed() }
        binding.ownRulesRow.setOnClickListener {
            val n = novel ?: return@setOnClickListener
            startActivity(RulesActivity.intent(this, n.id, n.name))
        }
        binding.ownSkipRow.setOnClickListener { setSkipNotes(!binding.ownSkipSwitch.isChecked) }
        binding.ownSkipSwitch.setOnCheckedChangeListener { button, checked -> if (button.isPressed) setSkipNotes(checked) }
    }

    private fun renderOwn(n: Novel) {
        binding.ownVoiceValue.text = n.voice?.let(OnlineVoices::name) ?: getString(R.string.own_follow_global)
        binding.ownVoiceNote.isVisible = n.voice != null && globalVoice.engine == VoiceEngine.SYSTEM
        binding.ownSpeedValue.text = n.rate?.let { getString(R.string.player_speed, PlayerUi.format(it)) } ?: getString(R.string.own_follow_global)
        binding.ownSkipSwitch.isChecked = n.skipAuthorNotes
    }

    private fun chooseVoice() {
        val n = novel ?: return
        val ids = listOf<String?>(null) + OnlineVoices.all.map { it.id }
        val labels = listOf(getString(R.string.own_follow_global)) + OnlineVoices.all.map { "${it.name} · ${it.description}" }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.own_voice)
            .setSingleChoiceItems(labels.toTypedArray(), ids.indexOf(n.voice).coerceAtLeast(0)) { dialog, which ->
                lifecycleScope.launch { repo.setVoice(n.id, ids[which]) }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseSpeed() {
        val n = novel ?: return
        val rates = listOf<Float?>(null) + SPEEDS
        val labels = listOf(getString(R.string.own_follow_global)) + SPEEDS.map { getString(R.string.player_speed, PlayerUi.format(it)) }
        val checked = rates.indexOfFirst { it != null && n.rate != null && kotlin.math.abs(it - n.rate) < 0.01f }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.own_speed)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                lifecycleScope.launch { repo.setRate(n.id, rates[which]) }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setSkipNotes(on: Boolean) {
        val n = novel ?: return
        binding.ownSkipSwitch.isChecked = on
        lifecycleScope.launch { repo.setSkipAuthorNotes(n.id, on) }
    }

    // ---- Lists ----------------------------------------------------------------------------

    /** The chapter list, with what is being heard, what was heard, what is new and what is saved offline. */
    private class ChapterAdapter(private val onClick: (TocParser.Entry) -> Unit) : RecyclerView.Adapter<ChapterAdapter.Holder>() {
        private var all: List<TocParser.Entry> = emptyList()
        private var rows: List<Int> = emptyList()
        private var current: Int? = null
        private var newFrom: Int? = null
        var offline: Set<String> = emptySet()
            private set

        fun submit(entries: List<TocParser.Entry>, saved: Set<String>) {
            all = entries
            offline = saved
            rows = entries.indices.toList()
            notifyDataSetChanged()
        }

        fun update(currentIndex: Int?, seenCount: Int?, saved: Set<String>) {
            if (currentIndex == current && seenCount == newFrom && saved == offline) return
            current = currentIndex
            newFrom = seenCount
            offline = saved
            notifyDataSetChanged()
        }

        /** A number jumps to that chapter, by its place in the list or the number in its title. */
        fun filter(query: String) {
            val q = query.trim()
            rows = when {
                q.isEmpty() -> all.indices.toList()
                q.all(Char::isDigit) -> {
                    val n = q.toIntOrNull()
                    val numbered = Regex("(?<!\\d)${n}(?!\\d)")
                    all.indices.filter { i -> i + 1 == n || numbered.containsMatchIn(all[i].title) }
                }
                else -> all.indices.filter { all[it].title.contains(q, ignoreCase = true) }
            }
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemChapterBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

        inner class Holder(private val binding: ItemChapterBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(index: Int) {
                val ctx = binding.root.context
                val entry = all[index]
                val cur = current
                binding.number.text = (index + 1).toString()
                binding.title.text = entry.title
                val (tag, color) = when {
                    cur != null && index == cur -> R.string.toc_tag_current to R.color.rl_accent
                    cur != null && index < cur -> R.string.toc_tag_heard to R.color.rl_heard
                    newFrom != null && index >= newFrom!! -> R.string.toc_tag_new to R.color.rl_accent
                    TocParser.normalize(entry.url) in offline -> R.string.toc_tag_offline to R.color.rl_offline
                    else -> null to R.color.rl_text
                }
                binding.tag.isVisible = tag != null
                tag?.let { binding.tag.setText(it) }
                binding.tag.setTextColor(ContextCompat.getColor(ctx, color))
                val titleColor = when {
                    cur != null && index == cur -> R.color.rl_accent
                    cur != null && index < cur -> R.color.rl_heard
                    else -> R.color.rl_text
                }
                binding.title.setTextColor(ContextCompat.getColor(ctx, titleColor))
                binding.row.setOnClickListener { onClick(entry) }
            }
        }
    }

    private class BookmarkAdapter(
        private val onOpen: (Bookmark) -> Unit,
        private val onMenu: (Bookmark, View) -> Unit,
    ) : ListAdapter<Bookmark, BookmarkAdapter.Holder>(Diff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemBookmarkBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        inner class Holder(private val binding: ItemBookmarkBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(mark: Bookmark) {
                val ctx = binding.root.context
                binding.where.text = ctx.getString(R.string.mark_where, mark.chapterTitle, mark.chunkIndex + 1)
                binding.quote.text = "“${mark.text}”"
                binding.note.isVisible = mark.note.isNotBlank()
                binding.note.text = ctx.getString(R.string.mark_note, mark.note)
                binding.body.setOnClickListener { onOpen(mark) }
                binding.btnMenu.setOnClickListener { onMenu(mark, it) }
            }
        }

        private object Diff : DiffUtil.ItemCallback<Bookmark>() {
            override fun areItemsTheSame(a: Bookmark, b: Bookmark) = a.id == b.id
            override fun areContentsTheSame(a: Bookmark, b: Bookmark) = a == b
        }
    }

    companion object {
        private const val EXTRA_NOVEL_ID = "novel_id"
        private const val EXTRA_PANE = "pane"
        private const val KEY_PANE = "pane"
        private const val MINUTE_MS = 60_000L
        private const val MENU_RELOAD = 1
        private const val MENU_TOC_URL = 2
        private const val MENU_RENAME = 3
        private const val MENU_DELETE = 4
        private const val MENU_MARK_LISTEN = 5
        private const val MENU_MARK_NOTE = 6
        private const val MENU_MARK_DELETE = 7
        private const val MENU_CHECK_NOW = 8
        private val SPEEDS = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.75f, 2.0f)

        fun intent(context: Context, novelId: Long): Intent =
            Intent(context, NovelDetailActivity::class.java).putExtra(EXTRA_NOVEL_ID, novelId)

        /** "41 giờ", "2 giờ 15 phút" or "25 phút". */
        fun formatDuration(context: Context, ms: Long): String {
            val minutes = (ms / MINUTE_MS).toInt()
            if (minutes < 60) return context.getString(R.string.duration_minutes, minutes)
            val hours = minutes / 60
            val rest = minutes % 60
            return if (hours >= 10 || rest == 0) {
                context.getString(R.string.duration_hours, hours)
            } else {
                context.getString(R.string.duration_hours_minutes, hours, rest)
            }
        }

        /** Adds or changes the note on a bookmark. */
        fun editNote(activity: AppCompatActivity, bookmarkId: Long, current: String) {
            val input = EditText(activity).apply {
                setText(current)
                hint = activity.getString(R.string.mark_note_hint)
                setSelection(text.length)
            }
            val padding = activity.resources.getDimensionPixelSize(R.dimen.dialog_padding)
            MaterialAlertDialogBuilder(activity)
                .setTitle(if (current.isBlank()) R.string.mark_add_note else R.string.mark_edit_note)
                .setView(input, padding, 0, padding, 0)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val note = input.text.toString()
                    activity.lifecycleScope.launch { BookmarkRepository(activity).setNote(bookmarkId, note) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
