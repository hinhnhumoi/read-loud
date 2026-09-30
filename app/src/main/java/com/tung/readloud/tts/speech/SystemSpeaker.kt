package com.tung.readloud.tts.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** The phone's own TextToSpeech engine. */
class SystemSpeaker(
    context: Context,
    enginePackage: String?,
    private val voiceName: String?,
    private val attributes: AudioAttributes,
    private val listener: Speaker.Listener,
) : Speaker {

    override val lookahead = 1

    private val main = Handler(Looper.getMainLooper())
    private val ready = CompletableDeferred<Speaker.PrepareResult>()
    private lateinit var tts: TextToSpeech
    private var ok = false
    private var volume = 1f

    /** Text length per queued utterance, to turn the engine's character ranges into a fraction. */
    private val lengths = java.util.concurrent.ConcurrentHashMap<String, Int>()

    init {
        val onInit = TextToSpeech.OnInitListener { status -> main.post { ready.complete(configure(status)) } }
        tts = if (enginePackage.isNullOrBlank()) {
            TextToSpeech(context.applicationContext, onInit)
        } else {
            TextToSpeech(context.applicationContext, onInit, enginePackage)
        }
    }

    private fun configure(status: Int): Speaker.PrepareResult {
        if (status != TextToSpeech.SUCCESS) return Speaker.PrepareResult.FAILED
        val result = tts.setLanguage(VIETNAMESE)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            return Speaker.PrepareResult.MISSING_DATA
        }
        voiceName?.let { name ->
            runCatching { tts.voices?.firstOrNull { it.name == name } }.getOrNull()?.let { tts.voice = it }
        }
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                main.post { listener.onProgress(utteranceId, 0f) }
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                val length = lengths[utteranceId] ?: return
                main.post { listener.onProgress(utteranceId, start.toFloat() / length.coerceAtLeast(1)) }
            }

            override fun onDone(utteranceId: String) {
                lengths.remove(utteranceId)
                main.post { listener.onDone(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = onError(utteranceId, -1)

            override fun onError(utteranceId: String, errorCode: Int) {
                main.post { listener.onError(utteranceId, false, "TTS error $errorCode") }
            }
        })
        ok = true
        return Speaker.PrepareResult.OK
    }

    override suspend fun prepare(): Speaker.PrepareResult =
        withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: Speaker.PrepareResult.FAILED

    override fun enqueue(utteranceId: String, text: String) {
        if (!ok) return
        lengths[utteranceId] = text.length
        val params = if (volume < 1f) Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) } else null
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }

    override fun stop() {
        lengths.clear()
        if (ok) tts.stop()
    }

    override fun applyVoice(rate: Float, pitch: Float): Boolean {
        if (ok) {
            tts.setSpeechRate(rate)
            tts.setPitch(pitch)
        }
        return false
    }

    override fun pause(): Boolean = false

    override fun resume(): Boolean = false

    /** Applies from the next chunk handed to the engine; one already queued keeps the volume it was given. */
    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun release() {
        runCatching { if (ok) tts.stop() }
        runCatching { tts.shutdown() }
        ok = false
    }

    companion object {
        val VIETNAMESE: Locale = Locale("vi", "VN")
        private const val INIT_TIMEOUT_MS = 10_000L
    }
}
