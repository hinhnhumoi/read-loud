package com.tung.readloud.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tung.readloud.R
import com.tung.readloud.book.BookStore
import com.tung.readloud.book.BookUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tung.readloud.data.Novel
import com.tung.readloud.data.NovelRepository
import com.tung.readloud.databinding.ActivityTocBinding
import com.tung.readloud.databinding.ItemTocBinding
import com.tung.readloud.fetch.PageFetcher
import com.tung.readloud.fetch.ChallengeRequiredException
import com.tung.readloud.fetch.TocLoader
import com.tung.readloud.parse.SiteConfigs
import com.tung.readloud.parse.TocParser
import com.tung.readloud.tts.ReaderService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jsoup.Jsoup

/** A novel's chapter list; tap a chapter to start reading from it. */
class TocActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTocBinding
    private val repo by lazy { NovelRepository(this) }
    private val fetcher by lazy { PageFetcher(this) }
    private var novel: Novel? = null
    private var entries: List<TocParser.Entry> = emptyList()
    private var loadJob: Job? = null
    private val adapter = TocAdapter { entry -> open(entry) }

    /** Arguments of the last [load], repeated once the user has passed a bot check. */
    private var lastLoad: Pair<Boolean, String?> = false to null
    private val verify = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) load(lastLoad.first, lastLoad.second)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTocBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_toc)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reload -> load(rediscover = false)
                R.id.action_change_url -> askTocUrl()
            }
            true
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.filter.doAfterTextChanged { applyFilter() }
        binding.btnPasteUrl.setOnClickListener { askTocUrl() }

        val id = intent.getLongExtra(EXTRA_NOVEL_ID, -1L)
        lifecycleScope.launch {
            val found = repo.findById(id)
            if (found == null) {
                finish()
                return@launch
            }
            novel = found
            binding.toolbar.title = found.name
            val bookId = BookUrl.bookId(found.currentUrl)
            if (bookId != null) {
                binding.toolbar.menu.clear()
                val chapters = runCatching { withContext(Dispatchers.IO) { BookStore(this@TocActivity).toc(bookId) } }
                chapters.onSuccess { show(it) }.onFailure { showNoToc(it.message ?: getString(R.string.toc_empty)) }
                binding.btnPasteUrl.isVisible = false
                return@launch
            }
            val stored = repo.tocEntries(id).map { TocParser.Entry(it.title, it.url) }
            if (stored.isEmpty()) load(rediscover = false) else show(stored)
        }
    }

    private fun load(rediscover: Boolean, overrideUrl: String? = null) {
        val current = novel ?: return
        lastLoad = rediscover to overrideUrl
        loadJob?.cancel()
        setBusy(getString(R.string.toc_finding))
        loadJob = lifecycleScope.launch {
            try {
                val tocUrl = overrideUrl ?: current.tocUrl.takeUnless { rediscover } ?: discover(current)
                if (tocUrl == null) {
                    showNoToc(getString(R.string.toc_not_found))
                    return@launch
                }
                val loaded = TocLoader(fetcher).load(tocUrl) { done, known ->
                    binding.status.text = getString(R.string.toc_loading_page, done, known)
                }
                if (loaded.isEmpty()) {
                    showNoToc(getString(R.string.toc_empty))
                    return@launch
                }
                repo.saveToc(current.id, tocUrl, loaded)
                novel = current.copy(tocUrl = tocUrl)
                show(loaded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChallengeRequiredException) {
                showNoToc(getString(R.string.verify_needed))
                verify.launch(VerifyActivity.intent(this@TocActivity, e.url))
            } catch (e: Exception) {
                showNoToc(getString(R.string.toc_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private suspend fun discover(current: Novel): String? {
        val url = current.currentUrl
        val html = fetcher.fetch(url)
        return TocParser.findTocUrl(Jsoup.parse(html, url), url, SiteConfigs.forUrl(url))
    }

    private fun show(list: List<TocParser.Entry>) {
        entries = list
        binding.progress.isVisible = false
        binding.status.isVisible = false
        binding.emptyGroup.isVisible = false
        binding.filterLayout.isVisible = true
        binding.list.isVisible = true
        binding.toolbar.subtitle = getString(R.string.toc_count, list.size)
        applyFilter()
    }

    private fun applyFilter() {
        val query = binding.filter.text?.toString()?.trim().orEmpty()
        val currentKey = novel?.currentUrl?.let(TocParser::normalize)
        val rows = entries.mapIndexed { i, e -> i to e }
            .filter { (_, e) -> query.isEmpty() || matches(e.title, query) }
        adapter.submit(rows, currentKey)
        if (query.isEmpty()) {
            val currentRow = rows.indexOfFirst { (_, e) -> TocParser.normalize(e.url) == currentKey }
            if (currentRow >= 0) {
                (binding.list.layoutManager as LinearLayoutManager)
                    .scrollToPositionWithOffset(currentRow, resources.getDimensionPixelSize(R.dimen.reader_scroll_offset))
            }
        }
    }

    /** Plain numbers match chapter numbers exactly, so "12" finds "Chương 12" but not "Chương 120". */
    private fun matches(title: String, query: String): Boolean {
        if (query.all(Char::isDigit)) return Regex("(?<!\\d)${query.toInt()}(?!\\d)").containsMatchIn(title)
        return title.contains(query, ignoreCase = true)
    }

    private fun setBusy(message: String) {
        binding.progress.isVisible = true
        binding.status.isVisible = true
        binding.status.text = message
        binding.emptyGroup.isVisible = false
    }

    private fun showNoToc(message: String) {
        binding.progress.isVisible = false
        binding.status.isVisible = false
        binding.emptyGroup.isVisible = entries.isEmpty()
        binding.emptyText.text = message
        if (entries.isNotEmpty()) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
                if (url.startsWith("http")) load(rediscover = false, overrideUrl = url)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun open(entry: TocParser.Entry) {
        val id = novel?.id ?: return
        ReaderService.start(this, entry.url, 0, id)
        finish()
    }

    private class TocAdapter(private val onClick: (TocParser.Entry) -> Unit) : RecyclerView.Adapter<TocAdapter.Holder>() {
        private var rows: List<Pair<Int, TocParser.Entry>> = emptyList()
        private var currentKey: String? = null

        fun submit(newRows: List<Pair<Int, TocParser.Entry>>, key: String?) {
            rows = newRows
            currentKey = key
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemTocBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (index, entry) = rows[position]
            holder.bind(index, entry, TocParser.normalize(entry.url) == currentKey)
        }

        inner class Holder(private val binding: ItemTocBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(index: Int, entry: TocParser.Entry, current: Boolean) {
                binding.number.text = (index + 1).toString()
                binding.title.text = entry.title
                binding.root.isActivated = current
                val color = if (current) {
                    com.google.android.material.R.attr.colorOnPrimaryContainer
                } else {
                    com.google.android.material.R.attr.colorOnSurface
                }
                binding.title.setTextColor(MaterialColors.getColor(binding.title, color))
                binding.root.setOnClickListener { onClick(entry) }
            }
        }
    }

    companion object {
        private const val EXTRA_NOVEL_ID = "novel_id"

        fun intent(context: Context, novelId: Long): Intent =
            Intent(context, TocActivity::class.java).putExtra(EXTRA_NOVEL_ID, novelId)
    }
}
