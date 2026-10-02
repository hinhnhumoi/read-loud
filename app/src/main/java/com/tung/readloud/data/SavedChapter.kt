package com.tung.readloud.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A chapter kept for offline listening: its text, and its audio when an online voice reads it. It stays
 * until it has gone unused for the days set in the settings, or the user removes it.
 */
@Entity(tableName = "saved_chapters", primaryKeys = ["novelId", "url"])
data class SavedChapter(
    val novelId: Long,
    val url: String,
    val title: String,
    /** Place in the novel's chapter list, or -1 when the list is not known. */
    val position: Int,
    val state: Int = QUEUED,
    /** The online voice the audio was made with; empty when only the text was saved. */
    val voice: String = "",
    /** Audio files of the chapter, so they can be let go once it has been heard. */
    val keys: String = "",
    val piecesDone: Int = 0,
    val piecesTotal: Int = 0,
    val bytes: Long = 0,
    val attempts: Int = 0,
    val error: String? = null,
    /** Order in the queue; a chapter retried goes behind the others. */
    val queuedAt: Long = System.currentTimeMillis(),
    /** When it was last saved or played; one left alone long enough is removed. */
    @ColumnInfo(defaultValue = "0") val lastUsedAt: Long = System.currentTimeMillis(),
) {
    val keyList: List<String> get() = keys.split(',').filter { it.isNotEmpty() }

    companion object {
        const val QUEUED = 0
        const val SAVING = 1
        const val DONE = 2
        const val FAILED = 3
    }
}

@Dao
interface SavedChapterDao {
    @Query("SELECT * FROM saved_chapters WHERE novelId = :novelId")
    fun observe(novelId: Long): Flow<List<SavedChapter>>

    @Query("SELECT * FROM saved_chapters")
    fun observeAll(): Flow<List<SavedChapter>>

    @Query("SELECT * FROM saved_chapters WHERE state IN (0, 1) ORDER BY state DESC, queuedAt, position LIMIT 1")
    suspend fun nextPending(): SavedChapter?

    @Query("SELECT * FROM saved_chapters WHERE novelId = :novelId AND url = :url")
    suspend fun find(novelId: Long, url: String): SavedChapter?

    @Query("SELECT * FROM saved_chapters WHERE novelId = :novelId")
    suspend fun forNovel(novelId: Long): List<SavedChapter>

    @Query("SELECT * FROM saved_chapters")
    suspend fun all(): List<SavedChapter>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(rows: List<SavedChapter>)

    @Update
    suspend fun update(row: SavedChapter)

    @Query("UPDATE saved_chapters SET state = 0, attempts = 0, error = NULL, queuedAt = :now WHERE novelId = :novelId AND url = :url AND state = 3")
    suspend fun retry(novelId: Long, url: String, now: Long)

    @Query("UPDATE saved_chapters SET state = 0 WHERE state = 1")
    suspend fun resetInterrupted()

    @Query("UPDATE saved_chapters SET lastUsedAt = :now WHERE novelId = :novelId AND url = :url")
    suspend fun touch(novelId: Long, url: String, now: Long)

    /** Finished or failed chapters not used since [before]; ones still waiting to be saved are kept. */
    @Query("SELECT * FROM saved_chapters WHERE state IN (2, 3) AND lastUsedAt < :before")
    suspend fun unusedSince(before: Long): List<SavedChapter>

    @Query("DELETE FROM saved_chapters WHERE novelId = :novelId AND url = :url")
    suspend fun delete(novelId: Long, url: String)

    @Query("DELETE FROM saved_chapters WHERE state IN (0, 1, 3)")
    suspend fun dropUnfinished()

    @Query("DELETE FROM saved_chapters WHERE novelId = :novelId")
    suspend fun clear(novelId: Long)

    @Query("DELETE FROM saved_chapters")
    suspend fun clearAll()
}
