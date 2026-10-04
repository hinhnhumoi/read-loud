package com.tung.readloud.ui

import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import android.content.Intent
import android.os.Bundle
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tung.readloud.R
import com.google.android.material.snackbar.Snackbar
import com.tung.readloud.data.Bookmark
import com.tung.readloud.data.BookmarkRepository
import com.tung.readloud.data.NovelRepository
import com.tung.readloud.data.ProgressStore
import com.tung.readloud.databinding.ActivityReaderBinding
import com.tung.readloud.tts.PlaybackStatus
import com.tung.readloud.tts.ReaderService
import com.tung.readloud.tts.ReaderState
import kotlinx.coroutines.launch

/** Full text of the current chapter with the spoken chunk highlighted; tap a chunk to jump there. */
class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private val store by lazy { ProgressStore(this) }
    private val novels by lazy { NovelRepository(this) }
    private val adapter = ChunkAdapter(
        onRuleRequest = { selected, chunk -> RuleDialog.show(this, prefill = selected, sample = chunk) },
        onBookmark = { position, selected -> bookmark(position, selected) },
    )
    private var lastScrolledTo = -1

    /** The text keeps the voice in view until the reader scrolls it by hand. */
    private var following = true

    /** Chunk and character where the voice would start if asked to listen from the reader's place. */
    private var target: Pair<Int, Int>? = null
    private var chapterUrl: String? = null
    private var rate = ProgressStore.DEFAULT_RATE
    private var novelId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_reader)
        binding.toolbar.menu.findItem(R.id.action_text_size).actionView?.setOnClickListener(::chooseTextSize)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_toc -> ReaderService.state.value.novelId?.let { startActivity(NovelDetailActivity.intent(this, it)) }
                R.id.action_bookmark -> bookmark(ReaderService.state.value.chunkIndex, null)
                R.id.action_rules -> startActivity(Intent(this, RulesActivity::class.java))
            }
            true
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.itemAnimator = null
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) following = false
                if (!following) updateTarget()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!following) updateTarget()
            }
        })
        binding.btnListenHere.setOnClickListener {
            target?.let { (position, offset) -> listenFrom(position, offset) }
            follow(scroll = false)
        }
        binding.btnBackToVoice.setOnClickListener { follow(scroll = true) }

        binding.btnToggle.setOnClickListener {
            when (ReaderService.state.value.status) {
                PlaybackStatus.PLAYING, PlaybackStatus.LOADING -> ReaderService.send(this, ReaderService.ACTION_PAUSE)
                else -> ReaderService.send(this, ReaderService.ACTION_PLAY)
            }
        }
        binding.btnPrevChunk.setOnClickListener { ReaderService.send(this, ReaderService.ACTION_PREV_CHUNK) }
        binding.btnNextChunk.setOnClickListener { ReaderService.send(this, ReaderService.ACTION_NEXT_CHUNK) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ReaderService.state.collect { render(it) } }
                launch { store.readerTextSize.collect { adapter.textSize = it } }
                launch {
                    store.speechRate.collect {
                        rate = it
                        render(ReaderService.state.value)
                    }
                }
            }
        }
    }

    /** Saves the chunk, or the words selected in it, as a bookmark of the novel being read. */
    private fun bookmark(position: Int, selected: String?) {
        val s = ReaderService.state.value
        val id = s.novelId ?: return
        val url = s.url ?: return
        val chunk = s.chunks.getOrNull(position) ?: return
        val text = selected?.takeIf { it.isNotBlank() } ?: chunk
        lifecycleScope.launch {
            val markId = BookmarkRepository(this@ReaderActivity).add(
                Bookmark(novelId = id, chapterUrl = url, chapterTitle = s.title.orEmpty(), chunkIndex = position, text = text),
            )
            Snackbar.make(binding.root, R.string.mark_added, Snackbar.LENGTH_LONG)
                .setAction(R.string.mark_add_note) { NovelDetailActivity.editNote(this@ReaderActivity, markId, "") }
                .show()
        }
    }

    /** Back to following the voice: the bar and the mark go, and the text returns to the voice when asked. */
    private fun follow(scroll: Boolean) {
        following = true
        target = null
        adapter.clearMarker()
        binding.listenBar.isVisible = false
        if (scroll) {
            lastScrolledTo = -1
            render(ReaderService.state.value)
        }
    }

    /** Marks the sentence at the top of the screen and shows the bar, unless that is where the voice already is. */
    private fun updateTarget() {
        val s = ReaderService.state.value
        val found = sentenceAtReadingLine(s.chunks)
        val spokenChunk = s.chunks.getOrNull(s.chunkIndex)
        val spoken = spokenChunk?.let { s.chunkIndex to Sentences.startOf(it, (it.length * s.chunkProgress).toInt()) }
        target = found
        val show = found != null && found != spoken
        binding.listenBar.isVisible = show
        if (!show) {
            adapter.clearMarker()
            return
        }
        val (position, start) = found!!
        val text = s.chunks[position]
        val end = Sentences.split(text).firstOrNull { it.first == start }?.let { it.first + it.second.length } ?: text.length
        adapter.setMarker(position, start, end)
    }

    /**
     * The first sentence that starts on screen: where the eye is when reading by hand. A sentence cut off
     * by the top edge counts as read.
     */
    private fun sentenceAtReadingLine(chunks: List<String>): Pair<Int, Int>? {
        val list = binding.list
        val y = list.paddingTop + READING_LINE_DP * resources.displayMetrics.density
        val child = list.findChildViewUnder(list.width / 2f, y) ?: list.findChildViewUnder(list.width / 2f, y + GAP_DP * resources.displayMetrics.density)
            ?: return null
        val position = list.getChildAdapterPosition(child).takeIf { it != RecyclerView.NO_POSITION && it < chunks.size } ?: return null
        val textView = child.findViewById<TextView>(R.id.text) ?: return null
        val layout = textView.layout ?: return null
        val chunk = chunks[position]
        val inText = (y - child.top - textView.top - textView.totalPaddingTop).toInt()
        if (inText <= 0) return position to 0
        val line = layout.getLineForVertical(inText)
        val lineStart = layout.getLineStart(line)
        val start = Sentences.startOf(chunk, lineStart)
        if (layout.getLineForOffset(start) >= line) return position to start
        val next = Sentences.split(chunk).firstOrNull { it.first > lineStart }?.first
        return when {
            next != null -> position to next
            position + 1 < chunks.size -> position + 1 to 0
            else -> position to start
        }
    }

    /** Moves the voice to the sentence picked in the text, and sets it reading if it was not. */
    private fun listenFrom(position: Int, offset: Int) {
        ReaderService.send(this, ReaderService.ACTION_SEEK_CHUNK) {
            putExtra(ReaderService.EXTRA_INDEX, position)
            putExtra(ReaderService.EXTRA_OFFSET, offset)
        }
        val status = ReaderService.state.value.status
        if (status != PlaybackStatus.PLAYING && status != PlaybackStatus.LOADING) ReaderService.send(this, ReaderService.ACTION_PLAY)
    }

    private fun chooseTextSize(anchor: android.view.View) {
        val menu = PopupMenu(this, anchor)
        TEXT_SIZES.forEachIndexed { i, size -> menu.menu.add(0, i, i, "${size.toInt()} sp") }
        menu.setOnMenuItemClickListener { item ->
            lifecycleScope.launch { store.setReaderTextSize(TEXT_SIZES[item.itemId]) }
            true
        }
        menu.show()
    }

    private fun render(s: ReaderState) {
        val active = s.status == PlaybackStatus.PLAYING || s.status == PlaybackStatus.LOADING
        binding.toolbar.title = s.title ?: getString(R.string.no_chapter)
        if (s.novelId != novelId) {
            novelId = s.novelId
            lifecycleScope.launch { binding.toolbar.subtitle = s.novelId?.let { novels.findById(it)?.name } }
        }
        binding.empty.isVisible = s.chunks.isEmpty()
        binding.empty.text = s.message ?: getString(R.string.reader_empty)
        // A new chapter replaces the text the reader was in, so their place there is gone.
        if (s.url != chapterUrl) {
            chapterUrl = s.url
            if (!following) follow(scroll = false)
        }
        adapter.submit(s.chunks, s.chunkIndex)
        if (following && s.chunks.isNotEmpty() && s.chunkIndex != lastScrolledTo) {
            lastScrolledTo = s.chunkIndex
            // Keep the chunk being read about a third of the way down, with what came before in view.
            binding.list.post {
                (binding.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(s.chunkIndex, binding.list.height / 4)
            }
        }
        binding.transportLabel.text = if (s.chunkCount > 0) {
            val speed = if (s.status == PlaybackStatus.IDLE) rate else s.speechRate
            getString(R.string.player_chunk_of, s.chunkIndex + 1, s.chunkCount) + " · " + getString(R.string.player_speed, PlayerUi.format(speed))
        } else {
            s.message ?: getString(R.string.no_chapter)
        }
        val fraction = if (s.chunkCount > 0) (s.chunkIndex + s.chunkProgress) / s.chunkCount else 0f
        binding.transportProgress.setProgressCompat((fraction * 1000).toInt(), false)
        binding.btnToggle.setIconResource(if (active) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnToggle.contentDescription = getString(if (active) R.string.action_pause else R.string.action_play)
        val hasChapter = s.chunks.isNotEmpty()
        binding.btnPrevChunk.isEnabled = hasChapter
        binding.btnNextChunk.isEnabled = hasChapter
    }

    private companion object {
        val TEXT_SIZES = listOf(15f, 17f, 19f, 21f, 23f)

        /** How far below the top of the text the reading line sits. */
        const val READING_LINE_DP = 24
        const val GAP_DP = 16
    }
}
