package com.tung.readloud.ui

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tung.readloud.R
import com.tung.readloud.data.ProgressStore
import com.tung.readloud.data.RuleRepository
import com.tung.readloud.data.VoiceEngine
import com.tung.readloud.data.VoiceSettings
import com.tung.readloud.databinding.FragmentSettingsBinding
import com.tung.readloud.databinding.ItemVoiceBinding
import com.tung.readloud.tts.PlaybackStatus
import com.tung.readloud.tts.ReaderService
import com.tung.readloud.tts.speech.AzureSynthesizer
import com.tung.readloud.tts.speech.EdgeSynthesizer
import com.tung.readloud.tts.speech.Http
import com.tung.readloud.tts.speech.OnlineVoice
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where speech comes from and how it sounds. Voice changes are saved when the tab is left, since
 * applying each one restarts the chunk being read; speed, pitch and buffering apply at once.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val store by lazy { ProgressStore(requireContext()) }
    private val rules by lazy { RuleRepository(requireContext()) }
    private val main = Handler(Looper.getMainLooper())

    private var settings = VoiceSettings()
    private var loaded = false
    private var dirty = false
    private var systemExpanded = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var systemVoices: List<Voice> = emptyList()
    private var engines: List<TextToSpeech.EngineInfo> = emptyList()
    private var player: MediaPlayer? = null
    private var previewJob: Job? = null
    private val voiceCards = mutableMapOf<String, ItemVoiceBinding>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val version = runCatching {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull().orEmpty()
        binding.version.text = getString(R.string.drawer_version, version)
        binding.rowRules.setOnClickListener { startActivity(Intent(requireContext(), RulesActivity::class.java)) }
        binding.rowHealth.setOnClickListener { Dialogs.showBattery(requireContext()) }
        binding.rowLog.setOnClickListener { Dialogs.showEventLog(requireContext()) }
        binding.systemToggleRow.setOnClickListener {
            systemExpanded = !systemExpanded
            renderSections()
        }
        binding.btnTtsSettings.setOnClickListener { Dialogs.openTtsSettings(requireContext()) }
        binding.btnPreviewSystem.setOnClickListener { previewSystem() }
        bindStyle()

        viewLifecycleOwner.lifecycleScope.launch {
            settings = store.voiceSettings.first()
            bindSettings()
            bindBuffer(store.bufferChapters.first())
            loaded = true
            loadSystemTts(settings.systemEngine)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                rules.rulesFor(null).collect { list ->
                    val enabled = list.filter { it.enabled }
                    val sample = enabled.take(2).joinToString(", ") { "${it.pattern} → ${it.replacement.ifBlank { "∅" }}" }
                    _binding?.rulesSummary?.text = when {
                        enabled.isEmpty() -> getString(R.string.settings_rules_none)
                        else -> getString(R.string.settings_rules_sample, enabled.size, sample)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderHealth()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) commitVoice() else renderHealth()
    }

    override fun onPause() {
        super.onPause()
        commitVoice()
    }

    override fun onDestroyView() {
        stopPreview()
        tts?.shutdown()
        tts = null
        player?.release()
        _binding = null
        super.onDestroyView()
    }

    /** Saves a changed voice and has the reader switch to it. */
    private fun commitVoice() {
        stopPreview()
        if (!dirty) return
        dirty = false
        val snapshot = settings
        runBlocking { store.setVoiceSettings(snapshot) }
        if (ReaderService.state.value.status != PlaybackStatus.IDLE) {
            ReaderService.send(requireContext(), ReaderService.ACTION_RELOAD_VOICE)
        }
    }

    private fun renderHealth() {
        val b = _binding ?: return
        val ok = Dialogs.isBatteryUnrestricted(requireContext())
        b.healthSummary.setText(if (ok) R.string.settings_health_ok else R.string.settings_health_bad)
        b.healthSummary.setTextColor(ContextCompat.getColor(requireContext(), if (ok) R.color.rl_offline else R.color.rl_warn))
    }

    // ---- Voice source ---------------------------------------------------------------------

    private fun bindSettings() {
        val engineOrder = listOf(VoiceEngine.EDGE, VoiceEngine.AZURE, VoiceEngine.SYSTEM)
        binding.engineSegment.setOptions(
            listOf(getString(R.string.settings_engine_edge), getString(R.string.settings_engine_azure), getString(R.string.settings_engine_system)),
        )
        binding.engineSegment.select(engineOrder.indexOf(settings.engine))
        binding.engineSegment.onSelected = { i ->
            update(settings.copy(engine = engineOrder[i]))
            renderSections()
        }

        binding.onlineVoices.removeAllViews()
        voiceCards.clear()
        OnlineVoices.all.forEach { voice -> addVoiceCard(voice) }

        binding.azureKey.setText(settings.azureKey)
        binding.azureRegion.setText(settings.azureRegion)
        binding.azureKey.doAfterTextChanged { update(settings.copy(azureKey = it?.toString()?.trim().orEmpty())) }
        binding.azureRegion.doAfterTextChanged { update(settings.copy(azureRegion = it?.toString()?.trim().orEmpty())) }
        renderSections()
    }

    private fun addVoiceCard(voice: OnlineVoice) {
        val card = ItemVoiceBinding.inflate(layoutInflater, binding.onlineVoices, false)
        card.voiceName.text = voice.name
        card.voiceDesc.text = voice.description
        card.voicePlay.contentDescription = getString(R.string.settings_voice_play, voice.name)
        card.voiceCard.setOnClickListener {
            update(settings.copy(onlineVoice = voice.id))
            renderVoiceCards()
        }
        card.voicePlay.setOnClickListener { previewOnline(voice.id) }
        binding.onlineVoices.addView(card.root)
        voiceCards[voice.id] = card
    }

    private fun renderVoiceCards() {
        val ctx = context ?: return
        voiceCards.forEach { (id, card) ->
            val selected = id == settings.onlineVoice
            card.voiceCard.isSelected = selected
            card.voicePlay.backgroundTintList = ContextCompat.getColorStateList(ctx, if (selected) R.color.rl_accent else android.R.color.transparent)
            card.voicePlay.iconTint = ContextCompat.getColorStateList(ctx, if (selected) R.color.rl_on_accent else R.color.rl_text)
            card.voicePlay.strokeWidth = if (selected) 0 else (1 * resources.displayMetrics.density).toInt()
        }
    }

    private fun update(next: VoiceSettings) {
        if (!loaded || next == settings) return
        settings = next
        dirty = true
    }

    private fun renderSections() {
        val b = _binding ?: return
        val online = settings.engine != VoiceEngine.SYSTEM
        b.engineDesc.setText(
            when (settings.engine) {
                VoiceEngine.EDGE -> R.string.settings_edge_desc
                VoiceEngine.AZURE -> R.string.settings_azure_desc
                VoiceEngine.SYSTEM -> R.string.settings_system_desc
            },
        )
        b.onlineVoices.isVisible = online
        b.azureSection.isVisible = settings.engine == VoiceEngine.AZURE
        // The phone voice is the whole choice for "Giọng máy"; for online voices it is a fold-out stand-in.
        b.systemToggleRow.isVisible = online
        b.systemSection.isVisible = !online || systemExpanded
        b.systemChevron.rotation = if (systemExpanded) 180f else 0f
        b.systemTitle.setText(R.string.voice_system_title_fallback)
        b.systemSummary.text = settings.systemVoice ?: getString(R.string.voice_system_default)
        b.bufferLabel.isVisible = online
        b.bufferSegment.isVisible = online
        b.bufferHint.isVisible = online
        renderVoiceCards()
    }

    // ---- Speed, pitch, buffering --------------------------------------------------------

    private fun bindStyle() {
        val chips = mapOf(binding.rate10 to 1.0f, binding.rate13 to 1.3f, binding.rate15 to 1.5f, binding.rate20 to 2.0f)
        val showRate = { value: Float ->
            binding.rateValue.text = getString(R.string.player_speed, PlayerUi.format(value))
            chips.forEach { (chip, speed) -> chip.isChecked = abs(speed - value) < 0.01f }
        }
        val showPitch = { value: Float -> binding.pitchValue.text = PlayerUi.format(value) }
        binding.rateSlider.addOnChangeListener { _, value, fromUser ->
            showRate(value)
            if (fromUser) onVoiceChanged()
        }
        binding.pitchSlider.addOnChangeListener { _, value, fromUser ->
            showPitch(value)
            if (fromUser) onVoiceChanged()
        }
        showRate(binding.rateSlider.value)
        showPitch(binding.pitchSlider.value)
        chips.forEach { (chip, speed) ->
            chip.setOnClickListener {
                binding.rateSlider.value = speed
                onVoiceChanged()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { store.speechRate.collect { binding.rateSlider.value = it.snap(binding.rateSlider.valueFrom, binding.rateSlider.valueTo) } }
                launch { store.pitch.collect { binding.pitchSlider.value = it.snap(binding.pitchSlider.valueFrom, binding.pitchSlider.valueTo) } }
            }
        }
    }

    private fun onVoiceChanged() {
        val rate = binding.rateSlider.value
        val pitch = binding.pitchSlider.value
        lifecycleScope.launch {
            store.setSpeechRate(rate)
            store.setPitch(pitch)
        }
        if (ReaderService.state.value.status != PlaybackStatus.IDLE) {
            ReaderService.send(requireContext(), ReaderService.ACTION_SET_VOICE) {
                putExtra(ReaderService.EXTRA_RATE, rate)
                putExtra(ReaderService.EXTRA_PITCH, pitch)
            }
        }
    }

    private fun bindBuffer(current: Int) {
        val values = listOf(ProgressStore.BUFFER_AUTO, 0, 1, 2, 3)
        binding.bufferSegment.setOptions(
            listOf(getString(R.string.buffer_auto), getString(R.string.buffer_off), "1", "2", "3"),
            soft = true,
        )
        binding.bufferSegment.select(values.indexOf(current).coerceAtLeast(0))
        binding.bufferSegment.onSelected = { i ->
            val count = values[i]
            lifecycleScope.launch {
                store.setBufferChapters(count)
                if (ReaderService.state.value.status != PlaybackStatus.IDLE) {
                    ReaderService.send(requireContext(), ReaderService.ACTION_BUFFER_SETTING)
                }
            }
        }
    }

    // ---- System engines and voices --------------------------------------------------------

    private fun loadSystemTts(enginePackage: String?) {
        val b = _binding ?: return
        tts?.shutdown()
        ttsReady = false
        b.systemVoiceGroup.removeAllViews()
        b.systemVoiceStatus.isVisible = true
        b.systemVoiceStatus.setText(R.string.voice_loading)
        var created: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status -> main.post { created?.let { onSystemTtsReady(it, status) } } }
        val ctx = requireContext()
        created = if (enginePackage.isNullOrBlank()) TextToSpeech(ctx, listener) else TextToSpeech(ctx, listener, enginePackage)
        tts = created
    }

    private fun onSystemTtsReady(engine: TextToSpeech, status: Int) {
        val b = _binding ?: return
        if (engine !== tts) return
        if (status != TextToSpeech.SUCCESS) {
            b.systemVoiceStatus.setText(R.string.voice_system_failed)
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
        b.systemVoiceStatus.isVisible = missing || systemVoices.isEmpty()
        b.systemVoiceStatus.setText(if (missing) R.string.voice_system_missing else R.string.voice_system_none)

        b.systemVoiceGroup.setOnCheckedChangeListener(null)
        b.systemVoiceGroup.removeAllViews()
        val defaultButton = voiceRadio(getString(R.string.voice_system_default), null)
        b.systemVoiceGroup.addView(defaultButton)
        systemVoices.forEach { b.systemVoiceGroup.addView(voiceRadio(voiceLabel(it), it.name)) }
        val selected = (0 until b.systemVoiceGroup.childCount)
            .map { b.systemVoiceGroup.getChildAt(it) as RadioButton }
            .firstOrNull { it.tag == settings.systemVoice } ?: defaultButton
        selected.isChecked = true
        b.systemVoiceGroup.setOnCheckedChangeListener { group, checkedId ->
            val name = group.findViewById<RadioButton>(checkedId)?.tag as? String
            update(settings.copy(systemVoice = name))
            renderSections()
        }
    }

    private fun bindEngineDropdown(engine: TextToSpeech) {
        val b = _binding ?: return
        val labels = listOf(getString(R.string.voice_engine_default, engine.defaultEngine ?: "")) + engines.map { it.label }
        b.engineDropdown.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels))
        val selectedIndex = engines.indexOfFirst { it.name == settings.systemEngine }.let { if (it < 0) 0 else it + 1 }
        b.engineDropdown.setText(labels[selectedIndex], false)
        b.engineDropdown.setOnItemClickListener { _, _, position, _ ->
            val pkg = if (position == 0) null else engines[position - 1].name
            if (pkg == settings.systemEngine) return@setOnItemClickListener
            update(settings.copy(systemEngine = pkg, systemVoice = null))
            loadSystemTts(pkg)
        }
    }

    private fun voiceRadio(label: String, voiceName: String?) = RadioButton(requireContext()).apply {
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

    private fun previewSystem() {
        stopPreview()
        val engine = tts
        if (engine == null || !ttsReady) {
            toast(getString(R.string.voice_loading))
            return
        }
        engine.setLanguage(SystemSpeaker.VIETNAMESE)
        settings.systemVoice?.let { name -> systemVoices.firstOrNull { it.name == name }?.let { engine.voice = it } }
        engine.speak(getString(R.string.voice_sample), TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    private fun previewOnline(voiceId: String) {
        stopPreview()
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
        val card = voiceCards[voiceId] ?: return
        card.voicePlay.isEnabled = false
        card.voiceLoading.isVisible = true
        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = synthesizer.synthesize(getString(R.string.voice_sample), voiceId)
                val file = File(requireContext().cacheDir, "preview.mp3")
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
                card.voicePlay.isEnabled = true
                card.voiceLoading.isVisible = false
            }
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        player?.release()
        player = null
        tts?.takeIf { ttsReady }?.stop()
    }

    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()

    private fun Float.snap(min: Float, max: Float): Float = ((this * 10).roundToInt() / 10f).coerceIn(min, max)
}
