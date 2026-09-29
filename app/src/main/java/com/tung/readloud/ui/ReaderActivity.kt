package com.tung.readloud.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tung.readloud.R
import com.tung.readloud.databinding.ActivityReaderBinding
import com.tung.readloud.tts.PlaybackStatus
import com.tung.readloud.tts.ReaderService
import com.tung.readloud.tts.ReaderState
import kotlinx.coroutines.launch

/** Full text of the current chapter with the spoken chunk highlighted; tap a chunk to jump there. */
class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private val adapter = ChunkAdapter(
        onClick = { position ->
            ReaderService.send(this, ReaderService.ACTION_SEEK_CHUNK) { putExtra(ReaderService.EXTRA_INDEX, position) }
        },
        onRuleRequest = { selected, chunk -> RuleDialog.show(this, prefill = selected, sample = chunk) },
    )
    private var lastScrolledTo = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_reader)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_toc -> ReaderService.state.value.novelId?.let { startActivity(TocActivity.intent(this, it)) }
                R.id.action_rules -> startActivity(Intent(this, RulesActivity::class.java))
            }
            true
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnToggle.setOnClickListener {
            when (ReaderService.state.value.status) {
                PlaybackStatus.PLAYING, PlaybackStatus.LOADING -> ReaderService.send(this, ReaderService.ACTION_PAUSE)
                else -> ReaderService.send(this, ReaderService.ACTION_PLAY)
            }
        }
        binding.btnPrevChunk.setOnClickListener { ReaderService.send(this, ReaderService.ACTION_PREV_CHUNK) }
        binding.btnNextChunk.setOnClickListener { ReaderService.send(this, ReaderService.ACTION_NEXT_CHUNK) }
        binding.btnNextChapter.setOnClickListener { ReaderService.send(this, ReaderService.ACTION_NEXT) }
        binding.btnPrevChapter.setOnClickListener { ReaderService.send(this, ReaderService.ACTION_PREV_CHAPTER) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ReaderService.state.collect { render(it) }
            }
        }
    }

    private fun render(s: ReaderState) {
        val active = s.status == PlaybackStatus.PLAYING || s.status == PlaybackStatus.LOADING
        binding.toolbar.title = s.title ?: getString(R.string.no_chapter)
        binding.toolbar.subtitle = when {
            s.status == PlaybackStatus.LOADING -> s.message ?: getString(R.string.status_loading)
            s.chunkCount > 0 -> getString(R.string.progress_text, s.chunkIndex + 1, s.chunkCount)
            else -> s.message
        }
        binding.empty.isVisible = s.chunks.isEmpty()
        binding.empty.text = s.message ?: getString(R.string.reader_empty)
        adapter.submit(s.chunks, s.chunkIndex)
        if (s.chunks.isNotEmpty() && s.chunkIndex != lastScrolledTo) {
            lastScrolledTo = s.chunkIndex
            (binding.list.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(s.chunkIndex, resources.getDimensionPixelSize(R.dimen.reader_scroll_offset))
        }
        binding.btnToggle.setIconResource(if (active) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnToggle.contentDescription = getString(if (active) R.string.action_pause else R.string.action_play)
        val hasChapter = s.chunks.isNotEmpty()
        binding.btnPrevChunk.isEnabled = hasChapter
        binding.btnNextChunk.isEnabled = hasChapter
        binding.btnNextChapter.isEnabled = hasChapter
        binding.btnPrevChapter.isEnabled = hasChapter
    }
}
