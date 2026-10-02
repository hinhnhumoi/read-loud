package com.tung.readloud.tts

enum class PlaybackStatus { IDLE, LOADING, PLAYING, PAUSED, ERROR, FINISHED }

/** Vietnamese neural voices at 1.0x say about this many characters a second, until the app has measured. */
const val DEFAULT_CHARS_PER_SECOND = 14f

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
    /** Length of the running minute timer, for drawing how much of it is left. */
    val sleepTotalMs: Long = 0,
    /** Chapter ends still to go before the sleep timer pauses; 0 when it is not counting chapters. */
    val sleepChapters: Int = 0,
    /** How far into the current chunk the voice is, 0 to 1, as of [progressAt] (elapsedRealtime). */
    val chunkProgress: Float = 0f,
    val progressAt: Long = 0,
    /** Characters of chapter text spoken per second with the current voice and speed. */
    val charsPerSecond: Float = DEFAULT_CHARS_PER_SECOND,
    /** Set when the online voice failed and the phone's voice took over. */
    val engineNote: String? = null,
    /** Chapters after the current one whose text and audio are already prepared. */
    val bufferedChapters: Int = 0,
    /** A page stuck behind a bot check that the user has to pass by hand, and where to resume after. */
    val verifyUrl: String? = null,
    val verifyResumeIndex: Int = 0,
    /** The speed in use: the novel's own when it has one, else the voice settings'. */
    val speechRate: Float = 1f,
    /** The novel being read has its own speed, so speed changes from the player are saved to it. */
    val ownRate: Boolean = false,
    /** The novel's own online voice, standing in for the one in the voice settings. */
    val voiceOverride: String? = null,
)
