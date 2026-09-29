package com.tung.readloud.tts.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Official Azure Speech REST API; same neural voices as Edge, billed to the user's own key. */
class AzureSynthesizer(
    private val client: OkHttpClient,
    private val key: String,
    private val region: String,
) : Synthesizer {

    override suspend fun synthesize(text: String, voice: String): ByteArray = withContext(Dispatchers.IO) {
        val ssml = "<speak version='1.0' xml:lang='vi-VN'><voice name='$voice'>${SsmlText.escape(text)}</voice></speak>"
        val request = Request.Builder()
            .url("https://${region.trim()}.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", key.trim())
            .header("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
            .header("User-Agent", "ReadLoud")
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Azure TTS lỗi HTTP ${response.code}")
            response.body?.bytes()?.takeIf { it.isNotEmpty() } ?: throw IOException("Azure TTS trả về rỗng")
        }
    }
}
