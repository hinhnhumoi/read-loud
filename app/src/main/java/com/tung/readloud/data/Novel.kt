package com.tung.readloud.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "novels", indices = [Index(value = ["seriesKey"], unique = true)])
data class Novel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesKey: String,
    val name: String,
    val host: String,
    val currentUrl: String,
    val currentTitle: String,
    val chunkIndex: Int,
    val chunkCount: Int,
    val lastReadAt: Long,
    val createdAt: Long,
    val tocUrl: String? = null,
)
