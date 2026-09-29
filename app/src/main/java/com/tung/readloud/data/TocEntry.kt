package com.tung.readloud.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query

@Entity(tableName = "toc_entries", primaryKeys = ["novelId", "position"])
data class TocEntry(
    val novelId: Long,
    val position: Int,
    val title: String,
    val url: String,
)

@Dao
interface TocDao {
    @Query("SELECT * FROM toc_entries WHERE novelId = :novelId ORDER BY position")
    suspend fun entries(novelId: Long): List<TocEntry>

    @Query("DELETE FROM toc_entries WHERE novelId = :novelId")
    suspend fun clear(novelId: Long)

    @Insert
    suspend fun insertAll(entries: List<TocEntry>)
}
