package com.tung.readloud.data

import com.tung.readloud.tts.speech.OnlineVoices

enum class VoiceEngine { SYSTEM, EDGE, AZURE }

data class VoiceSettings(
    val engine: VoiceEngine = VoiceEngine.EDGE,
    /** Package of the system TTS engine; null uses the phone's default engine. */
    val systemEngine: String? = null,
    /** Voice name inside the system engine; null uses the engine's default Vietnamese voice. */
    val systemVoice: String? = null,
    val onlineVoice: String = OnlineVoices.DEFAULT,
    val azureKey: String = "",
    val azureRegion: String = "southeastasia",
    /** The online source to go back to when "only the phone voice" is turned off again. */
    val onlineEngine: VoiceEngine = VoiceEngine.EDGE,
)
