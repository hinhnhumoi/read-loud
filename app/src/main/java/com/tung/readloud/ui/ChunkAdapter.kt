package com.tung.readloud.ui

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
 * Shows the chapter's TTS chunks: heard ones faded, the one being spoken on an amber tint. Tap a chunk
 * to jump there; select words to add a pronunciation rule, bookmark them, or listen from that chunk.
 */
class ChunkAdapter(
    private val onClick: (Int) -> Unit,
    private val onRuleRequest: (selected: String, chunk: String) -> Unit,
    private val onListenFrom: (Int) -> Unit,
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

    inner class Holder(private val binding: ItemChunkBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(text: String, position: Int) {
            val ctx = binding.root.context
            binding.text.text = text
            binding.text.textSize = textSize
            binding.root.isActivated = position == current
            val color = when {
                position == current -> R.color.rl_text
                position < current -> R.color.rl_faint
                else -> R.color.rl_upcoming
            }
            binding.text.setTextColor(ContextCompat.getColor(ctx, color))
            binding.text.setOnClickListener { onClick(position) }
            binding.text.customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.add(Menu.NONE, MENU_RULE, 0, R.string.rules_from_selection)
                    menu.add(Menu.NONE, MENU_LISTEN, 1, R.string.reader_listen_here)
                    menu.add(Menu.NONE, MENU_BOOKMARK, 2, R.string.reader_bookmark)
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
                        MENU_LISTEN -> onListenFrom(position)
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
        const val MENU_LISTEN = 0x4c495354
        const val MENU_BOOKMARK = 0x424b4d4b
    }
}
