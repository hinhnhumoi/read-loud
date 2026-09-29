package com.tung.readloud.tts.speech

/** Plays text chunks in order and reports each one as it finishes. All calls happen on the main thread. */
interface Speaker {
    enum class PrepareResult { OK, MISSING_DATA, FAILED }

    interface Listener {
        fun onDone(utteranceId: String)

        /** [fatal] means this speaker cannot continue and the caller should switch to another one. */
        fun onError(utteranceId: String, fatal: Boolean, message: String?)

        /** Nothing is playing because the voice is still waiting for audio; false once sound resumes. */
        fun onStall(waiting: Boolean) {}
    }

    /** How many chunks beyond the current one should be queued ahead of time. */
    val lookahead: Int

    suspend fun prepare(): PrepareResult

    fun enqueue(utteranceId: String, text: String)

    /** Drops everything queued and stops sound immediately. */
    fun stop()

    /** Returns true when the new settings apply without re-queuing the current chunk. */
    fun applyVoice(rate: Float, pitch: Float): Boolean

    /** Pauses mid-chunk if supported; returns false when the caller must stop and re-queue instead. */
    fun pause(): Boolean

    fun resume(): Boolean

    fun release()
}
