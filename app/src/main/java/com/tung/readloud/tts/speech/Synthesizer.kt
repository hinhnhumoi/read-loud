package com.tung.readloud.tts.speech

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Turns text into MP3 audio using an online voice. */
interface Synthesizer {
    suspend fun synthesize(text: String, voice: String): ByteArray
}

data class OnlineVoice(val id: String, val label: String)

object OnlineVoices {
    const val DEFAULT = "vi-VN-HoaiMyNeural"
    val all = listOf(
        OnlineVoice("vi-VN-HoaiMyNeural", "HoaiMy (nữ)"),
        OnlineVoice("vi-VN-NamMinhNeural", "NamMinh (nam)"),
    )

    fun label(id: String): String = all.firstOrNull { it.id == id }?.label ?: id
}

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

internal object SsmlText {
    private val control = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")

    fun escape(text: String): String = control.replace(text, " ")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
