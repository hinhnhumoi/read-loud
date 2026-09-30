package com.tung.readloud.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A place in a chapter worth coming back to, with the words that were on screen and an optional note. */
@Entity(tableName = "bookmarks", indices = [Index(value = ["novelId"])])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val novelId: Long,
    val chapterUrl: String,
    val chapterTitle: String,
    val chunkIndex: Int,
    val text: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE novelId = :novelId ORDER BY createdAt DESC")
    fun observe(novelId: Long): Flow<List<Bookmark>>

    @Insert
    suspend fun insert(bookmark: Bookmark): Long

    @Query("UPDATE bookmarks SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bookmarks WHERE novelId = :novelId")
    suspend fun clear(novelId: Long)
}

class BookmarkRepository(context: Context) {
    private val dao = AppDatabase.get(context).bookmarks()

    fun observe(novelId: Long): Flow<List<Bookmark>> = dao.observe(novelId)

    suspend fun add(bookmark: Bookmark): Long = dao.insert(bookmark.copy(text = bookmark.text.trim().take(MAX_TEXT)))

    suspend fun setNote(id: Long, note: String) = dao.setNote(id, note.trim())

    suspend fun delete(id: Long) = dao.delete(id)

    private companion object {
        const val MAX_TEXT = 400
    }
}
