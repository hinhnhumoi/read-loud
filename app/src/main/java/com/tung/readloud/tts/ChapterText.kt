package com.tung.readloud.tts

import android.speech.tts.TextToSpeech
import com.tung.readloud.data.CompiledRule
import com.tung.readloud.data.Novel
import com.tung.readloud.data.ReplaceRules
import com.tung.readloud.data.VoiceEngine
import com.tung.readloud.data.VoiceSettings
import com.tung.readloud.model.Chapter
import com.tung.readloud.parse.TextChunker
import com.tung.readloud.parse.TextNormalizer
import com.tung.readloud.tts.speech.AzureSynthesizer
import com.tung.readloud.tts.speech.EdgeSynthesizer
import com.tung.readloud.tts.speech.Http
import com.tung.readloud.tts.speech.OnlineSpeaker
import com.tung.readloud.tts.speech.Synthesizer

/**
 * How a chapter becomes speech, shared by playback and offline saving: both must cut the same chunks and
 * pieces, or audio saved ahead would not be found when the chapter plays.
 */
object ChapterText {
    private const val CHUNK_TARGET_CHARS = 1000

    /** Junk lines are dropped before chunking, so the text view and the voice see the same chunks. */
    fun chunks(chapter: Chapter, skipAuthorNotes: Boolean): List<String> {
        val paragraphs = if (skipAuthorNotes) TextNormalizer.dropAuthorNotes(chapter.paragraphs) else chapter.paragraphs
        return TextChunker.chunk(TextNormalizer.clean(paragraphs), chunkSize())
    }

    /** What is actually spoken for a chunk: user rules first, then the built-in rewrites. */
    fun speech(chunk: String, rules: List<CompiledRule>): String =
        TextNormalizer.forSpeech(ReplaceRules.apply(rules, chunk)).ifBlank { chunk }

    /** The pieces an online voice synthesizes for a chunk, each cached as one audio file. */
    fun pieces(chunk: String, rules: List<CompiledRule>): List<String> = OnlineSpeaker.splitForSynthesis(speech(chunk, rules))

    private fun chunkSize(): Int = minOf(CHUNK_TARGET_CHARS, TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(200)

    /** The online voice to synthesize with, or null for the phone's own voice. */
    fun onlineSource(settings: VoiceSettings): Pair<Synthesizer, String>? = when (settings.engine) {
        VoiceEngine.SYSTEM -> null
        VoiceEngine.EDGE -> EdgeSynthesizer(Http.client) to settings.onlineVoice
        VoiceEngine.AZURE -> if (settings.azureKey.isBlank()) {
            null
        } else {
            AzureSynthesizer(Http.client, settings.azureKey, settings.azureRegion) to settings.onlineVoice
        }
    }

    /** The voice settings with the novel's own online voice; the phone's voice has no per-novel choice. */
    fun withOverrides(settings: VoiceSettings, novel: Novel?): VoiceSettings {
        val voice = novel?.voice ?: return settings
        return if (settings.engine == VoiceEngine.SYSTEM) settings else settings.copy(onlineVoice = voice)
    }
}
