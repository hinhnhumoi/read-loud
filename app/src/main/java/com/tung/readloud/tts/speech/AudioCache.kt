package com.tung.readloud.tts.speech

import android.content.Context
import com.tung.readloud.data.EventLog
import com.tung.readloud.data.Hashing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Synthesized audio on disk, keyed by voice and text. Concurrent requests for the same chunk share one
 * synthesis, and at most [MAX_PARALLEL] syntheses run at once. A synthesis nobody wants any more is
 * cancelled, so audio for text the listener skipped past does not hold up what plays next.
 *
 * Background requests (buffering chapters ahead) use at most one slot and only while no playback request
 * is waiting, so they never delay the voice that is speaking. Audio saved for offline listening lives in
 * [OfflineAudio]'s folder, is found first, and is never trimmed.
 */
class AudioCache(context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, DIR).apply { mkdirs() }
    private val savedDir = File(context.filesDir, OfflineAudio.DIR)
    private val inflight = HashMap<String, Entry>()
    private val limiter = Semaphore(MAX_PARALLEL)
    private val backgroundLimiter = Semaphore(1)

    /** Playback requests not finished yet, waiting for a slot or synthesizing. */
    private val foregroundActive = AtomicInteger(0)

    private class Entry(val job: Deferred<File>, var users: Int, val urgent: AtomicBoolean)

    fun cachedFile(voice: String, text: String): File? {
        val key = key(voice, text)
        return File(savedDir, "$key.mp3").takeIf { it.length() > 0 } ?: File(dir, "$key.mp3").takeIf { it.length() > 0 }
    }

    /** Each call counts as one user of the synthesis until it is [release]d or finishes. */
    fun request(synthesizer: Synthesizer, voice: String, text: String, background: Boolean = false): Deferred<File> =
        synchronized(inflight) {
            val key = key(voice, text)
            File(savedDir, "$key.mp3").takeIf { it.length() > 0 }?.let { return CompletableDeferred(it) }
            val file = File(dir, "$key.mp3")
            if (file.length() > 0) {
                file.setLastModified(System.currentTimeMillis())
                return CompletableDeferred(file)
            }
            inflight[key]?.let {
                it.users++
                // Playback now needs what the buffer started: stop yielding for it.
                if (!background) it.urgent.set(true)
                return it.job
            }
            val urgent = AtomicBoolean(!background)
            if (!background) foregroundActive.incrementAndGet()
            val job = scope.async(Dispatchers.IO) {
                val asked = System.currentTimeMillis()
                try {
                    if (background) {
                        backgroundLimiter.withPermit {
                            while (!urgent.get() && foregroundActive.get() > 0) delay(YIELD_POLL_MS)
                            limiter.withPermit { synthesize(synthesizer, voice, text, key, file, null) }
                        }
                    } else {
                        limiter.withPermit { synthesize(synthesizer, voice, text, key, file, asked) }
                    }
                } finally {
                    if (!background) foregroundActive.decrementAndGet()
                    val self = coroutineContext.job
                    synchronized(inflight) { if (inflight[key]?.job === self) inflight.remove(key) }
                }
            }
            inflight[key] = Entry(job, 1, urgent)
            job
        }

    /** [asked] is when playback asked for this audio; background requests wait by design and pass null. */
    private suspend fun synthesize(synthesizer: Synthesizer, voice: String, text: String, key: String, file: File, asked: Long?): File {
        val waited = asked?.let { System.currentTimeMillis() - it } ?: 0L
        if (waited > SLOW_SLOT_MS) EventLog.log("Voice: waited $waited ms for a synthesis slot")
        val bytes = synthesizer.synthesize(text, voice)
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
        return file
    }

    /** Makes sure [text] is on disk, at background priority; cancelling the caller gives up its claim. */
    suspend fun prefetch(synthesizer: Synthesizer, voice: String, text: String): File {
        val deferred = request(synthesizer, voice, text, background = true)
        try {
            return deferred.await()
        } catch (e: CancellationException) {
            release(voice, text)
            throw e
        }
    }

    /** One user no longer needs this text; the synthesis is cancelled when that was the last one. */
    fun release(voice: String, text: String) {
        synchronized(inflight) {
            val key = key(voice, text)
            val entry = inflight[key] ?: return
            entry.users--
            if (entry.users <= 0) {
                inflight.remove(key)
                entry.job.cancel()
            }
        }
    }

    /** Deletes the least recently used files until the cache fits in [maxBytes]. */
    fun trim(maxBytes: Long = MAX_BYTES) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        var total = files.filter { it.exists() }.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.filter { it.exists() }.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= maxBytes) break
        }
    }

    companion object {
        const val DIR = "tts-audio"

        fun key(voice: String, text: String) = Hashing.sha1("$voice\n$text")

        private const val MAX_PARALLEL = 2
        private const val SLOW_SLOT_MS = 3_000L
        private const val YIELD_POLL_MS = 300L
        private const val MAX_BYTES = 800L * 1024 * 1024
    }
}
