package com.tung.readloud.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tung.readloud.tts.speech.OnlineVoices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "reader")

/** Voice settings and the playing flag. Reading positions live in [NovelRepository]. */
class ProgressStore(context: Context) {
    private val store = context.applicationContext.dataStore

    data class Progress(val url: String, val title: String, val chunkIndex: Int)

    val speechRate: Flow<Float> = store.data.map { it[KEY_RATE] ?: DEFAULT_RATE }
    val pitch: Flow<Float> = store.data.map { it[KEY_PITCH] ?: DEFAULT_PITCH }

    val voiceSettings: Flow<VoiceSettings> = store.data.map(::readVoice).distinctUntilChanged()

    /** Chapters to prepare ahead: [BUFFER_AUTO] lets the app choose from the network, 0 turns it off. */
    val bufferChapters: Flow<Int> = store.data.map { it[KEY_BUFFER] ?: BUFFER_AUTO }.distinctUntilChanged()

    suspend fun setBufferChapters(count: Int) {
        store.edit { it[KEY_BUFFER] = count }
    }

    suspend fun wasPlaying(): Boolean = store.data.map { it[KEY_PLAYING] ?: false }.first()

    suspend fun setPlaying(playing: Boolean) {
        store.edit { it[KEY_PLAYING] = playing }
    }

    suspend fun setSpeechRate(rate: Float) {
        store.edit { it[KEY_RATE] = rate }
    }

    suspend fun setPitch(pitch: Float) {
        store.edit { it[KEY_PITCH] = pitch }
    }

    suspend fun setVoiceSettings(settings: VoiceSettings) {
        store.edit {
            it[KEY_ENGINE] = settings.engine.name
            if (settings.systemEngine != null) it[KEY_SYS_ENGINE] = settings.systemEngine else it.remove(KEY_SYS_ENGINE)
            if (settings.systemVoice != null) it[KEY_SYS_VOICE] = settings.systemVoice else it.remove(KEY_SYS_VOICE)
            it[KEY_ONLINE_VOICE] = settings.onlineVoice
            it[KEY_AZURE_KEY] = settings.azureKey
            it[KEY_AZURE_REGION] = settings.azureRegion
        }
    }

    private fun readVoice(p: Preferences): VoiceSettings {
        val defaults = VoiceSettings()
        return VoiceSettings(
            engine = p[KEY_ENGINE]?.let { name -> VoiceEngine.entries.firstOrNull { it.name == name } } ?: defaults.engine,
            systemEngine = p[KEY_SYS_ENGINE],
            systemVoice = p[KEY_SYS_VOICE],
            onlineVoice = p[KEY_ONLINE_VOICE] ?: OnlineVoices.DEFAULT,
            azureKey = p[KEY_AZURE_KEY] ?: "",
            azureRegion = p[KEY_AZURE_REGION] ?: defaults.azureRegion,
        )
    }

    /** Position saved by the first app version, kept only for the one-time library migration. */
    suspend fun load(): Progress? = store.data.map { p ->
        val url = p[KEY_URL] ?: return@map null
        Progress(url, p[KEY_TITLE] ?: "", p[KEY_INDEX] ?: 0)
    }.first()

    suspend fun clearProgress() {
        store.edit {
            it.remove(KEY_URL)
            it.remove(KEY_TITLE)
            it.remove(KEY_INDEX)
        }
    }

    companion object {
        const val DEFAULT_RATE = 1.0f
        const val DEFAULT_PITCH = 1.0f
        const val BUFFER_AUTO = -1
        private val KEY_BUFFER = intPreferencesKey("buffer_chapters")
        private val KEY_URL = stringPreferencesKey("url")
        private val KEY_TITLE = stringPreferencesKey("title")
        private val KEY_INDEX = intPreferencesKey("chunk_index")
        private val KEY_PLAYING = booleanPreferencesKey("was_playing")
        private val KEY_RATE = floatPreferencesKey("speech_rate")
        private val KEY_PITCH = floatPreferencesKey("pitch")
        private val KEY_ENGINE = stringPreferencesKey("voice_engine")
        private val KEY_SYS_ENGINE = stringPreferencesKey("system_engine")
        private val KEY_SYS_VOICE = stringPreferencesKey("system_voice")
        private val KEY_ONLINE_VOICE = stringPreferencesKey("online_voice")
        private val KEY_AZURE_KEY = stringPreferencesKey("azure_key")
        private val KEY_AZURE_REGION = stringPreferencesKey("azure_region")
    }
}
