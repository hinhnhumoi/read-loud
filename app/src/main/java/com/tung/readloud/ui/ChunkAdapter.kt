package com.tung.readloud.ui

import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.tung.readloud.R
import com.tung.readloud.databinding.ItemChunkBinding

/**
 * Shows the chapter's TTS chunks with the one being spoken highlighted. Tap a chunk to jump there;
 * select words to add a pronunciation rule for them.
 */
class ChunkAdapter(
    private val onClick: (Int) -> Unit,
    private val onRuleRequest: (selected: String, chunk: String) -> Unit,
) : RecyclerView.Adapter<ChunkAdapter.Holder>() {

    private var chunks: List<String> = emptyList()
    var current: Int = -1
        private set

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

    fun setCurrent(newCurrent: Int) {
        if (newCurrent == current) return
        val old = current
        current = newCurrent
        if (old in chunks.indices) notifyItemChanged(old)
        if (newCurrent in chunks.indices) notifyItemChanged(newCurrent)
    }

    override fun getItemCount() = chunks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemChunkBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(chunks[position], position == current, position)

    inner class Holder(private val binding: ItemChunkBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(text: String, active: Boolean, position: Int) {
            binding.text.text = text
            binding.root.isActivated = active
            val colorAttr = if (active) {
                com.google.android.material.R.attr.colorOnPrimaryContainer
            } else {
                com.google.android.material.R.attr.colorOnSurface
            }
            binding.text.setTextColor(MaterialColors.getColor(binding.text, colorAttr))
            binding.text.setOnClickListener { onClick(position) }
            binding.text.customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.add(Menu.NONE, MENU_RULE, 0, R.string.rules_from_selection)
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    if (item.itemId != MENU_RULE) return false
                    val start = minOf(binding.text.selectionStart, binding.text.selectionEnd).coerceAtLeast(0)
                    val end = maxOf(binding.text.selectionStart, binding.text.selectionEnd).coerceAtMost(text.length)
                    if (end > start) onRuleRequest(text.substring(start, end), text)
                    mode.finish()
                    return true
                }

                override fun onDestroyActionMode(mode: ActionMode) = Unit
            }
        }
    }

    private companion object {
        const val MENU_RULE = 0x52554c45
    }
}
