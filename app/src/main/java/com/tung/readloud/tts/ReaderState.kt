package com.tung.readloud.tts

enum class PlaybackStatus { IDLE, LOADING, PLAYING, PAUSED, ERROR, FINISHED }

data class DownloadProgress(
    val chaptersDone: Int,
    val chaptersTotal: Int,
    val chunksDone: Int = 0,
    val chunksTotal: Int = 0,
)

data class ReaderState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val novelId: Long? = null,
    val url: String? = null,
    val title: String? = null,
    val chunkIndex: Int = 0,
    val chunkCount: Int = 0,
    val chunks: List<String> = emptyList(),
    val message: String? = null,
    val ttsNeedsData: Boolean = false,
    /** SystemClock.elapsedRealtime() at which the sleep timer pauses playback. */
    val sleepDeadline: Long? = null,
    val sleepAfterChapter: Boolean = false,
    /** Set when the online voice failed and the phone's voice took over. */
    val engineNote: String? = null,
    val download: DownloadProgress? = null,
    /** Chapters after the current one whose text and audio are already prepared. */
    val bufferedChapters: Int = 0,
    /** A page stuck behind a bot check that the user has to pass by hand, and where to resume after. */
    val verifyUrl: String? = null,
    val verifyResumeIndex: Int = 0,
    val downloadMessage: String? = null,
)
