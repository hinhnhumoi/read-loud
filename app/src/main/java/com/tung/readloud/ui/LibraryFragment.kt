package com.tung.readloud.ui

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tung.readloud.R
import com.tung.readloud.data.LibraryItem
import com.tung.readloud.data.LibraryRepository
import com.tung.readloud.databinding.FragmentLibraryBinding
import com.tung.readloud.databinding.ItemLibraryBinding
import com.tung.readloud.tts.PlaybackStatus
import com.tung.readloud.tts.ReaderService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Every web novel and book opened so far, with how far along each one is. */
class LibraryFragment : Fragment() {

    private enum class Filter { ALL, WEB, BOOK, OFFLINE }

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val host get() = requireActivity() as MainActivity
    private val repo by lazy { LibraryRepository(requireContext()) }
    private var items: List<LibraryItem> = emptyList()
    private var filter = Filter.ALL
    private val adapter = LibraryAdapter(
        onOpen = { item -> startActivity(NovelDetailActivity.intent(requireContext(), item.novel.id)) },
        onMenu = { item, anchor -> NovelActions.showMenu(host, item.novel, anchor) },
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.search.doAfterTextChanged { render() }
        binding.filterGroup.setOnCheckedStateChangeListener { _, ids ->
            filter = when (ids.firstOrNull()) {
                R.id.filterWeb -> Filter.WEB
                R.id.filterBook -> Filter.BOOK
                R.id.filterOffline -> Filter.OFFLINE
                else -> Filter.ALL
            }
            render()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.items.combine(ReaderService.state) { list, state ->
                    list to state.novelId.takeIf { state.status == PlaybackStatus.PLAYING || state.status == PlaybackStatus.LOADING }
                }.collect { (list, activeId) ->
                    items = list
                    adapter.activeId = activeId
                    render()
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        val b = _binding ?: return
        val query = b.search.text?.toString()?.trim().orEmpty()
        val shown = items
            .filter {
                when (filter) {
                    Filter.ALL -> true
                    Filter.WEB -> !it.isBook
                    Filter.BOOK -> it.isBook
                    Filter.OFFLINE -> it.offline
                }
            }
            .filter { query.isEmpty() || it.novel.name.contains(query, true) || it.novel.currentTitle.contains(query, true) }
        adapter.submitList(shown)
        b.count.text = getString(R.string.library_count_short, items.count { !it.isBook }, items.count { it.isBook })
        b.empty.isVisible = shown.isEmpty()
        b.empty.setText(if (items.isEmpty()) R.string.library_empty else R.string.library_no_match)
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
                binding.cover.title = novel.name
                binding.name.text = novel.name
                binding.activeDot.isVisible = novel.id == activeId
                binding.tag.isVisible = item.isBook
                binding.tag.text = item.source
                binding.newBadge.isVisible = item.newCount > 0
                binding.newBadge.text = ctx.getString(R.string.badge_new, item.newCount)

                val meta = SpannableStringBuilder()
                if (item.chapterIndex != null && item.chapterCount != null) {
                    meta.append(ctx.getString(R.string.library_chapter_of, item.chapterIndex + 1, item.chapterCount))
                } else {
                    meta.append(novel.currentTitle.ifBlank { item.source })
                }
                meta.append(" · ")
                if (item.offline && !item.isBook) {
                    val start = meta.length
                    meta.append(ctx.getString(R.string.library_offline))
                    meta.setSpan(ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.rl_offline)), start, meta.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    meta.append(" · ")
                }
                meta.append(DateUtils.getRelativeTimeSpanString(novel.lastReadAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                binding.meta.text = meta

                val progress = item.progress
                binding.progressRow.isVisible = progress != null
                if (progress != null) {
                    binding.progress.setProgressCompat((progress * 1000).roundToInt(), false)
                    binding.percent.text = ctx.getString(R.string.library_percent, (progress * 100).roundToInt())
                }
                binding.noToc.isVisible = progress == null && !item.isBook
                binding.row.setOnClickListener { onOpen(item) }
                binding.row.setOnLongClickListener {
                    onMenu(item, binding.name)
                    true
                }
            }
        }

        private object Diff : DiffUtil.ItemCallback<LibraryItem>() {
            override fun areItemsTheSame(a: LibraryItem, b: LibraryItem) = a.novel.id == b.novel.id
            override fun areContentsTheSame(a: LibraryItem, b: LibraryItem) = a == b
        }
    }
}
