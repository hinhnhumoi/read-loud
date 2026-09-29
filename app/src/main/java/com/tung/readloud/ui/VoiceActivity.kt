package com.tung.readloud.ui

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.tung.readloud.R
import com.tung.readloud.data.ProgressStore
import com.tung.readloud.data.VoiceEngine
import com.tung.readloud.data.VoiceSettings
import com.tung.readloud.databinding.ActivityVoiceBinding
import com.tung.readloud.tts.PlaybackStatus
import com.tung.readloud.tts.ReaderService
import com.tung.readloud.tts.speech.AzureSynthesizer
import com.tung.readloud.tts.speech.EdgeSynthesizer
import com.tung.readloud.tts.speech.Http
import com.tung.readloud.tts.speech.OnlineVoices
import com.tung.readloud.tts.speech.SystemSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** Picks where speech comes from: an online neural voice or one of the phone's TTS engines. */
class VoiceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceBinding
    private val store by lazy { ProgressStore(this) }
    private val main = Handler(Looper.getMainLooper())

    private var settings = VoiceSettings()
    private var loaded = false
    private var dirty = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var systemVoices: List<Voice> = emptyList()
    private var engines: List<TextToSpeech.EngineInfo> = emptyList()
    private var player: MediaPlayer? = null
    private var previewJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        lifecycleScope.launch {
            settings = store.voiceSettings.first()
            bindBuffer(store.bufferChapters.first())
            bindSettings()
            loaded = true
            loadSystemTts(settings.systemEngine)
        }
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
        if (dirty) {
            dirty = false
            val snapshot = settings
            runBlocking { store.setVoiceSettings(snapshot) }
            if (ReaderService.state.value.status != PlaybackStatus.IDLE) {
                ReaderService.send(this, ReaderService.ACTION_RELOAD_VOICE)
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        player?.release()
        super.onDestroy()
    }

    private fun bindSettings() {
        val engineButtons = mapOf(
            VoiceEngine.EDGE to binding.engineEdge,
            VoiceEngine.AZURE to binding.engineAzure,
            VoiceEngine.SYSTEM to binding.engineSystem,
        )
        engineButtons[settings.engine]?.isChecked = true
        binding.engineGroup.setOnCheckedChangeListener { _, checkedId ->
            val engine = engineButtons.entries.firstOrNull { it.value.id == checkedId }?.key ?: return@setOnCheckedChangeListener
            update(settings.copy(engine = engine))
            renderSections()
        }

        binding.onlineVoiceGroup.removeAllViews()
        OnlineVoices.all.forEach { voice ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = voice.label
                tag = voice.id
                minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
                isChecked = voice.id == settings.onlineVoice
            }
            binding.onlineVoiceGroup.addView(button)
        }
        binding.onlineVoiceGroup.setOnCheckedChangeListener { group, checkedId ->
            val voiceId = group.findViewById<RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            update(settings.copy(onlineVoice = voiceId))
        }

        binding.azureKey.setText(settings.azureKey)
        binding.azureRegion.setText(settings.azureRegion)
        binding.azureKey.doAfterTextChanged { update(settings.copy(azureKey = it?.toString()?.trim().orEmpty())) }
        binding.azureRegion.doAfterTextChanged { update(settings.copy(azureRegion = it?.toString()?.trim().orEmpty())) }

        binding.btnTtsSettings.setOnClickListener { openTtsSettings() }
        binding.btnPreview.setOnClickListener { preview() }
        renderSections()
    }

    /** Saved right away and applied to the running reader, unlike the voice settings that wait for onPause. */
    private fun bindBuffer(current: Int) {
        val chips = mapOf(
            ProgressStore.BUFFER_AUTO to binding.bufferAuto,
            0 to binding.bufferOff,
            1 to binding.buffer1,
            2 to binding.buffer2,
            3 to binding.buffer3,
        )
        (chips[current] ?: binding.bufferAuto).isChecked = true
        binding.bufferGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val count = chips.entries.firstOrNull { it.value.id == checkedIds.firstOrNull() }?.key ?: return@setOnCheckedStateChangeListener
            lifecycleScope.launch {
                store.setBufferChapters(count)
                if (ReaderService.state.value.status != PlaybackStatus.IDLE) {
                    ReaderService.send(this@VoiceActivity, ReaderService.ACTION_BUFFER_SETTING)
                }
            }
        }
    }

    private fun update(next: VoiceSettings) {
        if (!loaded || next == settings) return
        settings = next
        dirty = true
    }

    private fun renderSections() {
        val online = settings.engine != VoiceEngine.SYSTEM
        binding.onlineSection.isVisible = online
        binding.edgeWarning.isVisible = settings.engine == VoiceEngine.EDGE
        binding.azureSection.isVisible = settings.engine == VoiceEngine.AZURE
        binding.systemTitle.setText(if (online) R.string.voice_system_title_fallback else R.string.voice_system_title)
    }

    // ---- System engines and voices --------------------------------------------------------

    private fun loadSystemTts(enginePackage: String?) {
        tts?.shutdown()
        ttsReady = false
        binding.systemVoiceGroup.removeAllViews()
        binding.systemVoiceStatus.isVisible = true
        binding.systemVoiceStatus.setText(R.string.voice_loading)
        var created: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status -> main.post { created?.let { onSystemTtsReady(it, status) } } }
        created = if (enginePackage.isNullOrBlank()) TextToSpeech(this, listener) else TextToSpeech(this, listener, enginePackage)
        tts = created
    }

    private fun onSystemTtsReady(engine: TextToSpeech, status: Int) {
        if (engine !== tts || isDestroyed) return
        if (status != TextToSpeech.SUCCESS) {
            binding.systemVoiceStatus.setText(R.string.voice_system_failed)
            return
        }
        ttsReady = true
        engines = runCatching { engine.engines }.getOrDefault(emptyList())
        bindEngineDropdown(engine)

        val language = engine.setLanguage(SystemSpeaker.VIETNAMESE)
        systemVoices = runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "vi" }
            .sortedWith(compareBy<Voice> { it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }.thenBy { it.name })
        val missing = language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED
        binding.systemVoiceStatus.isVisible = missing || systemVoices.isEmpty()
        binding.systemVoiceStatus.setText(if (missing) R.string.voice_system_missing else R.string.voice_system_none)

        binding.systemVoiceGroup.setOnCheckedChangeListener(null)
        binding.systemVoiceGroup.removeAllViews()
        val defaultButton = voiceRadio(getString(R.string.voice_system_default), null)
        binding.systemVoiceGroup.addView(defaultButton)
        systemVoices.forEach { binding.systemVoiceGroup.addView(voiceRadio(voiceLabel(it), it.name)) }
        val selected = (0 until binding.systemVoiceGroup.childCount)
            .map { binding.systemVoiceGroup.getChildAt(it) as RadioButton }
            .firstOrNull { it.tag == settings.systemVoice } ?: defaultButton
        selected.isChecked = true
        binding.systemVoiceGroup.setOnCheckedChangeListener { group, checkedId ->
            val name = group.findViewById<RadioButton>(checkedId)?.tag as? String
            update(settings.copy(systemVoice = name))
        }
    }

    private fun bindEngineDropdown(engine: TextToSpeech) {
        val labels = listOf(getString(R.string.voice_engine_default, engine.defaultEngine ?: "")) + engines.map { it.label }
        binding.engineDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        val selectedIndex = engines.indexOfFirst { it.name == settings.systemEngine }.let { if (it < 0) 0 else it + 1 }
        binding.engineDropdown.setText(labels[selectedIndex], false)
        binding.engineDropdown.setOnItemClickListener { _, _, position, _ ->
            val pkg = if (position == 0) null else engines[position - 1].name
            if (pkg == settings.systemEngine) return@setOnItemClickListener
            update(settings.copy(systemEngine = pkg, systemVoice = null))
            loadSystemTts(pkg)
        }
    }

    private fun voiceRadio(label: String, voiceName: String?) = RadioButton(this).apply {
        id = View.generateViewId()
        text = label
        tag = voiceName
        minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
    }

    private fun voiceLabel(voice: Voice): String {
        val tags = buildList {
            if (voice.isNetworkConnectionRequired) add(getString(R.string.voice_tag_network)) else add(getString(R.string.voice_tag_offline))
            if (voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) add(getString(R.string.voice_tag_not_installed))
            if (voice.quality >= Voice.QUALITY_HIGH) add(getString(R.string.voice_tag_high_quality))
        }
        return voice.name + "\n" + tags.joinToString(" · ")
    }

    // ---- Preview --------------------------------------------------------------------------

    private fun preview() {
        stopPreview()
        when (settings.engine) {
            VoiceEngine.SYSTEM -> previewSystem()
            VoiceEngine.EDGE, VoiceEngine.AZURE -> previewOnline()
        }
    }

    private fun previewSystem() {
        val engine = tts
        if (engine == null || !ttsReady) {
            toast(getString(R.string.voice_loading))
            return
        }
        engine.setLanguage(SystemSpeaker.VIETNAMESE)
        settings.systemVoice?.let { name -> systemVoices.firstOrNull { it.name == name }?.let { engine.voice = it } }
        engine.speak(getString(R.string.voice_sample), TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    private fun previewOnline() {
        val synthesizer = when (settings.engine) {
            VoiceEngine.AZURE -> {
                if (settings.azureKey.isBlank()) {
                    toast(getString(R.string.voice_azure_need_key))
                    return
                }
                AzureSynthesizer(Http.client, settings.azureKey, settings.azureRegion)
            }
            else -> EdgeSynthesizer(Http.client)
        }
        binding.btnPreview.isEnabled = false
        binding.btnPreview.setText(R.string.voice_preview_loading)
        previewJob = lifecycleScope.launch {
            try {
                val bytes = synthesizer.synthesize(getString(R.string.voice_sample), settings.onlineVoice)
                val file = File(cacheDir, "preview.mp3")
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                player = MediaPlayer().apply {
                    setDataSource(file.path)
                    setOnCompletionListener { stopPreview() }
                    prepare()
                    start()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(getString(R.string.voice_preview_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                binding.btnPreview.isEnabled = true
                binding.btnPreview.setText(R.string.voice_preview)
            }
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        player?.release()
        player = null
        tts?.takeIf { ttsReady }?.stop()
    }

    private fun openTtsSettings() {
        val intents = listOf(Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
        for (intent in intents) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
