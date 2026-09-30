package com.tung.readloud.ui

import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tung.readloud.R
import com.tung.readloud.data.Novel
import com.tung.readloud.data.NovelRepository
import com.tung.readloud.data.SeriesKey
import com.tung.readloud.databinding.SheetAddBinding
import com.tung.readloud.parse.TocParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** "Thêm truyện": a link found on the clipboard, a field to paste another, a book file, or plain text. */
class AddSheet : BottomSheetDialogFragment() {

    private var _binding: SheetAddBinding? = null
    private val binding get() = _binding!!
    private val host get() = requireActivity() as MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = SheetAddBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnGo.setOnClickListener { readTyped() }
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
            readTyped()
            true
        }
        binding.btnOpenFile.setOnClickListener {
            dismiss()
            host.openBookPicker()
        }
        binding.btnPasteText.setOnClickListener {
            val text = clipboardText()?.trim()
            if (text.isNullOrEmpty()) {
                Toast.makeText(requireContext(), R.string.add_no_text, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dismiss()
            host.readPastedText(text)
        }
    }

    override fun onResume() {
        super.onResume()
        // The clipboard can only be read while the app has focus, so check each time the sheet shows.
        showClipboardLink()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun showClipboardLink() {
        val url = clipboardText()?.let(MainActivity::extractUrl)
        binding.clipCard.isVisible = url != null
        binding.orPaste.setText(if (url != null) R.string.add_or_paste else R.string.shortcut_paste_link)
        if (url == null) return
        binding.clipUrl.text = url.removePrefix("https://").removePrefix("http://")
        binding.clipTitle.text = SeriesKey.hostOf(url)
        binding.clipStatus.setText(R.string.add_new_novel)
        binding.btnReadClip.setOnClickListener {
            dismiss()
            host.startReading(url)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val novel = findInLibrary(url)
            val b = _binding ?: return@launch
            if (novel != null) {
                b.clipTitle.text = novel.name
                b.clipStatus.setText(R.string.add_in_library)
            }
        }
    }

    /**
     * The novel a chapter link belongs to, before the page is loaded: same site, and the link is its
     * current chapter or in its saved table of contents.
     */
    private suspend fun findInLibrary(url: String): Novel? {
        val repo = NovelRepository(requireContext())
        val host = SeriesKey.hostOf(url)
        val key = TocParser.normalize(url)
        val sameSite = repo.novels.first().filter { SeriesKey.hostOf(it.currentUrl) == host }
        return sameSite.firstOrNull { TocParser.normalize(it.currentUrl) == key }
            ?: sameSite.firstOrNull { novel -> repo.tocEntries(novel.id).any { TocParser.normalize(it.url) == key } }
    }

    private fun readTyped() {
        val text = binding.urlInput.text?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(requireContext(), R.string.toast_enter_url, Toast.LENGTH_SHORT).show()
            return
        }
        dismiss()
        host.startReading(text)
    }

    private fun clipboardText(): String? {
        val clip = runCatching { requireContext().getSystemService(ClipboardManager::class.java).primaryClip }.getOrNull()
        return clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(requireContext())?.toString()
    }

    companion object {
        const val TAG = "add"
    }
}
