package com.tung.readloud.tts.speech

import android.content.Context
import java.io.File

/**
 * Audio of chapters saved for offline listening. It sits apart from [AudioCache]'s folder, so trimming the
 * cache never removes a saved chapter; once heard, a chapter's audio goes back to the cache to age out.
 */
class OfflineAudio(context: Context) {
    private val dir = File(context.filesDir, DIR).apply { mkdirs() }
    private val cacheDir = File(context.filesDir, AudioCache.DIR).apply { mkdirs() }

    /** Makes sure the audio for [text] is saved, moving it out of the cache when it was made already. */
    suspend fun keep(synthesizer: Synthesizer, voice: String, text: String): File {
        val key = AudioCache.key(voice, text)
        val target = File(dir, "$key.mp3")
        if (target.length() > 0) return target
        val cached = File(cacheDir, "$key.mp3")
        if (cached.length() > 0 && cached.renameTo(target)) return target
        val bytes = synthesizer.synthesize(text, voice)
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
        return target
    }

    fun size(key: String): Long = File(dir, "$key.mp3").length()

    /** Hands the audio back to the cache, where it ages out like anything else played. */
    fun release(keys: Collection<String>) {
        val now = System.currentTimeMillis()
        for (key in keys) {
            val file = File(dir, "$key.mp3")
            if (!file.exists()) continue
            val back = File(cacheDir, "$key.mp3")
            if (file.renameTo(back)) back.setLastModified(now) else file.delete()
        }
    }

    /** Releases files no saved chapter claims, left behind by a chapter removed while it was being saved. */
    fun releaseAllBut(claimed: Set<String>) {
        val stray = dir.listFiles()?.mapNotNull { f ->
            when {
                f.name.endsWith(".tmp") -> {
                    f.delete()
                    null
                }
                else -> f.name.removeSuffix(".mp3").takeIf { it !in claimed }
            }
        } ?: return
        release(stray)
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    companion object {
        const val DIR = "tts-offline"
    }
}
