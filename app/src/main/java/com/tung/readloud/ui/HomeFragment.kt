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
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tung.readloud.R
import com.tung.readloud.book.BookUrl
import com.tung.readloud.data.LibraryItem
import com.tung.readloud.data.LibraryRepository
import com.tung.readloud.databinding.FragmentHomeBinding
import com.tung.readloud.databinding.ItemRecentBinding
import com.tung.readloud.tts.PlaybackStatus
import com.tung.readloud.tts.ReaderService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** "Đang nghe": the novel to continue, the ones before it, and ways to add another. */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val host get() = requireActivity() as MainActivity
    private val library by lazy { LibraryRepository(requireContext()) }
    private val recentAdapter = RecentAdapter(
        onOpen = { item -> startActivity(NovelDetailActivity.intent(requireContext(), item.novel.id)) },
        onMenu = { item, anchor -> NovelActions.showMenu(host, item.novel, anchor) },
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.brand.text = SpannableStringBuilder(getString(R.string.brand_read)).apply {
            val start = length
            append(getString(R.string.brand_loud))
            setSpan(ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.rl_accent)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.cover.showTitle = true
        binding.recentList.layoutManager = LinearLayoutManager(requireContext())
        binding.recentList.adapter = recentAdapter
        binding.btnAdd.setOnClickListener { host.showAddSheet() }
        binding.btnEmptyAdd.setOnClickListener { host.showAddSheet() }
        binding.shortcutLink.setOnClickListener { host.showAddSheet() }
        binding.shortcutFile.setOnClickListener { host.openBookPicker() }
        binding.btnSeeAll.setOnClickListener { host.showTab(MainActivity.Tab.LIBRARY) }
        binding.btnContinue.setOnClickListener {
            val now = host.nowPlaying.value
            if (!now.active) PlayerUi.togglePlayback(requireContext(), now)
            host.expandPlayer()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { host.nowPlaying.collect { render(it) } }
                launch {
                    library.items.combine(host.nowPlaying.map { it.item?.novel?.id }.distinctUntilChanged()) { items, currentId ->
                        items.filter { it.novel.id != currentId }.take(RECENT_COUNT)
                    }.collect { recent ->
                        recentAdapter.submitList(recent)
                        _binding?.recentHeader?.isVisible = recent.isNotEmpty()
                    }
                }
                launch {
                    while (true) {
                        delay(1_000)
                        val now = host.nowPlaying.value
                        if (now.state.status == PlaybackStatus.PLAYING) renderTime(now)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun render(now: NowPlaying) {
        val b = _binding ?: return
        b.continueLabel.isVisible = now.hasSomething
        b.continueCard.isVisible = now.hasSomething
        b.emptyCard.isVisible = !now.hasSomething
        if (!now.hasSomething) return
        val s = now.state
        b.cover.title = now.novelName
        b.novelName.text = now.novelName
        b.chapterLine.text = if (now.chunkCount > 0) {
            getString(R.string.home_chapter_chunk, now.chapterTitle, now.chunkIndex + 1, now.chunkCount)
        } else {
            now.chapterTitle
        }
        b.bufferLine.isVisible = now.live && s.bufferedChapters > 0
        b.bufferLine.text = getString(R.string.home_buffer_ready, s.bufferedChapters)
        val note = when {
            s.status == PlaybackStatus.LOADING -> s.message
            else -> s.engineNote
        }
        b.noteLine.isVisible = now.live && !note.isNullOrBlank()
        b.noteLine.text = note
        b.btnContinue.setText(if (now.active) R.string.action_open_player else R.string.action_continue)
        b.btnContinue.setIconResource(if (now.active) R.drawable.ic_waveform else R.drawable.ic_play)
        StatusBanner.bind(b.homeBanner, now, host)
        renderTime(now)
    }

    private fun renderTime(now: NowPlaying) {
        val b = _binding ?: return
        if (!now.hasSomething) return
        b.chapterProgress.setProgressIfChanged((now.chapterFraction() * 1000).toInt())
        val minutes = now.chapterMinutesLeft()
        b.chapterLeft.setTextIfChanged(minutes?.let { getString(R.string.home_chapter_left, it) }.orEmpty())
        val story = now.item?.progress
        b.storyPercent.isVisible = story != null
        story?.let { b.storyPercent.text = getString(R.string.home_story_percent, (it * 100).roundToInt()) }
    }

    private class RecentAdapter(
        private val onOpen: (LibraryItem) -> Unit,
        private val onMenu: (LibraryItem, View) -> Unit,
    ) : ListAdapter<LibraryItem, RecentAdapter.Holder>(Diff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemRecentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        inner class Holder(private val binding: ItemRecentBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: LibraryItem) {
                val ctx = binding.root.context
                val novel = item.novel
                binding.cover.title = novel.name
                binding.name.text = novel.name
                val ago = DateUtils.getRelativeTimeSpanString(novel.lastReadAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                val index = BookUrl.index(novel.currentUrl)
                binding.meta.text = if (item.isBook && index != null && item.chapterCount != null) {
                    ctx.getString(R.string.recent_book_meta, item.source, index + 1, item.chapterCount, ago)
                } else {
                    ctx.getString(R.string.recent_meta, novel.currentTitle.ifBlank { item.source }, ago)
                }
                binding.newBadge.isVisible = item.newCount > 0
                binding.newBadge.text = ctx.getString(R.string.badge_new, item.newCount)
                binding.offline.isVisible = item.offline && !item.isBook && item.newCount == 0
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

    private companion object {
        const val RECENT_COUNT = 3
    }
}
