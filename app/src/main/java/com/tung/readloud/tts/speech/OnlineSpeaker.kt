package com.tung.readloud.tts.speech

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Synthesizes chunks to cached MP3 files ahead of time and plays them gaplessly with ExoPlayer.
 * Speed and pitch are applied by the player, so cached audio stays valid when they change.
 */
class OnlineSpeaker(
    context: Context,
    private val cache: AudioCache,
    private val synthesizer: Synthesizer,
    private val voice: String,
    private val listener: Speaker.Listener,
) : Speaker {

    override val lookahead = 3

    /** One synthesized piece of a chunk, starting [startFraction] into it; the chunk is done when its [last] piece finishes. */
    private class Part(val id: String, val last: Boolean, val startFraction: Float)

    private class Pending(val part: Part, val text: String, var file: Deferred<File>, var retries: Int = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = ArrayDeque<Pending>()
    private val inPlayer = ArrayDeque<Part>()
    private var generation = 0
    private var paused = false
    private val main = Handler(Looper.getMainLooper())

    /** When the player last ran dry while the next piece was still being synthesized, or 0. */
    private var starvedSince = 0L
    private var stallReported = false
    private val stallCheck = Runnable { checkStall() }

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            false,
        )
        addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
                val finished = inPlayer.removeFirstOrNull() ?: return
                if (currentMediaItemIndex > 0) removeMediaItem(0)
                if (finished.last) notify { listener.onDone(finished.id) }
                inPlayer.firstOrNull()?.let { next -> notify { listener.onProgress(next.id, next.startFraction) } }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                val finished = inPlayer.removeFirstOrNull()
                inPlayer.clear()
                clearMediaItems()
                if (finished != null && finished.last) notify { listener.onDone(finished.id) }
                pump()
            }

            override fun onPlayerError(error: PlaybackException) {
                val index = currentMediaItemIndex
                val remaining = (index + 1 until mediaItemCount).map { getMediaItemAt(it) }
                if (index in 0 until mediaItemCount) {
                    getMediaItemAt(index).localConfiguration?.uri?.path?.let { File(it).delete() }
                }
                val failed = inPlayer.removeFirstOrNull()
                // Skip the rest of the failed chunk so the next chunk starts cleanly.
                val keep = remaining.filter { item -> failed == null || !item.mediaId.startsWith(failed.id + "#") }
                if (failed != null) {
                    inPlayer.removeAll { it.id == failed.id }
                    pending.filter { it.part.id == failed.id }.forEach { cache.release(voice, it.text) }
                    pending.removeAll { it.part.id == failed.id }
                }
                setMediaItems(keep)
                if (keep.isNotEmpty()) {
                    prepare()
                    if (!paused) play()
                }
                if (failed != null) notify { listener.onError(failed.id, false, error.message) }
                pump()
            }
        })
    }

    /** Delivers callbacks after the player callback returns, and drops them if the queue was reset meanwhile. */
    private fun notify(block: () -> Unit) {
        val gen = generation
        main.post { if (gen == generation) block() }
    }

    override suspend fun prepare(): Speaker.PrepareResult = Speaker.PrepareResult.OK

    override fun enqueue(utteranceId: String, text: String) {
        val gen = generation
        val pieces = splitForSynthesis(text)
        val total = pieces.sumOf { it.length }.coerceAtLeast(1)
        var before = 0
        pieces.forEachIndexed { i, piece ->
            val part = Part(utteranceId, i == pieces.lastIndex, before.toFloat() / total)
            before += piece.length
            val entry = Pending(part, piece, cache.request(synthesizer, voice, piece))
            pending.addLast(entry)
            awaitThenPump(entry.file, gen)
        }
    }

    private fun awaitThenPump(file: Deferred<File>, gen: Int) {
        scope.launch {
            runCatching { file.await() }
            if (gen == generation) pump()
        }
    }

    private fun pump() {
        fillPlayer()
        updateStall()
    }

    /** Hands finished files to the player in order; reports a failed chunk once the player drains. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun fillPlayer() {
        while (pending.isNotEmpty()) {
            val head = pending.first()
            if (!head.file.isCompleted) return
            val error = head.file.getCompletionExceptionOrNull()
            if (error != null && head.retries < MAX_PIECE_RETRIES) {
                // One unlucky piece should not drop the voice; ask again while earlier audio keeps playing.
                head.retries++
                head.file = cache.request(synthesizer, voice, head.text)
                awaitThenPump(head.file, generation)
                return
            }
            if (error != null) {
                if (inPlayer.isNotEmpty()) return
                pending.removeFirst()
                notify { listener.onError(head.part.id, true, error.message) }
                return
            }
            pending.removeFirst()
            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(head.file.getCompleted()))
                .setMediaId(head.part.id + "#" + System.nanoTime())
                .build()
            inPlayer.addLast(head.part)
            if (player.mediaItemCount == 0) {
                val part = head.part
                notify { listener.onProgress(part.id, part.startFraction) }
                player.setMediaItem(item)
                player.prepare()
                if (!paused) player.play()
            } else {
                player.addMediaItem(item)
            }
        }
    }

    /** Silence while waiting for synthesis is reported after a few seconds, and treated as a failure after longer. */
    private fun updateStall() {
        val starving = !paused && inPlayer.isEmpty() && pending.firstOrNull()?.file?.isCompleted == false
        if (starving) {
            if (starvedSince == 0L) {
                starvedSince = SystemClock.elapsedRealtime()
                main.postDelayed(stallCheck, STALL_NOTICE_MS)
            }
        } else {
            clearStall()
        }
    }

    private fun clearStall() {
        starvedSince = 0L
        main.removeCallbacks(stallCheck)
        if (stallReported) {
            stallReported = false
            listener.onStall(false)
        }
    }

    private fun checkStall() {
        if (starvedSince == 0L) return
        val waited = SystemClock.elapsedRealtime() - starvedSince
        if (waited >= STALL_FAIL_MS) {
            val head = pending.firstOrNull() ?: return
            starvedSince = 0L
            stallReported = false
            listener.onError(head.part.id, true, STALL_MESSAGE)
            return
        }
        if (!stallReported) {
            stallReported = true
            listener.onStall(true)
        }
        main.postDelayed(stallCheck, STALL_FAIL_MS - waited)
    }

    override fun stop() {
        generation++
        paused = false
        pending.forEach { cache.release(voice, it.text) }
        pending.clear()
        inPlayer.clear()
        player.stop()
        player.clearMediaItems()
        clearStall()
    }

    override fun applyVoice(rate: Float, pitch: Float): Boolean {
        player.playbackParameters = PlaybackParameters(rate, pitch)
        return true
    }

    override fun pause(): Boolean {
        if (inPlayer.isEmpty() && pending.isEmpty()) return false
        paused = true
        player.pause()
        clearStall()
        return true
    }

    override fun resume(): Boolean {
        if (inPlayer.isEmpty() && pending.isEmpty()) return false
        paused = false
        if (player.mediaItemCount > 0) player.play() else pump()
        return true
    }

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    override fun release() {
        stop()
        player.release()
        scope.cancel()
    }

    companion object {
        /** Online voices synthesize only a little faster than real time, so short pieces start playing sooner. */
        private const val PIECE_CHARS = 300
        private const val MAX_PIECE_RETRIES = 2
        private const val STALL_NOTICE_MS = 6_000L
        private const val STALL_FAIL_MS = 30_000L
        private const val STALL_MESSAGE = "máy chủ không phản hồi"

        /** The first piece of each chunk is about one sentence, so sound starts within a few seconds. */
        private const val FIRST_PIECE_CHARS = 120
        private val sentenceEnd = Regex("(?<=[.!?…;:][\"”’)]?)\\s+|\\n+")

        /** Splits a chunk into the pieces that are synthesized and cached; downloads must use the same split. */
        fun splitForSynthesis(text: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            for (sentence in text.split(sentenceEnd).map { it.trim() }.filter { it.isNotEmpty() }) {
                val limit = if (out.isEmpty()) FIRST_PIECE_CHARS else PIECE_CHARS
                if (sb.isNotEmpty() && sb.length + sentence.length + 1 > limit) {
                    out += sb.toString()
                    sb.setLength(0)
                }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(sentence)
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out.ifEmpty { listOf(text) }
        }
    }
}
