package com.tung.readloud.data

import android.content.Context
import androidx.room.withTransaction
import com.tung.readloud.model.Chapter
import com.tung.readloud.parse.TocParser
import kotlinx.coroutines.flow.Flow

/** Library of novels being listened to, one row per series with its current position. */
class NovelRepository(context: Context) {
    private val db = AppDatabase.get(context)
    private val dao = db.novels()
    private val tocDao = db.toc()
    private val legacy = ProgressStore(context)

    val novels: Flow<List<Novel>> = dao.observeAll()

    suspend fun findById(id: Long): Novel? = dao.findById(id)

    suspend fun findByKey(key: String): Novel? = dao.findByKey(key)

    suspend fun mostRecent(): Novel? {
        migrateLegacy()
        return dao.mostRecent()
    }

    /** Finds the novel this chapter belongs to, creating it on first sight. */
    suspend fun findOrCreate(chapter: Chapter, chunkIndex: Int, chunkCount: Int): Novel {
        migrateLegacy()
        val key = SeriesKey.of(chapter.url, chapter.title, chapter.pageTitle)
        dao.findByKey(key)?.let { return it }
        val now = System.currentTimeMillis()
        val novel = Novel(
            seriesKey = key,
            name = SeriesKey.displayName(chapter.url, chapter.title, chapter.pageTitle),
            host = SeriesKey.hostOf(chapter.url),
            currentUrl = chapter.url,
            currentTitle = chapter.title,
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
            lastReadAt = now,
            createdAt = now,
            tocUrl = chapter.tocUrl,
        )
        val id = dao.insert(novel)
        return novel.copy(id = id)
    }

    suspend fun updateProgress(id: Long, url: String, title: String, chunkIndex: Int, chunkCount: Int) {
        dao.updateProgress(id, url, title, chunkIndex, chunkCount, System.currentTimeMillis())
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim())

    suspend fun delete(id: Long) {
        db.withTransaction {
            tocDao.clear(id)
            dao.delete(id)
        }
    }

    suspend fun setTocUrlIfMissing(id: Long, url: String) = dao.setTocUrlIfMissing(id, url)

    suspend fun tocEntries(id: Long): List<TocEntry> = tocDao.entries(id)

    /** Replaces the stored chapter list and remembers where it came from. */
    suspend fun saveToc(id: Long, tocUrl: String, entries: List<TocParser.Entry>) {
        db.withTransaction {
            tocDao.clear(id)
            tocDao.insertAll(entries.mapIndexed { i, e -> TocEntry(id, i, e.title, e.url) })
            dao.setTocUrl(id, tocUrl)
        }
    }

    /** Carries the single saved position from the first version into the library once. */
    suspend fun migrateLegacy() {
        if (dao.count() > 0) return
        val old = legacy.load() ?: return
        val now = System.currentTimeMillis()
        dao.insert(
            Novel(
                seriesKey = SeriesKey.of(old.url, old.title, ""),
                name = SeriesKey.displayName(old.url, old.title, ""),
                host = SeriesKey.hostOf(old.url),
                currentUrl = old.url,
                currentTitle = old.title,
                chunkIndex = old.chunkIndex,
                chunkCount = 0,
                lastReadAt = now,
                createdAt = now,
            ),
        )
        legacy.clearProgress()
    }
}
