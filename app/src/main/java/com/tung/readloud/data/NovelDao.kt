package com.tung.readloud.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NovelDao {
    @Query("SELECT * FROM novels ORDER BY lastReadAt DESC")
    fun observeAll(): Flow<List<Novel>>

    @Query("SELECT * FROM novels WHERE seriesKey = :key LIMIT 1")
    suspend fun findByKey(key: String): Novel?

    @Query("SELECT * FROM novels WHERE id = :id")
    suspend fun findById(id: Long): Novel?

    @Query("SELECT * FROM novels ORDER BY lastReadAt DESC LIMIT 1")
    suspend fun mostRecent(): Novel?

    @Query("SELECT COUNT(*) FROM novels")
    suspend fun count(): Int

    @Insert
    suspend fun insert(novel: Novel): Long

    @Query(
        "UPDATE novels SET currentUrl = :url, currentTitle = :title, chunkIndex = :chunkIndex, " +
            "chunkCount = :chunkCount, lastReadAt = :at WHERE id = :id",
    )
    suspend fun updateProgress(id: Long, url: String, title: String, chunkIndex: Int, chunkCount: Int, at: Long)

    @Query("UPDATE novels SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE novels SET tocUrl = :url WHERE id = :id AND tocUrl IS NULL")
    suspend fun setTocUrlIfMissing(id: Long, url: String)

    @Query("UPDATE novels SET tocUrl = :url WHERE id = :id")
    suspend fun setTocUrl(id: Long, url: String?)

    @Query("DELETE FROM novels WHERE id = :id")
    suspend fun delete(id: Long)
}
