package com.tung.readloud.ui

import com.tung.readloud.offline.OfflineSaver
import android.content.Intent
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.tung.readloud.R
import com.tung.readloud.data.ProgressStore
import com.tung.readloud.data.VoiceEngine
import com.tung.readloud.data.VoiceSettings
import com.tung.readloud.databinding.ViewMiniPlayerBinding
import com.tung.readloud.databinding.ViewPlayerBinding
import com.tung.readloud.tts.PlaybackStatus
import com.tung.readloud.tts.ReaderService
import com.tung.readloud.tts.speech.OnlineVoices
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/** Binds the mini player and the full-screen player to what is playing. */
class PlayerUi(
    private val activity: MainActivity,
    private val mini: ViewMiniPlayerBinding,
    private val player: ViewPlayerBinding,
) {
    private val store = ProgressStore(activity)
    private var np: NowPlaying? = null
    private var rate = ProgressStore.DEFAULT_RATE
    private var voice: VoiceSettings = VoiceSettings()
    private var globalRate = ProgressStore.DEFAULT_RATE

    /** Chapters still waiting in the offline queue. */
    private var savingLeft = 0
    private var seeking = false
    private val accent = ContextCompat.getColor(activity, R.color.rl_accent)

    init {
        mini.miniRoot.setOnClickListener { activity.expandPlayer() }
        mini.miniToggle.setOnClickListener { np?.let(::toggle) }
        player.btnCollapse.setOnClickListener { activity.collapsePlayer() }
        player.btnPlayerToc.setOnClickListener {
            np?.item?.novel?.id?.let { activity.startActivity(NovelDetailActivity.intent(activity, it)) }
        }
        player.sentenceFrame.setOnClickListener {
            if (ReaderService.state.value.chunks.isNotEmpty()) activity.startActivity(Intent(activity, ReaderActivity::class.java))
        }
        player.btnToggle.setOnClickListener { np?.let(::toggle) }
        player.btnPrevChunk.setOnClickListener { send(ReaderService.ACTION_PREV_CHUNK) }
        player.btnNextChunk.setOnClickListener { send(ReaderService.ACTION_NEXT_CHUNK) }
        player.btnPrevChapter.setOnClickListener { send(ReaderService.ACTION_PREV_CHAPTER) }
        player.btnNextChapter.setOnClickListener { send(ReaderService.ACTION_NEXT) }
        player.chunkSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                seeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                seeking = false
                ReaderService.send(activity, ReaderService.ACTION_SEEK_CHUNK) { putExtra(ReaderService.EXTRA_INDEX, slider.value.toInt()) }
            }
        })
        player.chunkSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) player.seekLeft.text = activity.getString(R.string.player_chunk_label, value.toInt() + 1)
        }
        player.quickSpeed.setOnClickListener { cycleSpeed() }
        player.quickSleep.setOnClickListener { SleepSheet().show(activity.supportFragmentManager, SleepSheet.TAG) }
        player.quickVoice.setOnClickListener { activity.showTab(MainActivity.Tab.SETTINGS) }
        player.quickDownload.setOnClickListener { Dialogs.showDownload(activity, voice.engine == VoiceEngine.SYSTEM) }

        activity.lifecycleScope.launch {
            store.speechRate.collect {
                globalRate = it
                np?.let(::showSpeedAndVoice) ?: run { player.quickSpeedValue.text = activity.getString(R.string.player_speed, format(it)) }
            }
        }
        activity.lifecycleScope.launch {
            OfflineSaver.progress(activity).collect {
                savingLeft = it?.remaining ?: 0
                np?.let(::bindQuick)
            }
        }
        activity.lifecycleScope.launch {
            store.voiceSettings.collect {
                voice = it
                np?.let(::showSpeedAndVoice) ?: run { player.quickVoiceLabel.text = voiceName(it) }
            }
        }
    }

    /** A novel with its own speed or voice shows those while it plays, not the general settings. */
    private fun showSpeedAndVoice(now: NowPlaying) {
        val s = now.state
        rate = if (now.live) s.speechRate else globalRate
        player.quickSpeedValue.setTextIfChanged(activity.getString(R.string.player_speed, format(rate)))
        val override = s.voiceOverride?.takeIf { now.live && voice.engine != VoiceEngine.SYSTEM }
        player.quickVoiceLabel.setTextIfChanged(override?.let(OnlineVoices::name) ?: voiceName(voice))
    }

    fun bind(now: NowPlaying) {
        np = now
        showSpeedAndVoice(now)
        val s = now.state
        val loading = s.status == PlaybackStatus.LOADING
        val playIcon = if (now.active) R.drawable.ic_pause else R.drawable.ic_play
        val playLabel = activity.getString(if (now.active) R.string.action_pause else R.string.action_play)

        // Mini player
        mini.miniCover.title = now.novelName
        mini.miniTitle.text = now.chapterTitle.ifBlank { now.novelName }
        mini.miniToggle.setIconResource(playIcon)
        mini.miniToggle.contentDescription = playLabel
        mini.miniToggle.isVisible = !loading
        mini.miniLoading.isVisible = loading

        // Player
        player.playerStatus.text = statusLabel(now)
        player.playerNovel.text = now.novelName
        player.frameChapter.text = now.chapterTitle
        player.btnToggle.setIconResource(playIcon)
        player.btnToggle.contentDescription = playLabel
        player.playerLoading.isVisible = loading
        player.btnToggle.icon = if (loading) null else ContextCompat.getDrawable(activity, playIcon)
        val hasChunks = s.chunks.isNotEmpty()
        listOf(player.btnPrevChunk, player.btnNextChunk).forEach { it.isEnabled = hasChunks }
        listOf(player.btnPrevChapter, player.btnNextChapter).forEach { it.isEnabled = now.live && !loading }
        player.frameHint.isVisible = hasChunks
        player.chunkSlider.isEnabled = hasChunks && now.chunkCount > 1
        if (!seeking) {
            val max = (now.chunkCount - 1).coerceAtLeast(1).toFloat()
            player.chunkSlider.valueTo = max
            player.chunkSlider.value = now.chunkIndex.toFloat().coerceIn(0f, max)
        }
        StatusBanner.bind(player.playerBanner, now, activity)
        bindQuick(now)
        tick()
    }

    /** Moves the highlight and the time left on between state updates. */
    suspend fun runTicker() {
        while (true) {
            delay(TICK_MS)
            // Between updates only a playing voice moves anything on screen.
            if (np?.state?.status == PlaybackStatus.PLAYING || np?.state?.sleepDeadline != null) {
                tick()
                np?.let(::bindQuick)
            }
        }
    }

    private fun tick() {
        val now = np ?: return
        val s = now.state
        val minutes = now.chapterMinutesLeft()
        val chunkText = if (now.chunkCount > 0) activity.getString(R.string.player_chunk_of, now.chunkIndex + 1, now.chunkCount) else ""
        mini.miniMeta.setTextIfChanged(when {
            s.status == PlaybackStatus.LOADING -> s.message ?: activity.getString(R.string.status_loading)
            s.engineNote != null -> s.engineNote
            minutes != null && now.chunkCount > 0 -> activity.getString(R.string.mini_meta, now.chunkIndex + 1, now.chunkCount, minutes)
            else -> chunkText.ifBlank { now.novelName }
        })
        mini.miniProgress.setProgressIfChanged((now.chapterFraction() * 1000).toInt())
        player.frameChunk.setTextIfChanged(chunkText)
        if (!seeking) player.seekLeft.setTextIfChanged(if (now.chunkCount > 0) activity.getString(R.string.player_chunk_label, now.chunkIndex + 1) else "")
        player.seekRight.setTextIfChanged(minutes?.let { activity.getString(R.string.player_minutes_left, it) }.orEmpty())
        bindSentences(now)
    }

    /** The sentence the voice is on, with the phrase being said in amber, between its neighbours. */
    private fun bindSentences(now: NowPlaying) {
        val s = now.state
        val chunk = s.chunks.getOrNull(s.chunkIndex)
        if (!now.live || chunk == null) {
            player.sentencePrev.setTextIfChanged("")
            player.sentenceNow.setTextIfChanged(
                if (s.status == PlaybackStatus.LOADING) s.message ?: activity.getString(R.string.status_loading) else now.chapterTitle,
            )
            player.sentenceNext.setTextIfChanged("")
            return
        }
        val offset = (now.progressNow() * chunk.length).toInt()
        val (before, current, after) = Sentences.around(chunk, offset)
        player.sentencePrev.setTextIfChanged(before ?: s.chunks.getOrNull(s.chunkIndex - 1)?.let(Sentences::last).orEmpty())
        player.sentenceNext.setTextIfChanged(after ?: s.chunks.getOrNull(s.chunkIndex + 1)?.let(Sentences::first).orEmpty())
        val sentence = current.second
        val styled = SpannableString(sentence)
        if (s.status == PlaybackStatus.PLAYING) {
            val within = offset - current.first
            Sentences.phrases(sentence).lastOrNull { it.first <= within }?.let { (start, phrase) ->
                styled.setSpan(ForegroundColorSpan(accent), start, start + phrase.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        player.sentenceNow.setTextIfChanged(styled)
    }

    private fun bindQuick(now: NowPlaying) {
        val s = now.state
        val deadline = s.sleepDeadline
        player.quickSleepLabel.setTextIfChanged(when {
            s.sleepChapters == 1 -> activity.getString(R.string.sleep_after_chapter_option)
            s.sleepChapters > 1 -> activity.getString(R.string.sleep_chapters_option, s.sleepChapters)
            deadline != null -> {
                val left = ((deadline - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
                String.format(Locale.US, "%d:%02d", left / 60, left % 60)
            }
            else -> activity.getString(R.string.action_sleep)
        })
        val sleeping = deadline != null || s.sleepChapters > 0
        player.quickSleepIcon.setColorFilter(ContextCompat.getColor(activity, if (sleeping) R.color.rl_accent else R.color.rl_text))
        val saving = savingLeft
        player.quickDownloadLabel.setTextIfChanged(when {
            saving > 0 -> activity.getString(R.string.download_note_short, saving)
            s.bufferedChapters > 0 -> activity.getString(R.string.player_buffered, s.bufferedChapters)
            else -> activity.getString(R.string.action_download)
        })
        val ready = saving > 0 || s.bufferedChapters > 0
        player.quickDownloadIcon.setColorFilter(ContextCompat.getColor(activity, if (ready) R.color.rl_offline else R.color.rl_text))
    }

    private fun statusLabel(now: NowPlaying): String = activity.getString(
        when (now.state.status) {
            PlaybackStatus.PLAYING -> R.string.status_label_reading
            PlaybackStatus.LOADING -> R.string.status_label_reading
            PlaybackStatus.PAUSED -> R.string.status_label_paused
            PlaybackStatus.ERROR -> R.string.status_label_error
            PlaybackStatus.FINISHED -> R.string.status_label_finished
            PlaybackStatus.IDLE -> R.string.status_label_paused
        },
    )

    private fun toggle(now: NowPlaying) = togglePlayback(activity, now)

    private fun send(action: String) = ReaderService.send(activity, action)

    /** Steps through the common speeds; the settings tab has the fine slider. */
    private fun cycleSpeed() {
        val next = SPEEDS.firstOrNull { it > rate + 0.05f } ?: SPEEDS.first()
        val forNovel = np?.live == true && ReaderService.state.value.ownRate
        activity.lifecycleScope.launch {
            // A novel with its own speed keeps the change to itself.
            if (!forNovel) store.setSpeechRate(next)
            val pitch = store.pitch.first()
            ReaderService.send(activity, ReaderService.ACTION_SET_VOICE) {
                putExtra(ReaderService.EXTRA_RATE, next)
                putExtra(ReaderService.EXTRA_PITCH, pitch)
                putExtra(ReaderService.EXTRA_FOR_NOVEL, forNovel)
            }
        }
    }

    private fun voiceName(v: VoiceSettings): String = when (v.engine) {
        VoiceEngine.EDGE, VoiceEngine.AZURE -> OnlineVoices.name(v.onlineVoice)
        VoiceEngine.SYSTEM -> activity.getString(R.string.settings_engine_system)
    }

    companion object {
        private const val TICK_MS = 500L
        private val SPEEDS = listOf(1.0f, 1.3f, 1.5f, 2.0f)

        fun format(value: Float): String =
            if (abs(value - Math.round(value * 10) / 10f) < 0.001f) String.format(Locale.US, "%.1f", value) else String.format(Locale.US, "%.2f", value)

        /** Play or pause; from a stop or an error, picks up the novel on screen where it was left. */
        fun togglePlayback(activity: android.content.Context, now: NowPlaying) {
            when (now.state.status) {
                PlaybackStatus.PLAYING, PlaybackStatus.LOADING -> ReaderService.send(activity, ReaderService.ACTION_PAUSE)
                PlaybackStatus.PAUSED -> ReaderService.send(activity, ReaderService.ACTION_PLAY)
                else -> {
                    val novel = now.item?.novel
                    if (now.live || novel == null) {
                        ReaderService.send(activity, ReaderService.ACTION_PLAY)
                    } else {
                        ReaderService.start(activity, novel.currentUrl, novel.chunkIndex, novel.id)
                    }
                }
            }
        }
    }
}
