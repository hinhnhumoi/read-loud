package com.tung.readloud.ui

import android.widget.TextView
import android.text.style.ForegroundColorSpan
import android.text.Spanned
import android.text.Spannable
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tung.readloud.R
import com.tung.readloud.databinding.ItemChunkBinding

/**
 * Shows the chapter's TTS chunks: heard ones faded, the one being spoken on an amber tint, and the sentence
 * the voice would start from when the reader has scrolled away. A long press selects words to bookmark or
 * add a pronunciation rule for; a plain tap does nothing, so scrolling through the text never moves the voice.
 */
class ChunkAdapter(
    private val onRuleRequest: (selected: String, chunk: String) -> Unit,
    private val onBookmark: (position: Int, selected: String) -> Unit,
) : RecyclerView.Adapter<ChunkAdapter.Holder>() {

    private var chunks: List<String> = emptyList()
    var current: Int = -1
        private set

    var textSize: Float = 17f
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Chunk and character range of the marked sentence. */
    private var marker: Triple<Int, Int, Int>? = null

    /**
     * Marks a sentence, or clears the mark with null. Rows on screen are recoloured in place: rebinding a
     * long selectable text while it is being scrolled makes the list stutter and can swallow the swipe.
     */
    fun mark(list: RecyclerView, next: Triple<Int, Int, Int>?) {
        val old = marker
        if (old == next) return
        marker = next
        setOf(old?.first, next?.first).filterNotNull().forEach { position ->
            (list.findViewHolderForAdapterPosition(position) as? Holder)?.applyMarker(position)
        }
    }

    fun submit(newChunks: List<String>, newCurrent: Int) {
        val changed = newChunks != chunks
        chunks = newChunks
        if (changed) {
            current = newCurrent
            notifyDataSetChanged()
            return
        }
        setCurrent(newCurrent)
    }

    /** Every row changes colour when the position moves, since heard and upcoming chunks look different. */
    fun setCurrent(newCurrent: Int) {
        if (newCurrent == current) return
        val low = minOf(current, newCurrent).coerceAtLeast(0)
        val high = maxOf(current, newCurrent).coerceAtMost(chunks.size - 1)
        current = newCurrent
        if (high >= low) notifyItemRangeChanged(low, high - low + 1)
    }

    override fun getItemCount() = chunks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemChunkBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(chunks[position], position)

    /** Our colour on the marked sentence, told apart from any other span on the text. */
    private class MarkSpan(color: Int) : ForegroundColorSpan(color)

    inner class Holder(private val binding: ItemChunkBinding) : RecyclerView.ViewHolder(binding.root) {
        /** Amber, like the phrase being spoken in the player: it reads on any chunk's background. */
        fun applyMarker(position: Int) {
            val spannable = binding.text.text as? Spannable ?: return
            spannable.getSpans(0, spannable.length, MarkSpan::class.java).forEach(spannable::removeSpan)
            val mark = marker?.takeIf { it.first == position && it.third <= spannable.length && it.second < it.third } ?: return
            val color = ContextCompat.getColor(binding.root.context, R.color.rl_accent)
            spannable.setSpan(MarkSpan(color), mark.second, mark.third, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        fun bind(text: String, position: Int) {
            val ctx = binding.root.context
            binding.text.setText(text, TextView.BufferType.SPANNABLE)
            applyMarker(position)
            binding.text.textSize = textSize
            binding.root.isActivated = position == current
            val color = when {
                position == current -> R.color.rl_text
                position < current -> R.color.rl_faint
                else -> R.color.rl_upcoming
            }
            binding.text.setTextColor(ContextCompat.getColor(ctx, color))
            binding.text.customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    // The app's own items first; the system's follow, minus the ones of no use here.
                    menu.add(Menu.NONE, MENU_BOOKMARK, 1, R.string.reader_bookmark)
                    menu.add(Menu.NONE, MENU_RULE, 2, R.string.rules_from_selection)
                    menu.removeItem(android.R.id.selectAll)
                    menu.removeItem(android.R.id.shareText)
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    when (item.itemId) {
                        MENU_RULE -> {
                            val start = minOf(binding.text.selectionStart, binding.text.selectionEnd).coerceAtLeast(0)
                            val end = maxOf(binding.text.selectionStart, binding.text.selectionEnd).coerceAtMost(text.length)
                            if (end > start) onRuleRequest(text.substring(start, end), text)
                        }
                        MENU_BOOKMARK -> {
                            val start = minOf(binding.text.selectionStart, binding.text.selectionEnd).coerceAtLeast(0)
                            val end = maxOf(binding.text.selectionStart, binding.text.selectionEnd).coerceAtMost(text.length)
                            onBookmark(position, if (end > start) text.substring(start, end) else "")
                        }
                        else -> return false
                    }
                    mode.finish()
                    return true
                }

                override fun onDestroyActionMode(mode: ActionMode) = Unit
            }
        }
    }

    private companion object {
        const val MENU_RULE = 0x52554c45
        const val MENU_BOOKMARK = 0x424b4d4b
    }
}
