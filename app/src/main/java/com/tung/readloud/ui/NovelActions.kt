package com.tung.readloud.ui

import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tung.readloud.R
import com.tung.readloud.book.BookStore
import com.tung.readloud.book.BookUrl
import com.tung.readloud.data.Novel
import com.tung.readloud.data.NovelRepository
import com.tung.readloud.follow.NewChapterWorker
import com.tung.readloud.tts.ReaderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The per-novel menu shared by the home screen and the library: details, rename, delete. */
object NovelActions {

    fun showMenu(activity: AppCompatActivity, novel: Novel, anchor: View) {
        val menu = PopupMenu(activity, anchor)
        menu.menu.add(0, DETAILS, 0, R.string.detail_title)
        menu.menu.add(0, RENAME, 1, R.string.novel_rename)
        menu.menu.add(0, DELETE, 2, R.string.novel_delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                DETAILS -> activity.startActivity(NovelDetailActivity.intent(activity, novel.id))
                RENAME -> rename(activity, novel)
                DELETE -> confirmDelete(activity, novel)
            }
            true
        }
        menu.show()
    }

    fun rename(activity: AppCompatActivity, novel: Novel) {
        val input = EditText(activity).apply {
            setText(novel.name)
            setSelectAllOnFocus(true)
        }
        val padding = activity.resources.getDimensionPixelSize(R.dimen.dialog_padding)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.novel_rename)
            .setView(input, padding, 0, padding, 0)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) activity.lifecycleScope.launch { NovelRepository(activity).rename(novel.id, name) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun confirmDelete(activity: AppCompatActivity, novel: Novel, onDeleted: () -> Unit = {}) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.novel_delete)
            .setMessage(activity.getString(R.string.novel_delete_confirm, novel.name))
            .setPositiveButton(R.string.novel_delete) { _, _ ->
                if (ReaderService.state.value.novelId == novel.id) ReaderService.send(activity, ReaderService.ACTION_STOP)
                activity.lifecycleScope.launch {
                    NovelRepository(activity).delete(novel.id)
                    BookUrl.bookId(novel.currentUrl)?.let { id -> withContext(Dispatchers.IO) { BookStore(activity).delete(id) } }
                    NewChapterWorker.sync(activity)
                    onDeleted()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private const val DETAILS = 1
    private const val RENAME = 2
    private const val DELETE = 3
}
