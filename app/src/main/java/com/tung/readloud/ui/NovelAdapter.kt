package com.tung.readloud.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.tung.readloud.R
import com.tung.readloud.data.Novel
import com.tung.readloud.databinding.ItemNovelBinding

class NovelAdapter(
    private val onClick: (Novel) -> Unit,
    private val onLongClick: (Novel, View) -> Unit,
) : ListAdapter<Novel, NovelAdapter.Holder>(Diff) {

    var activeId: Long? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemNovelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemNovelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(novel: Novel) {
            val ctx = binding.root.context
            binding.name.text = novel.name
            binding.chapter.text = novel.currentTitle.ifBlank { novel.currentUrl }
            val ago = DateUtils.getRelativeTimeSpanString(novel.lastReadAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            binding.meta.text = if (novel.chunkCount > 0) {
                ctx.getString(R.string.novel_meta, novel.chunkIndex + 1, novel.chunkCount, ago)
            } else {
                ctx.getString(R.string.novel_meta_no_count, novel.chunkIndex + 1, ago)
            }
            val active = novel.id == activeId
            binding.root.strokeWidth = if (active) ctx.resources.getDimensionPixelSize(R.dimen.active_stroke) else 0
            binding.root.strokeColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
            binding.activeIcon.visibility = if (active) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { onClick(novel) }
            binding.root.setOnLongClickListener {
                onLongClick(novel, it)
                true
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Novel>() {
        override fun areItemsTheSame(a: Novel, b: Novel) = a.id == b.id
        override fun areContentsTheSame(a: Novel, b: Novel) = a == b
    }
}
