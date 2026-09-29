package com.tung.readloud.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.tung.readloud.R
import com.tung.readloud.data.LibraryItem
import com.tung.readloud.data.LibraryRepository
import com.tung.readloud.databinding.ActivityLibraryBinding
import com.tung.readloud.databinding.ItemLibraryBinding
import com.tung.readloud.tts.PlaybackStatus
import com.tung.readloud.tts.ReaderService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Every web novel and book opened so far, with how far along each one is. */
class LibraryActivity : AppCompatActivity() {

    private enum class Filter { ALL, WEB, BOOK }

    private lateinit var binding: ActivityLibraryBinding
    private val repo by lazy { LibraryRepository(this) }
    private var items: List<LibraryItem> = emptyList()
    private var filter = Filter.ALL
    private val adapter = LibraryAdapter(
        onOpen = { item ->
            ReaderService.start(this, item.novel.currentUrl, item.novel.chunkIndex, item.novel.id)
            finish()
        },
        onMenu = { item, anchor -> NovelActions.showMenu(this, item.novel, anchor) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.search.doAfterTextChanged { render() }
        binding.filterGroup.setOnCheckedStateChangeListener { _, ids ->
            filter = when (ids.firstOrNull()) {
                R.id.filterWeb -> Filter.WEB
                R.id.filterBook -> Filter.BOOK
                else -> Filter.ALL
            }
            render()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.items.combine(ReaderService.state) { list, state ->
                    list to state.novelId.takeIf { state.status != PlaybackStatus.IDLE }
                }.collect { (list, activeId) ->
                    items = list
                    adapter.activeId = activeId
                    render()
                }
            }
        }
    }

    private fun render() {
        val query = binding.search.text?.toString()?.trim().orEmpty()
        val shown = items
            .filter {
                when (filter) {
                    Filter.ALL -> true
                    Filter.WEB -> !it.isBook
                    Filter.BOOK -> it.isBook
                }
            }
            .filter { query.isEmpty() || it.novel.name.contains(query, true) || it.novel.currentTitle.contains(query, true) }
        adapter.submitList(shown)
        binding.toolbar.subtitle = getString(R.string.library_count, items.size, items.count { it.isBook })
        binding.empty.isVisible = shown.isEmpty()
        binding.empty.setText(if (items.isEmpty()) R.string.library_empty else R.string.library_no_match)
    }

    private class LibraryAdapter(
        private val onOpen: (LibraryItem) -> Unit,
        private val onMenu: (LibraryItem, View) -> Unit,
    ) : ListAdapter<LibraryItem, LibraryAdapter.Holder>(Diff) {

        var activeId: Long? = null
            set(value) {
                if (field == value) return
                field = value
                notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemLibraryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        inner class Holder(private val binding: ItemLibraryBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: LibraryItem) {
                val ctx = binding.root.context
                val novel = item.novel
                binding.icon.setImageResource(if (item.isBook) R.drawable.ic_book else R.drawable.ic_web)
                binding.name.text = novel.name
                binding.source.text = item.source
                binding.chapter.text = novel.currentTitle.ifBlank { novel.currentUrl }

                val parts = mutableListOf<String>()
                if (item.chapterIndex != null && item.chapterCount != null) {
                    parts += ctx.getString(R.string.library_chapter_of, item.chapterIndex + 1, item.chapterCount)
                }
                if (novel.chunkCount > 0) parts += ctx.getString(R.string.library_chunk_of, novel.chunkIndex + 1, novel.chunkCount)
                parts += DateUtils.getRelativeTimeSpanString(novel.lastReadAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                binding.meta.text = parts.joinToString(" · ")

                val progress = item.progress
                binding.progressRow.isVisible = progress != null
                if (progress != null) {
                    binding.progress.setProgressCompat((progress * 1000).roundToInt(), false)
                    binding.percent.text = ctx.getString(R.string.library_percent, (progress * 100).roundToInt())
                }
                binding.noToc.isVisible = progress == null && !item.isBook

                val active = novel.id == activeId
                binding.root.strokeWidth = if (active) ctx.resources.getDimensionPixelSize(R.dimen.active_stroke) else 0
                binding.root.strokeColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
                binding.root.setOnClickListener { onOpen(item) }
                binding.root.setOnLongClickListener {
                    onMenu(item, binding.btnMore)
                    true
                }
                binding.btnMore.setOnClickListener { onMenu(item, it) }
            }
        }

        private object Diff : DiffUtil.ItemCallback<LibraryItem>() {
            override fun areItemsTheSame(a: LibraryItem, b: LibraryItem) = a.novel.id == b.novel.id
            override fun areContentsTheSame(a: LibraryItem, b: LibraryItem) = a == b
        }
    }
}
