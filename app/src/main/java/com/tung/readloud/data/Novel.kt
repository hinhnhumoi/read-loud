package com.tung.readloud.data

import androidx.room.ColumnInfo
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
    /** Time spent actually playing this novel, for "Tổng thời gian nghe". */
    @ColumnInfo(defaultValue = "0") val listenedMs: Long = 0,
    /** Check the table of contents for new chapters every morning. */
    @ColumnInfo(defaultValue = "0") val followNew: Boolean = false,
    /** Chapters the table of contents had when first saved or last caught up; entries past it are new. */
    val seenTocCount: Int? = null,
    /** Online voice for this novel only; null follows the voice settings. */
    val voice: String? = null,
    /** Speed for this novel only; null follows the voice settings. */
    val rate: Float? = null,
    /** Drop the author's note that some sites append after the chapter text. */
    @ColumnInfo(defaultValue = "0") val skipAuthorNotes: Boolean = false,
)
