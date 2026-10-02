package com.tung.readloud.ui

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
        onListenFrom = ::listenFrom,
        onBookmark = { position, selected -> bookmark(position, selected) },
    )
    private var lastScrolledTo = -1
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
        adapter.submit(s.chunks, s.chunkIndex)
        if (s.chunks.isNotEmpty() && s.chunkIndex != lastScrolledTo) {
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
    }
}
