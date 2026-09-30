package com.tung.readloud.ui

import android.os.SystemClock
import com.tung.readloud.data.LibraryItem
import com.tung.readloud.tts.PlaybackStatus
import com.tung.readloud.tts.ReaderState
import kotlin.math.ceil

/**
 * What the continue card, the mini player and the player show: the chapter being read, or when nothing
 * is loaded yet, the most recent novel from the library, ready to resume.
 */
data class NowPlaying(
    val state: ReaderState,
    /** The library row for the novel on screen; null when the library is empty. */
    val item: LibraryItem?,
) {
    /** A chapter is loaded in the reader, as opposed to only a novel remembered from last time. */
    val live: Boolean get() = state.status != PlaybackStatus.IDLE && (state.title != null || state.status == PlaybackStatus.LOADING)

    val hasSomething: Boolean get() = live || item != null

    val active: Boolean get() = state.status == PlaybackStatus.PLAYING || state.status == PlaybackStatus.LOADING

    val novelName: String get() = item?.novel?.name ?: state.title.orEmpty()

    val chapterTitle: String get() = if (live) state.title ?: item?.novel?.currentTitle.orEmpty() else item?.novel?.currentTitle.orEmpty()

    val chunkIndex: Int get() = if (live) state.chunkIndex else item?.novel?.chunkIndex ?: 0

    val chunkCount: Int get() = if (live) state.chunkCount else item?.novel?.chunkCount ?: 0

    /** How far into the current chunk the voice should be right now, moving on between the speaker's updates. */
    fun progressNow(now: Long = SystemClock.elapsedRealtime()): Float {
        val s = state
        if (!live || s.chunks.isEmpty()) return 0f
        if (s.status != PlaybackStatus.PLAYING || s.progressAt == 0L) return s.chunkProgress
        val length = s.chunks.getOrNull(s.chunkIndex)?.length?.coerceAtLeast(1) ?: return s.chunkProgress
        val moved = (now - s.progressAt) / 1000f * s.charsPerSecond / length
        return (s.chunkProgress + moved).coerceIn(0f, 0.98f)
    }

    /** Minutes of the chapter still to hear, rounded up; null when the chapter's text is not loaded. */
    fun chapterMinutesLeft(): Int? {
        val s = state
        if (!live || s.chunks.isEmpty() || s.charsPerSecond <= 0f) return null
        val current = s.chunks.getOrNull(s.chunkIndex)?.length ?: return null
        val rest = s.chunks.drop(s.chunkIndex + 1).sumOf { it.length }
        val seconds = (rest + current * (1f - progressNow())) / s.charsPerSecond
        return ceil(seconds / 60f).toInt().coerceAtLeast(1)
    }

    /** Share of the chapter heard, 0 to 1. */
    fun chapterFraction(): Float {
        val count = chunkCount
        if (count <= 0) return 0f
        return ((chunkIndex + if (live) progressNow() else 0f) / count).coerceIn(0f, 1f)
    }
}
