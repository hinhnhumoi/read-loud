package com.tung.readloud.ui

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tung.readloud.offline.OfflineSaver
import android.content.Intent
import android.content.res.ColorStateList
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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.tung.readloud.R
import com.tung.readloud.data.ProgressStore
import com.tung.readloud.data.RuleRepository
import com.tung.readloud.data.VoiceEngine
import com.tung.readloud.data.VoiceSettings
import com.tung.readloud.databinding.FragmentSettingsBinding
import com.tung.readloud.databinding.ItemVoiceBinding
import com.tung.readloud.databinding.SheetVoiceBackupBinding
import com.tung.readloud.databinding.SheetVoicePrimaryBinding
import com.tung.readloud.tts.ReaderState
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
 * Where speech comes from and how it sounds. The main voice and the phone voice that stands in for it
 * are shown as two cards and changed in their own sheets; a change is applied when its sheet closes,
 * since applying one restarts the chunk being read. Speed, pitch and buffering apply at once.
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
    private var player: ReaderState = ReaderService.state.value
    private var metaColor: ColorStateList? = null
    private var primarySheet: SheetVoicePrimaryBinding? = null
    private var backupSheet: SheetVoiceBackupBinding? = null
    private var openSheet: BottomSheetDialog? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var systemVoicesMissing = false
    private var systemVoices: List<Voice> = emptyList()
    private var engines: List<TextToSpeech.EngineInfo> = emptyList()
    private var preview: MediaPlayer? = null
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
        binding.btnChangePrimary.setOnClickListener { openPrimarySheet() }
        binding.btnChangeBackup.setOnClickListener { openBackupSheet() }
        binding.primaryPlay.setOnClickListener { previewOnline(settings.onlineVoice, binding.primaryPlay, binding.primaryLoading) }
        binding.backupPlay.setOnClickListener { previewSystem() }
        bindStyle()

        viewLifecycleOwner.lifecycleScope.launch {
            settings = store.voiceSettings.first()
            bindSettings()
            bindBuffer(store.bufferChapters.first())
            bindNight(store.nightSaveCount.first())
            bindKeep(store.savedKeepDays.first())
            loaded = true
            loadSystemTts(settings.systemEngine)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ReaderService.state.collect {
                        player = it
                        renderNow()
                    }
                }
                launch {
                    OfflineSaver.summary(requireContext()).collect { (count, bytes) ->
                        _binding?.savedSummary?.text = if (count == 0) {
                            getString(R.string.saved_none)
                        } else {
                            getString(R.string.saved_summary, count, android.text.format.Formatter.formatShortFileSize(requireContext(), bytes))
                        }
                    }
                }
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
        openSheet?.dismiss()
        stopPreview()
        tts?.shutdown()
        tts = null
        preview?.release()
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

    // ---- Voice: main and stand-in ---------------------------------------------------------

    /** The online source of the main voice, also while "only the phone voice" has it switched off. */
    private val primaryEngine get() = if (settings.engine == VoiceEngine.SYSTEM) settings.onlineEngine else settings.engine

    private val systemOnly get() = settings.engine == VoiceEngine.SYSTEM

    private val missingAzureKey get() = !systemOnly && settings.engine == VoiceEngine.AZURE && settings.azureKey.isBlank()

    private fun bindSettings() {
        metaColor = binding.primaryDesc.textColors
        binding.systemOnlySwitch.isChecked = systemOnly
        binding.systemOnlySwitch.setOnCheckedChangeListener { _, on ->
            update(settings.copy(engine = if (on) VoiceEngine.SYSTEM else settings.onlineEngine))
            commitVoice()
            renderVoice()
        }
        renderVoice()
    }

    private fun renderVoice() {
        val b = _binding ?: return
        val voice = OnlineVoices.all.firstOrNull { it.id == settings.onlineVoice }
        b.primaryLabel.setText(if (systemOnly) R.string.voice_primary_off_label else R.string.voice_primary_label)
        b.primaryName.text = voice?.name ?: settings.onlineVoice
        if (missingAzureKey) {
            b.primaryDesc.setText(R.string.voice_primary_azure_no_key)
            b.primaryDesc.setTextColor(ContextCompat.getColor(requireContext(), R.color.rl_warn))
        } else {
            b.primaryDesc.text = getString(R.string.voice_primary_desc, engineName(primaryEngine), voice?.description.orEmpty())
            metaColor?.let(b.primaryDesc::setTextColor)
        }
        b.primaryCard.alpha = if (systemOnly) 0.45f else 1f
        b.chainRow.isVisible = !systemOnly
        b.chainNote.setText(if (primaryEngine == VoiceEngine.AZURE) R.string.voice_chain_azure else R.string.voice_chain_edge)
        b.backupLabel.setText(if (systemOnly) R.string.voice_in_use_label else R.string.voice_backup_label)
        b.backupName.setText(R.string.voice_system_sheet_title)
        b.backupDesc.text = getString(R.string.voice_primary_desc, systemEngineName(), systemVoiceName())
        b.bufferLabel.isVisible = !systemOnly
        b.bufferSegment.isVisible = !systemOnly
        b.bufferHint.isVisible = !systemOnly
        renderNow()
    }

    /** Which voice is speaking right now: its card is highlighted, and a line above says so. */
    private fun renderNow() {
        val b = _binding ?: return
        val onBackup = systemOnly || missingAzureKey || player.onBackupVoice
        b.primaryCard.isSelected = !onBackup
        b.backupCard.isSelected = onBackup
        val active = player.status == PlaybackStatus.PLAYING || player.status == PlaybackStatus.PAUSED ||
            player.status == PlaybackStatus.LOADING
        b.voiceNow.isVisible = active
        if (!active) return
        val line = getString(
            when {
                systemOnly -> R.string.voice_now_system
                onBackup -> R.string.voice_now_backup
                else -> R.string.voice_now_primary
            },
        )
        val own = player.voiceOverride?.takeIf { !onBackup }?.let { OnlineVoices.name(it) }
        b.voiceNow.text = if (own != null) getString(R.string.voice_now_own, line, own) else line
        b.voiceNow.setTextColor(ContextCompat.getColor(requireContext(), if (onBackup && !systemOnly) R.color.rl_warn else R.color.rl_offline))
    }

    private fun engineName(engine: VoiceEngine) =
        getString(if (engine == VoiceEngine.AZURE) R.string.voice_engine_azure_short else R.string.voice_engine_edge_short)

    private fun systemEngineName(): String {
        val pkg = settings.systemEngine ?: tts?.takeIf { ttsReady }?.defaultEngine ?: return "…"
        return engines.firstOrNull { it.name == pkg }?.label ?: pkg
    }

    private fun systemVoiceName(): String {
        val name = settings.systemVoice ?: return getString(R.string.voice_backup_default_voice)
        return systemVoices.firstOrNull { it.name == name }?.let { voiceLabel(it).replace("\n", " · ") } ?: name
    }

    private fun update(next: VoiceSettings) {
        if (!loaded || next == settings) return
        settings = next
        dirty = true
    }

    private fun sheetDialog(content: View, onClose: () -> Unit): BottomSheetDialog =
        BottomSheetDialog(requireContext()).apply {
            setContentView(content)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            setOnDismissListener {
                if (openSheet === this) openSheet = null
                onClose()
                commitVoice()
                renderVoice()
            }
        }

    private fun openPrimarySheet() {
        if (!loaded || openSheet != null) return
        val sheet = SheetVoicePrimaryBinding.inflate(layoutInflater)
        val dialog = sheetDialog(sheet.root) {
            primarySheet = null
            voiceCards.clear()
        }
        primarySheet = sheet
        val engineOrder = listOf(VoiceEngine.EDGE, VoiceEngine.AZURE)
        sheet.engineSegment.setOptions(listOf(getString(R.string.settings_engine_edge), getString(R.string.settings_engine_azure)))
        sheet.engineSegment.select(engineOrder.indexOf(primaryEngine))
        sheet.engineSegment.onSelected = { i ->
            val engine = engineOrder[i]
            update(if (systemOnly) settings.copy(onlineEngine = engine) else settings.copy(engine = engine, onlineEngine = engine))
            renderPrimarySheet()
        }
        voiceCards.clear()
        OnlineVoices.all.forEach { voice -> addVoiceCard(sheet, voice) }
        sheet.azureKey.setText(settings.azureKey)
        sheet.azureRegion.setText(settings.azureRegion)
        sheet.azureKey.doAfterTextChanged { update(settings.copy(azureKey = it?.toString()?.trim().orEmpty())) }
        sheet.azureRegion.doAfterTextChanged { update(settings.copy(azureRegion = it?.toString()?.trim().orEmpty())) }
        sheet.btnDone.setOnClickListener { dialog.dismiss() }
        renderPrimarySheet()
        openSheet = dialog
        dialog.show()
    }

    private fun renderPrimarySheet() {
        val sheet = primarySheet ?: return
        sheet.engineDesc.setText(if (primaryEngine == VoiceEngine.AZURE) R.string.settings_azure_desc else R.string.settings_edge_desc)
        sheet.azureSection.isVisible = primaryEngine == VoiceEngine.AZURE
        renderVoiceCards()
    }

    private fun addVoiceCard(sheet: SheetVoicePrimaryBinding, voice: OnlineVoice) {
        val card = ItemVoiceBinding.inflate(layoutInflater, sheet.onlineVoices, false)
        card.voiceName.text = voice.name
        card.voiceDesc.text = voice.description
        card.voicePlay.contentDescription = getString(R.string.settings_voice_play, voice.name)
        card.voiceCard.setOnClickListener {
            update(settings.copy(onlineVoice = voice.id))
            renderVoiceCards()
        }
        card.voicePlay.setOnClickListener { previewOnline(voice.id, card.voicePlay, card.voiceLoading) }
        sheet.onlineVoices.addView(card.root)
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

    private fun openBackupSheet() {
        if (!loaded || openSheet != null) return
        val sheet = SheetVoiceBackupBinding.inflate(layoutInflater)
        val dialog = sheetDialog(sheet.root) { backupSheet = null }
        backupSheet = sheet
        sheet.sheetTitle.setText(if (systemOnly) R.string.voice_system_sheet_title else R.string.voice_backup_sheet_title)
        sheet.sheetHint.setText(if (systemOnly) R.string.voice_system_sheet_hint else R.string.voice_backup_sheet_hint)
        sheet.btnTtsSettings.setOnClickListener { Dialogs.openTtsSettings(requireContext()) }
        sheet.btnPreviewSystem.setOnClickListener { previewSystem() }
        sheet.btnDone.setOnClickListener { dialog.dismiss() }
        bindBackupSheet()
        openSheet = dialog
        dialog.show()
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

    private fun bindKeep(current: Int) {
        val values = listOf(7, 15, 30, 0)
        binding.keepSegment.setOptions(values.map { if (it == 0) getString(R.string.keep_never) else getString(R.string.keep_days, it) }, soft = true)
        binding.keepSegment.select(values.indexOf(current).coerceAtLeast(0))
        binding.keepSegment.onSelected = { i ->
            lifecycleScope.launch {
                store.setSavedKeepDays(values[i])
                OfflineSaver.expire(requireContext())
            }
        }
    }

    private fun bindNight(current: Int) {
        val values = listOf(0, 5, 10, 20)
        binding.nightSegment.setOptions(listOf(getString(R.string.night_off), "5", "10", "20"), soft = true)
        binding.nightSegment.select(values.indexOf(current).coerceAtLeast(0))
        binding.nightSegment.onSelected = { i ->
            val count = values[i]
            lifecycleScope.launch {
                store.setNightSaveCount(count)
                OfflineSaver.syncNight(requireContext(), count)
            }
        }
        binding.rowSaved.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.saved_clear_title)
                .setMessage(R.string.saved_clear_message)
                .setPositiveButton(R.string.saved_clear) { _, _ -> lifecycleScope.launch { OfflineSaver.clearAll(requireContext()) } }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // ---- System engines and voices --------------------------------------------------------

    private fun loadSystemTts(enginePackage: String?) {
        tts?.shutdown()
        ttsReady = false
        ttsFailed = false
        bindBackupSheet()
        var created: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status -> main.post { created?.let { onSystemTtsReady(it, status) } } }
        val ctx = requireContext()
        created = if (enginePackage.isNullOrBlank()) TextToSpeech(ctx, listener) else TextToSpeech(ctx, listener, enginePackage)
        tts = created
    }

    private fun onSystemTtsReady(engine: TextToSpeech, status: Int) {
        if (_binding == null || engine !== tts) return
        if (status != TextToSpeech.SUCCESS) {
            ttsFailed = true
            bindBackupSheet()
            renderVoice()
            return
        }
        ttsReady = true
        engines = runCatching { engine.engines }.getOrDefault(emptyList())
        val language = engine.setLanguage(SystemSpeaker.VIETNAMESE)
        systemVoices = runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "vi" }
            // A stand-in for when the network is gone is only useful offline, so those voices come first.
            .sortedWith(
                compareBy<Voice> { it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenBy { it.name },
            )
        systemVoicesMissing = language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED
        bindBackupSheet()
        renderVoice()
    }

    /** Fills the phone voice sheet from the loaded engine, or says it is still loading. */
    private fun bindBackupSheet() {
        val sheet = backupSheet ?: return
        val engine = tts
        sheet.systemVoiceGroup.setOnCheckedChangeListener(null)
        sheet.systemVoiceGroup.removeAllViews()
        if (engine == null || !ttsReady) {
            sheet.systemVoiceStatus.isVisible = true
            sheet.systemVoiceStatus.setText(if (ttsFailed) R.string.voice_system_failed else R.string.voice_loading)
            return
        }
        bindEngineDropdown(sheet, engine)
        sheet.systemVoiceStatus.isVisible = systemVoicesMissing || systemVoices.isEmpty()
        sheet.systemVoiceStatus.setText(if (systemVoicesMissing) R.string.voice_system_missing else R.string.voice_system_none)

        val defaultButton = voiceRadio(getString(R.string.voice_system_default), null)
        sheet.systemVoiceGroup.addView(defaultButton)
        systemVoices.forEach { sheet.systemVoiceGroup.addView(voiceRadio(voiceLabel(it), it.name)) }
        val selected = (0 until sheet.systemVoiceGroup.childCount)
            .map { sheet.systemVoiceGroup.getChildAt(it) as RadioButton }
            .firstOrNull { it.tag == settings.systemVoice } ?: defaultButton
        selected.isChecked = true
        sheet.systemVoiceGroup.setOnCheckedChangeListener { group, checkedId ->
            val name = group.findViewById<RadioButton>(checkedId)?.tag as? String
            update(settings.copy(systemVoice = name))
            renderVoice()
        }
    }

    private fun bindEngineDropdown(sheet: SheetVoiceBackupBinding, engine: TextToSpeech) {
        val labels = listOf(getString(R.string.voice_engine_default, engine.defaultEngine ?: "")) + engines.map { it.label }
        sheet.engineDropdown.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels))
        val selectedIndex = engines.indexOfFirst { it.name == settings.systemEngine }.let { if (it < 0) 0 else it + 1 }
        sheet.engineDropdown.setText(labels[selectedIndex], false)
        sheet.engineDropdown.setOnItemClickListener { _, _, position, _ ->
            val pkg = if (position == 0) null else engines[position - 1].name
            if (pkg == settings.systemEngine) return@setOnItemClickListener
            update(settings.copy(systemEngine = pkg, systemVoice = null))
            loadSystemTts(pkg)
            renderVoice()
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

    private fun previewOnline(voiceId: String, button: MaterialButton, loading: View) {
        stopPreview()
        val synthesizer = when (primaryEngine) {
            VoiceEngine.AZURE -> {
                if (settings.azureKey.isBlank()) {
                    toast(getString(R.string.voice_azure_need_key))
                    return
                }
                AzureSynthesizer(Http.client, settings.azureKey, settings.azureRegion)
            }
            else -> EdgeSynthesizer(Http.client)
        }
        button.isEnabled = false
        loading.isVisible = true
        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = synthesizer.synthesize(getString(R.string.voice_sample), voiceId)
                val file = File(requireContext().cacheDir, "preview.mp3")
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                preview = MediaPlayer().apply {
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
                button.isEnabled = true
                loading.isVisible = false
            }
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        preview?.release()
        preview = null
        tts?.takeIf { ttsReady }?.stop()
    }

    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()

    private fun Float.snap(min: Float, max: Float): Float = ((this * 10).roundToInt() / 10f).coerceIn(min, max)
}
