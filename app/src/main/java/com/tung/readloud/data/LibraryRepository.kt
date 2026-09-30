package com.tung.readloud.data

import android.content.Context
import com.tung.readloud.book.BookStore
import com.tung.readloud.book.BookUrl
import com.tung.readloud.parse.TocParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** A library row: the novel plus where it sits among its chapters, when that is known. */
data class LibraryItem(
    val novel: Novel,
    val isBook: Boolean,
    /** Site host for web novels, "EPUB", "TXT" or "PDF" for books. */
    val source: String,
    /** Zero-based position of the current chapter, when the chapter list is known. */
    val chapterIndex: Int?,
    val chapterCount: Int?,
    /** The current chapter can be read without a connection: a local book, or a saved web chapter. */
    val offline: Boolean = isBook,
    /** Chapters published since the list was last caught up with, not yet listened past. */
    val newCount: Int = 0,
) {
    /** Overall progress from 0 to 1, counting the part of the current chapter already heard. */
    val progress: Float?
        get() {
            val count = chapterCount?.takeIf { it > 0 } ?: return null
            val index = chapterIndex ?: return null
            val inChapter = if (novel.chunkCount > 0) novel.chunkIndex.toFloat() / novel.chunkCount else 0f
            return ((index + inChapter) / count).coerceIn(0f, 1f)
        }
}

/** Chapters past both the last list the user had seen and the one they are on. */
fun newChapters(novel: Novel, tocSize: Int, currentIndex: Int?): Int {
    val seen = novel.seenTocCount ?: return 0
    return (tocSize - maxOf(seen, (currentIndex ?: -1) + 1)).coerceAtLeast(0)
}

class LibraryRepository(context: Context) {
    private val db = AppDatabase.get(context)
    private val books = BookStore(context)
    private val chapters = ChapterCache(context)

    val items: Flow<List<LibraryItem>> = db.novels().observeAll()
        .map { novels: List<Novel> -> novels.map { toItem(it) } }
        .flowOn(Dispatchers.IO)

    private suspend fun toItem(novel: Novel): LibraryItem {
        val bookId = BookUrl.bookId(novel.currentUrl)
        if (bookId != null) {
            val info = books.info(bookId)
            return LibraryItem(
                novel = novel,
                isBook = true,
                source = info?.format ?: "SÁCH",
                chapterIndex = BookUrl.index(novel.currentUrl),
                chapterCount = info?.chapterCount,
            )
        }
        val entries = db.toc().entries(novel.id)
        val current = TocParser.normalize(novel.currentUrl)
        val index = entries.indexOfFirst { TocParser.normalize(it.url) == current }.takeIf { it >= 0 }
        return LibraryItem(
            novel = novel,
            isBook = false,
            source = novel.host,
            chapterIndex = index,
            chapterCount = entries.size.takeIf { it > 0 },
            offline = chapters.contains(novel.currentUrl),
            newCount = newChapters(novel, entries.size, index),
        )
    }
}
