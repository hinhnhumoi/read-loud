package com.tung.readloud.tts

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import com.tung.readloud.R
import com.tung.readloud.data.ChapterCache
import com.tung.readloud.data.EventLog
import com.tung.readloud.data.NovelRepository
import com.tung.readloud.data.ProgressStore
import com.tung.readloud.data.VoiceEngine
import com.tung.readloud.data.VoiceSettings
import com.tung.readloud.fetch.ChallengeRequiredException
import com.tung.readloud.fetch.PageFetcher
import com.tung.readloud.model.Chapter
import com.tung.readloud.parse.ChapterParser
import com.tung.readloud.parse.NextChapterFinder
import com.tung.readloud.parse.TocParser
import com.tung.readloud.book.BookStore
import com.tung.readloud.book.BookUrl
import com.tung.readloud.data.CompiledRule
import com.tung.readloud.data.ReplaceRules
import com.tung.readloud.data.RuleRepository
import com.tung.readloud.parse.TextChunker
import com.tung.readloud.parse.TextNormalizer
import com.tung.readloud.tts.speech.AudioCache
import com.tung.readloud.tts.speech.AzureSynthesizer
import com.tung.readloud.tts.speech.EdgeSynthesizer
import com.tung.readloud.tts.speech.Http
import com.tung.readloud.tts.speech.OnlineSpeaker
import com.tung.readloud.tts.speech.Speaker
import com.tung.readloud.tts.speech.Synthesizer
import com.tung.readloud.tts.speech.SystemSpeaker
import com.tung.readloud.ui.MainActivity
import com.tung.readloud.ui.VerifyActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URI

/**
 * Foreground service that reads chapters aloud, prefetches the next chapter and keeps going until no
 * next chapter can be found. Speech comes from an online neural voice or the phone's TTS engine.
 */
class ReaderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val parser = ChapterParser()
    private val fetcher by lazy { PageFetcher(this) }
    private val store by lazy { ProgressStore(this) }
    private val library by lazy { NovelRepository(this) }
    private val ruleRepository by lazy { RuleRepository(this) }
    private val bookStore by lazy { BookStore(this) }

    /** The user's pronunciation rules, kept current while the service runs. */
    @Volatile
    private var rules: List<CompiledRule> = emptyList()
    private val chapterCache by lazy { ChapterCache(this) }
    private val audioCache by lazy { AudioCache(this, scope) }

    private var speaker: Speaker? = null
    private var speakerSettings: VoiceSettings? = null
    private var usingFallback = false
    private var pausedInPlace = false
    private var recoveryJob: Job? = null

    /** Set by the recovery probe once the online voice works again while the phone's voice stands in. */
    private var onlineRecovered = false

    /** Paused because neither voice could speak; resumes by itself when the online voice is back. */
    private var waitingForNetwork = false
    private var recoveryInterval = RECOVERY_INTERVAL_MS
    private var lastRecoveredAt = 0L

    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var novelId: Long? = null
    private var chapter: Chapter? = null
    private var chunks: List<String> = emptyList()
    private var index = 0
    private var queuedUpTo = -1
    private var chapterSeq = 0
    private var prefetch: Deferred<Chapter>? = null
    private var loadJob: Job? = null
    private var downloadJob: Job? = null
    private var bufferJob: Job? = null
    private var pauseRequested = false
    private var pausedByFocusLoss = false
    private var consecutiveErrors = 0
    private var emptyChapters = 0

    /** The "voice server is slow" note is showing, so it is ours to clear. */
    private var slowNoteShown = false
    private var speechRate = ProgressStore.DEFAULT_RATE
    private var pitch = ProgressStore.DEFAULT_PITCH
    private var sleepAfterChapter = false
    private val sleepRunnable = Runnable { onSleepTimer() }

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private val speakerListener = object : Speaker.Listener {
        override fun onDone(utteranceId: String) {
            parseId(utteranceId)?.let { (seq, n) -> onChunkDone(seq, n) }
        }

        override fun onError(utteranceId: String, fatal: Boolean, message: String?) {
            val (seq, n) = parseId(utteranceId) ?: return
            if (seq != chapterSeq) return
            if (fatal && !usingFallback) fallbackToSystem(message) else onChunkError(seq, n)
        }

        override fun onStall(waiting: Boolean) {
            if (waiting) {
                EventLog.log("Voice: no audio yet for chunk $index, waiting for the server")
                slowNoteShown = true
                setState { copy(engineNote = getString(R.string.voice_slow_note)) }
            } else if (slowNoteShown) {
                slowNoteShown = false
                setState { copy(engineNote = null) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        EventLog.log("Service created")
        audioManager = getSystemService(AudioManager::class.java)
        createChannel()
        session = MediaSessionCompat(this, "ReadLoud").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = handlePlay()
                override fun onPause() = pause()
                override fun onSkipToNext() = seekChunk(index + 1)
                override fun onSkipToPrevious() = seekChunk(index - 1)
                override fun onFastForward() = seekChunk(index + 1)
                override fun onRewind() = seekChunk(index - 1)
                override fun onStop() = stopAll()
                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        CUSTOM_PREV_CHAPTER -> skipToPrevious()
                        CUSTOM_NEXT_CHAPTER -> skipToNext()
                        CUSTOM_STOP -> stopAll()
                    }
                }
            })
            isActive = true
        }
        updateSession(PlaybackStateCompat.STATE_NONE)
        startInForeground()
        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch(Dispatchers.IO) {
            audioCache.trim()
            chapterCache.trim()
        }
        scope.launch { ruleRepository.rules.collect { rules = ReplaceRules.compile(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        MediaButtonReceiver.handleIntent(session, intent)
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val id = intent.getLongExtra(EXTRA_NOVEL_ID, -1L).takeIf { it > 0 }
                if (url.isNullOrBlank()) fail(getString(R.string.error_no_url)) else startFrom(url, intent.getIntExtra(EXTRA_INDEX, 0), id)
            }
            ACTION_PLAY -> handlePlay()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> if (_state.value.status == PlaybackStatus.PLAYING) pause() else handlePlay()
            ACTION_NEXT -> skipToNext()
            ACTION_PREV_CHAPTER -> skipToPrevious()
            ACTION_PREV_CHUNK -> seekChunk(currentIndex() - 1)
            ACTION_NEXT_CHUNK -> seekChunk(currentIndex() + 1)
            ACTION_SEEK_CHUNK -> seekChunk(intent.getIntExtra(EXTRA_INDEX, currentIndex()))
            ACTION_SET_SLEEP -> setSleep(intent.getIntExtra(EXTRA_SLEEP_MINUTES, 0))
            ACTION_RELOAD_VOICE -> reloadVoice()
            ACTION_DOWNLOAD -> startDownload(intent.getIntExtra(EXTRA_COUNT, DEFAULT_DOWNLOAD_COUNT))
            ACTION_CANCEL_DOWNLOAD -> downloadJob?.cancel()
            ACTION_BUFFER_SETTING -> {
                val status = _state.value.status
                if (chunks.isNotEmpty() && (status == PlaybackStatus.PLAYING || status == PlaybackStatus.PAUSED)) startBuffer()
            }
            ACTION_STOP -> stopAll()
            ACTION_SET_VOICE -> {
                speechRate = intent.getFloatExtra(EXTRA_RATE, speechRate)
                pitch = intent.getFloatExtra(EXTRA_PITCH, pitch)
                applyVoiceSettings()
            }
            null -> {
                EventLog.log("Service restarted by the system")
                resumeAfterRestart()
            }
            else -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        EventLog.log("Service destroyed while ${_state.value.status}")
        // The state outlives this service; without this the screen would keep showing "reading" with nothing behind it.
        val status = _state.value.status
        if (status == PlaybackStatus.PLAYING || status == PlaybackStatus.LOADING) {
            setState { copy(status = PlaybackStatus.PAUSED, message = null, engineNote = null) }
        }
        loadJob?.cancel()
        prefetch?.cancel()
        downloadJob?.cancel()
        mainHandler.removeCallbacks(sleepRunnable)
        releaseSpeaker()
        runCatching { unregisterReceiver(noisyReceiver) }
        scope.cancel()
        session.release()
        releaseWakeLock()
        abandonFocus()
        super.onDestroy()
    }

    // ---- Speakers -------------------------------------------------------------------------

    private fun parseId(id: String): Pair<Int, Int>? {
        val m = utteranceIdPattern.matchEntire(id) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** Junk lines are dropped before chunking, so the text view and the voice see the same chunks. */
    private fun chunksOf(chapter: Chapter): List<String> =
        TextChunker.chunk(TextNormalizer.clean(chapter.paragraphs), chunkSize())

    /** What is actually spoken for a chunk: user rules first, then the built-in rewrites. */
    private fun speechText(chunk: String): String =
        TextNormalizer.forSpeech(ReplaceRules.apply(rules, chunk)).ifBlank { chunk }

    private fun chunkSize(): Int =
        minOf(CHUNK_TARGET_CHARS, TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(200)

    private fun onlineSource(settings: VoiceSettings): Pair<Synthesizer, String>? = when (settings.engine) {
        VoiceEngine.SYSTEM -> null
        VoiceEngine.EDGE -> EdgeSynthesizer(Http.client) to settings.onlineVoice
        VoiceEngine.AZURE -> if (settings.azureKey.isBlank()) {
            null
        } else {
            AzureSynthesizer(Http.client, settings.azureKey, settings.azureRegion) to settings.onlineVoice
        }
    }

    private fun systemSpeaker(settings: VoiceSettings) =
        SystemSpeaker(this, settings.systemEngine, settings.systemVoice, audioAttributes, speakerListener)

    private fun buildSpeaker(settings: VoiceSettings): Speaker {
        val online = onlineSource(settings) ?: return systemSpeaker(settings)
        return OnlineSpeaker(this, audioCache, online.first, online.second, speakerListener)
    }

    /** Returns a ready speaker for the saved settings, or reports the failure and returns null. */
    private suspend fun ensureSpeaker(): Speaker? {
        val settings = store.voiceSettings.first()
        speaker?.let { current -> if (usingFallback || settings == speakerSettings) return current }
        releaseSpeaker()
        val created = buildSpeaker(settings)
        speaker = created
        speakerSettings = settings
        return when (created.prepare()) {
            Speaker.PrepareResult.OK -> created
            Speaker.PrepareResult.MISSING_DATA -> {
                releaseSpeaker()
                fail(getString(R.string.error_tts_missing), needsData = true)
                null
            }
            Speaker.PrepareResult.FAILED -> {
                releaseSpeaker()
                fail(getString(R.string.error_tts_init))
                null
            }
        }
    }

    private fun releaseSpeaker() {
        speaker?.release()
        speaker = null
        speakerSettings = null
        pausedInPlace = false
    }

    /** The online voice cannot continue, so the phone's voice picks up from the current chunk. */
    private fun fallbackToSystem(reason: String?) {
        EventLog.log("Voice: online failed at chunk $index ($reason), using phone voice")
        slowNoteShown = false
        stopBuffer()
        val settings = speakerSettings ?: VoiceSettings()
        releaseSpeaker()
        usingFallback = true
        onlineRecovered = false
        // Failing again soon after coming back means the service is flaky: wait longer before the next try.
        val sinceRecovery = SystemClock.elapsedRealtime() - lastRecoveredAt
        recoveryInterval = if (lastRecoveredAt > 0 && sinceRecovery < FLAKY_WINDOW_MS) {
            (recoveryInterval * 2).coerceAtMost(MAX_RECOVERY_INTERVAL_MS)
        } else {
            RECOVERY_INTERVAL_MS
        }
        val system = systemSpeaker(settings)
        speaker = system
        speakerSettings = settings
        setState { copy(engineNote = getString(R.string.voice_fallback_note, reason ?: "")) }
        scope.launch {
            if (system.prepare() != Speaker.PrepareResult.OK) {
                releaseSpeaker()
                fail(getString(R.string.error_tts_missing), needsData = true)
                return@launch
            }
            if (speaker !== system) return@launch
            system.applyVoice(speechRate, pitch)
            queuedUpTo = index - 1
            if (_state.value.status == PlaybackStatus.PLAYING) enqueueAhead()
        }
        startRecoveryProbe(immediate = false)
    }

    /**
     * While the phone's voice stands in, checks now and then whether the online voice answers again.
     * A success only sets [onlineRecovered]; the switch back happens at the next chunk boundary.
     */
    private fun startRecoveryProbe(immediate: Boolean) {
        if (!usingFallback || onlineRecovered) return
        if (recoveryJob?.isActive == true && !immediate) return
        val source = speakerSettings?.let(::onlineSource) ?: return
        recoveryJob?.cancel()
        val backingOff = recoveryInterval > RECOVERY_INTERVAL_MS
        recoveryJob = scope.launch {
            var wait = !immediate || backingOff
            while (usingFallback && !onlineRecovered) {
                // Nothing is playing while waiting for the network, so there is no flapping to avoid.
                if (wait) delay(if (waitingForNetwork) RECOVERY_INTERVAL_MS else recoveryInterval)
                wait = true
                val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    runCatching { source.first.synthesize(PROBE_TEXT, source.second) }.isSuccess
                } == true
                EventLog.log("Online voice probe: ${if (ok) "back" else "still failing"}")
                if (ok && usingFallback) onlineRecovered = true
            }
            if (onlineRecovered && waitingForNetwork && _state.value.status == PlaybackStatus.PAUSED) play()
        }
    }

    /** Drops the stand-in voice if the online one has recovered; the next [play] rebuilds it. */
    private fun leaveFallbackIfRecovered(): Boolean {
        if (!usingFallback || !onlineRecovered) return false
        onlineRecovered = false
        recoveryJob?.cancel()
        speaker?.stop()
        releaseSpeaker()
        usingFallback = false
        lastRecoveredAt = SystemClock.elapsedRealtime()
        EventLog.log("Voice: back to online voice at chunk $index")
        setState { copy(engineNote = null) }
        startBuffer()
        return true
    }

    private fun resetFallback() {
        recoveryJob?.cancel()
        onlineRecovered = false
        usingFallback = false
        waitingForNetwork = false
        recoveryInterval = RECOVERY_INTERVAL_MS
        lastRecoveredAt = 0L
    }

    private fun reloadVoice() {
        scope.launch {
            val wasPlaying = _state.value.status == PlaybackStatus.PLAYING
            releaseSpeaker()
            resetFallback()
            setState { copy(engineNote = null) }
            if (chunks.isEmpty()) return@launch
            val s = ensureSpeaker() ?: return@launch
            s.applyVoice(speechRate, pitch)
            queuedUpTo = index - 1
            if (wasPlaying && _state.value.status == PlaybackStatus.PLAYING) enqueueAhead()
            startBuffer()
        }
    }

    // ---- Playback flow --------------------------------------------------------------------

    private fun startFrom(url: String, startIndex: Int, requestedNovelId: Long?, startPaused: Boolean = false) {
        loadJob?.cancel()
        stopBuffer()
        prefetch?.cancel()
        prefetch = null
        pauseRequested = startPaused
        speaker?.stop()
        pausedInPlace = false
        if (usingFallback) {
            releaseSpeaker()
            resetFallback()
        }
        consecutiveErrors = 0
        chapterSeq++
        novelId = requestedNovelId
        setState {
            copy(
                status = PlaybackStatus.LOADING, novelId = requestedNovelId, url = url, title = null,
                chunkIndex = 0, chunkCount = 0, chunks = emptyList(),
                message = getString(R.string.status_loading), ttsNeedsData = false, engineNote = null, verifyUrl = null,
            )
        }
        updateSession(PlaybackStateCompat.STATE_BUFFERING)
        refreshNotification()
        loadJob = scope.launch {
            EventLog.log("Start $url at chunk $startIndex")
            speechRate = store.speechRate.first()
            pitch = store.pitch.first()
            rules = ReplaceRules.compile(ruleRepository.rules.first())
            ensureSpeaker() ?: return@launch
            try {
                val loaded = loadChapter(url)
                if (novelId == null) {
                    val count = chunksOf(loaded).size
                    novelId = library.findOrCreate(loaded, startIndex, count).id
                }
                beginChapter(loaded, startIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChallengeRequiredException) {
                EventLog.log("Start blocked by a bot check on ${e.url}")
                needVerification(e.url, if (e.url == url) startIndex else 0)
            } catch (e: Exception) {
                EventLog.log("Start failed", e)
                fail(getString(R.string.error_load, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Uses the saved copy when it already knows its next chapter; otherwise fetches, falling back to the copy offline. */
    private suspend fun loadChapter(url: String): Chapter {
        if (BookUrl.isBook(url)) return withContext(Dispatchers.IO) { bookStore.chapter(url) }
        val cached = withContext(Dispatchers.IO) { chapterCache.get(url) }
        if (cached != null && cached.nextUrl != null) return cached
        return try {
            val html = fetcher.fetch(url)
            val parsed = parser.parse(url, html)
            if (parsed.paragraphs.isEmpty()) throw IOException(getString(R.string.error_no_content))
            withContext(Dispatchers.IO) { chapterCache.put(parsed) }
            parsed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cached ?: throw e
        }
    }

    private fun beginChapter(next: Chapter, startIndex: Int) {
        speaker?.stop()
        pausedInPlace = false
        chapter = next
        chunks = chunksOf(next)
        index = startIndex.coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
        chapterSeq++
        queuedUpTo = index - 1
        prefetch = next.nextUrl?.takeIf { it != next.url }?.let { url -> scope.async { loadChapter(url) } }
        setState {
            copy(
                status = PlaybackStatus.PLAYING, novelId = this@ReaderService.novelId, url = next.url, title = next.title,
                chunkIndex = index, chunkCount = this@ReaderService.chunks.size, chunks = this@ReaderService.chunks, message = null,
            )
        }
        updateMetadata(next)
        val id = novelId
        val toc = next.tocUrl
        if (id != null && toc != null) scope.launch { library.setTocUrlIfMissing(id, toc) }
        saveProgress()
        EventLog.log("Chapter '${next.title}': ${chunks.size} chunks, from $index")
        if (chunks.isEmpty()) {
            // A title page or an image-only chapter: nothing to say, so move on instead of sitting silent.
            emptyChapters++
            if (emptyChapters > MAX_EMPTY_CHAPTERS) fail(getString(R.string.error_empty_chapters)) else advanceChapter()
            return
        }
        emptyChapters = 0
        startBuffer()
        if (pauseRequested) {
            pauseRequested = false
            markPaused()
        } else {
            play()
        }
    }

    private fun play() {
        if (chunks.isEmpty()) {
            resumeLast()
            return
        }
        waitingForNetwork = false
        leaveFallbackIfRecovered()
        val s = speaker ?: run {
            scope.launch { if (ensureSpeaker() != null) play() }
            return
        }
        if (!requestFocus()) {
            fail(getString(R.string.error_audio_focus))
            return
        }
        acquireWakeLock()
        s.applyVoice(speechRate, pitch)
        val resumed = pausedInPlace && s.resume()
        pausedInPlace = false
        if (!resumed) {
            s.stop()
            queuedUpTo = index - 1
            enqueueAhead()
        }
        pausedByFocusLoss = false
        setState { copy(status = PlaybackStatus.PLAYING, message = null) }
        updateSession(PlaybackStateCompat.STATE_PLAYING)
        refreshNotification()
        scope.launch { store.setPlaying(true) }
    }

    private fun enqueueAhead() = enqueueUpTo(index + (speaker?.lookahead ?: 1))

    private fun enqueueUpTo(target: Int) {
        val s = speaker ?: return
        val last = minOf(target, chunks.size - 1)
        while (queuedUpTo < last) {
            queuedUpTo++
            s.enqueue("c${chapterSeq}_p$queuedUpTo", speechText(chunks[queuedUpTo]))
        }
    }

    private fun pause() {
        when (_state.value.status) {
            PlaybackStatus.LOADING -> pauseRequested = true
            PlaybackStatus.PLAYING -> {
                val s = speaker
                if (s != null && s.pause()) {
                    pausedInPlace = true
                } else {
                    s?.stop()
                    queuedUpTo = index - 1
                    pausedInPlace = false
                }
                markPaused()
            }
            else -> Unit
        }
    }

    private fun markPaused() {
        releaseWakeLock()
        setState { copy(status = PlaybackStatus.PAUSED, message = null) }
        updateSession(PlaybackStateCompat.STATE_PAUSED)
        refreshNotification()
        saveProgress()
        scope.launch { store.setPlaying(false) }
    }

    private fun handlePlay() {
        val status = _state.value.status
        val current = chapter
        when {
            status == PlaybackStatus.LOADING && loadJob?.isActive == true -> Unit
            status == PlaybackStatus.PLAYING && chunks.isNotEmpty() -> Unit
            status == PlaybackStatus.PAUSED && chunks.isNotEmpty() -> play()
            current != null -> startFrom(current.url, index.coerceAtMost((chunks.size - 1).coerceAtLeast(0)), novelId)
            else -> resumeLast()
        }
    }

    /** A fresh service knows nothing yet: pick up the novel on screen, or the most recent one, where it was left. */
    private fun resumeLast(atChunk: Int? = null) {
        scope.launch {
            val recent = _state.value.novelId?.let { library.findById(it) } ?: library.mostRecent()
            if (recent != null) {
                startFrom(recent.currentUrl, atChunk ?: recent.chunkIndex, recent.id)
            } else {
                fail(getString(R.string.error_nothing_to_read))
            }
        }
    }

    private fun applyVoiceSettings() {
        scope.launch {
            store.setSpeechRate(speechRate)
            store.setPitch(pitch)
        }
        val s = speaker ?: return
        val appliedLive = s.applyVoice(speechRate, pitch)
        if (appliedLive || _state.value.status != PlaybackStatus.PLAYING) return
        s.stop()
        queuedUpTo = index - 1
        enqueueAhead()
    }

    /** The chunk on screen; after a restart this service has not loaded it yet, but the shared state remembers. */
    private fun currentIndex(): Int = if (chapter == null) _state.value.chunkIndex else index

    /** Jumps to a chunk in the current chapter; past the end moves to the next chapter. */
    private fun seekChunk(target: Int) {
        if (chunks.isEmpty()) {
            if (chapter == null) resumeLast(target.coerceAtLeast(0))
            return
        }
        if (target >= chunks.size) {
            skipToNext()
            return
        }
        val clamped = target.coerceAtLeast(0)
        when (_state.value.status) {
            PlaybackStatus.PLAYING -> {
                speaker?.stop()
                pausedInPlace = false
                index = clamped
                queuedUpTo = index - 1
                enqueueAhead()
            }
            PlaybackStatus.PAUSED, PlaybackStatus.FINISHED, PlaybackStatus.ERROR -> {
                speaker?.stop()
                pausedInPlace = false
                index = clamped
                queuedUpTo = index - 1
            }
            else -> return
        }
        setState { copy(chunkIndex = index) }
        saveProgress()
        refreshNotification()
    }

    private fun onChunkDone(seq: Int, n: Int) {
        if (seq != chapterSeq || _state.value.status != PlaybackStatus.PLAYING || n < index) return
        consecutiveErrors = 0
        advanceTo(n + 1)
    }

    private fun onChunkError(seq: Int, n: Int) {
        if (seq != chapterSeq || _state.value.status != PlaybackStatus.PLAYING || n < index) return
        if (usingFallback) {
            waitForOnlineVoice(n)
            return
        }
        consecutiveErrors++
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            fail(getString(R.string.error_tts_speak))
            return
        }
        advanceTo(n + 1)
    }

    /**
     * Both voices failed, typically offline without a downloaded phone voice. Rather than skipping text,
     * pause on this chunk and let the recovery probe resume playback once the online voice answers.
     */
    private fun waitForOnlineVoice(chunk: Int) {
        EventLog.log("Voice: phone voice failed too, pausing at chunk $chunk until online voice is back")
        speaker?.stop()
        pausedInPlace = false
        index = chunk
        queuedUpTo = index - 1
        waitingForNetwork = true
        setState { copy(chunkIndex = index, engineNote = getString(R.string.voice_waiting_network)) }
        markPaused()
        startRecoveryProbe(immediate = false)
    }

    private fun advanceTo(nextIndex: Int) {
        index = nextIndex
        if (index >= chunks.size) {
            advanceChapter()
            return
        }
        setState { copy(chunkIndex = index) }
        saveProgress()
        refreshNotification()
        acquireWakeLock()
        if (leaveFallbackIfRecovered()) {
            play()
            return
        }
        enqueueAhead()
    }

    private fun advanceChapter() {
        val current = chapter ?: return
        val pending = prefetch
        startRecoveryProbe(immediate = true)
        if (pending == null) {
            finish(getString(R.string.finished_no_next))
            return
        }
        if (sleepAfterChapter) {
            sleepAfterChapter = false
            pauseRequested = true
            setState { copy(sleepAfterChapter = false) }
        }
        chapterSeq++
        setState { copy(status = PlaybackStatus.LOADING, message = getString(R.string.status_loading_next)) }
        updateSession(PlaybackStateCompat.STATE_BUFFERING)
        refreshNotification()
        loadJob = scope.launch {
            try {
                val next = pending.await()
                val samePage = next.url == current.url ||
                    (next.title == current.title && next.paragraphs == current.paragraphs)
                if (samePage) finish(getString(R.string.finished_no_next)) else beginChapter(next, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChallengeRequiredException) {
                EventLog.log("Next chapter blocked by a bot check on ${e.url}")
                needVerification(e.url, 0)
            } catch (e: Exception) {
                finish(getString(R.string.finished_load_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun skipToNext() {
        if (chapter == null || _state.value.status == PlaybackStatus.LOADING) return
        speaker?.stop()
        pausedInPlace = false
        pauseRequested = _state.value.status == PlaybackStatus.PAUSED
        advanceChapter()
    }

    /** Starts the chapter before this one, keeping the paused state; toasts when there is none. */
    private fun skipToPrevious() {
        val current = chapter ?: return
        if (_state.value.status == PlaybackStatus.LOADING) return
        val wasPaused = _state.value.status == PlaybackStatus.PAUSED
        val id = novelId
        scope.launch {
            val previous = previousUrl(current, id)
            if (previous == null) {
                Toast.makeText(this@ReaderService, R.string.no_previous_chapter, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (chapter !== current) return@launch
            EventLog.log("Previous chapter: $previous")
            startFrom(previous, 0, id, startPaused = wasPaused)
        }
    }

    /** The book's own order, then the saved table of contents, then the page's link, then the URL's number. */
    private suspend fun previousUrl(current: Chapter, id: Long?): String? {
        if (BookUrl.isBook(current.url)) return current.prevUrl
        if (id != null) {
            val entries = library.tocEntries(id)
            val key = TocParser.normalize(current.url)
            val at = entries.indexOfFirst { TocParser.normalize(it.url) == key }
            if (at > 0) return entries[at - 1].url
        }
        // Chapters saved by older versions did not keep the link, so read the page once more for it.
        val linked = current.prevUrl ?: runCatching {
            parser.parse(current.url, fetcher.fetch(current.url)).also { withContext(Dispatchers.IO) { chapterCache.put(it) } }.prevUrl
        }.getOrNull()
        return linked ?: NextChapterFinder.guessFromUrl(current.url, -1)
    }

    private fun finish(message: String) {
        EventLog.log("Finished: $message")
        speaker?.stop()
        pausedInPlace = false
        clearSleep()
        releaseWakeLock()
        abandonFocus()
        setState { copy(status = PlaybackStatus.FINISHED, message = message) }
        updateSession(PlaybackStateCompat.STATE_STOPPED)
        refreshNotification()
        if (downloadJob?.isActive != true) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        scope.launch { store.setPlaying(false) }
    }

    private fun fail(message: String, needsData: Boolean = false) {
        EventLog.log("Error: $message")
        speaker?.stop()
        pausedInPlace = false
        clearSleep()
        releaseWakeLock()
        abandonFocus()
        setState { copy(status = PlaybackStatus.ERROR, message = message, ttsNeedsData = needsData) }
        updateSession(PlaybackStateCompat.STATE_ERROR)
        refreshNotification()
        if (downloadJob?.isActive != true) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        scope.launch { store.setPlaying(false) }
    }

    /** Stops with a way out: the screen offers to open the page, and so does a notification when it is off. */
    private fun needVerification(url: String, resumeIndex: Int) {
        fail(getString(R.string.verify_needed))
        setState { copy(verifyUrl = url, verifyResumeIndex = resumeIndex) }
        val open = PendingIntent.getActivity(
            this, VERIFY_NOTIFICATION_ID,
            VerifyActivity.intent(this, url, resume = true, chunkIndex = resumeIndex, novelId = novelId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.verify_notification_title))
            .setContentText(getString(R.string.verify_notification_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(VERIFY_NOTIFICATION_ID, notification) }
    }

    private fun stopAll() {
        loadJob?.cancel()
        stopBuffer()
        prefetch?.cancel()
        downloadJob?.cancel()
        speaker?.stop()
        pausedInPlace = false
        clearSleep()
        saveProgress()
        releaseWakeLock()
        abandonFocus()
        setState { copy(status = PlaybackStatus.IDLE, message = null, download = null) }
        updateSession(PlaybackStateCompat.STATE_STOPPED)
        session.isActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.launch {
            store.setPlaying(false)
            stopSelf()
        }
    }

    private fun resumeAfterRestart() {
        if (chapter != null) return
        scope.launch {
            val recent = library.mostRecent()
            if (recent != null && store.wasPlaying()) startFrom(recent.currentUrl, recent.chunkIndex, recent.id) else stopAll()
        }
    }

    private fun saveProgress() {
        val current = chapter ?: return
        val id = novelId ?: return
        val position = index
        val count = chunks.size
        scope.launch { library.updateProgress(id, current.url, current.title, position, count) }
    }

    // ---- Buffering ahead ------------------------------------------------------------------

    /**
     * Chapters to prepare beyond the current one: the user's choice, or on "auto" two on Wi-Fi and one on
     * mobile data or a low-memory phone; none when storage is nearly full. The audio goes to disk, a few MB per chapter.
     */
    private suspend fun bufferChapterCount(): Int {
        if (filesDir.usableSpace < MIN_FREE_BYTES_FOR_BUFFER) return 0
        val chosen = store.bufferChapters.first()
        if (chosen != ProgressStore.BUFFER_AUTO) return chosen.coerceIn(0, MAX_BUFFER_CHAPTERS)
        val metered = runCatching { getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(true)
        val lowRam = runCatching { getSystemService(ActivityManager::class.java).isLowRamDevice }.getOrDefault(false)
        return if (metered || lowRam) 1 else 2
    }

    /**
     * Synthesizes the rest of this chapter and then the next chapters' text and audio in the background,
     * so the voice does not stop to wait at chapter changes. Only for online voices, and only while they work.
     */
    private fun startBuffer() {
        stopBuffer()
        val current = chapter ?: return
        val rest = chunks.drop(index + 1)
        val nextText = prefetch
        bufferJob = scope.launch {
            val source = onlineSource(store.voiceSettings.first()) ?: return@launch
            val ahead = bufferChapterCount()
            if (usingFallback || ahead == 0) return@launch
            // Let the chunk being spoken get its audio first.
            delay(BUFFER_START_DELAY_MS)
            var done = 0
            try {
                synthesizeInBackground(source, rest)
                var ch = current
                while (done < ahead) {
                    val url = ch.nextUrl?.takeIf { it != ch.url } ?: break
                    ch = if (done == 0 && nextText != null) nextText.await() else loadChapter(url)
                    synthesizeInBackground(source, chunksOf(ch))
                    done++
                    setState { copy(bufferedChapters = done) }
                }
                EventLog.log("Buffer: rest of '${current.title}' and $done chapter(s) ahead ready")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.log("Buffer: stopped after $done chapter(s)", e)
            }
        }
    }

    private suspend fun synthesizeInBackground(source: Pair<Synthesizer, String>, parts: List<String>) {
        for (part in parts) {
            for (piece in OnlineSpeaker.splitForSynthesis(speechText(part))) {
                if (usingFallback) throw IOException("online voice is down")
                audioCache.prefetch(source.first, source.second, piece)
            }
        }
    }

    private fun stopBuffer() {
        bufferJob?.cancel()
        bufferJob = null
        if (_state.value.bufferedChapters != 0) setState { copy(bufferedChapters = 0) }
    }

    // ---- Offline download -----------------------------------------------------------------

    /** Saves the next [count] chapters, starting with the current one, plus their audio for online voices. */
    private fun startDownload(count: Int) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            var done = 0
            try {
                val startUrl = chapter?.url
                    ?: (novelId?.let { library.findById(it) } ?: library.mostRecent())?.currentUrl
                if (startUrl == null) {
                    setState { copy(download = null, downloadMessage = getString(R.string.download_nothing)) }
                    return@launch
                }
                val online = onlineSource(store.voiceSettings.first())
                var url: String? = startUrl
                while (url != null && done < count) {
                    setState { copy(download = DownloadProgress(done, count), downloadMessage = null) }
                    refreshNotification()
                    val ch = loadChapter(url)
                    if (online != null) {
                        val parts = chunksOf(ch)
                        for ((i, part) in parts.withIndex()) {
                            for (piece in OnlineSpeaker.splitForSynthesis(speechText(part))) {
                                audioCache.request(online.first, online.second, piece).await()
                            }
                            setState { copy(download = DownloadProgress(done, count, i + 1, parts.size)) }
                        }
                    }
                    done++
                    url = ch.nextUrl?.takeIf { it != ch.url }
                }
                setState { copy(download = null, downloadMessage = getString(R.string.download_done, done)) }
            } catch (e: CancellationException) {
                setState { copy(download = null, downloadMessage = getString(R.string.download_cancelled, done)) }
                throw e
            } catch (e: Exception) {
                setState {
                    copy(download = null, downloadMessage = getString(R.string.download_failed, done, e.message ?: e.javaClass.simpleName))
                }
            } finally {
                refreshNotification()
                if (_state.value.status == PlaybackStatus.IDLE) {
                    ServiceCompat.stopForeground(this@ReaderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    // ---- Sleep timer ----------------------------------------------------------------------

    /** minutes > 0 pauses after that long, [SLEEP_END_OF_CHAPTER] pauses at the chapter end, 0 turns it off. */
    private fun setSleep(minutes: Int) {
        mainHandler.removeCallbacks(sleepRunnable)
        sleepAfterChapter = false
        when {
            minutes == SLEEP_END_OF_CHAPTER -> {
                sleepAfterChapter = true
                setState { copy(sleepDeadline = null, sleepAfterChapter = true) }
            }
            minutes > 0 -> {
                val delay = minutes * 60_000L
                mainHandler.postDelayed(sleepRunnable, delay)
                setState { copy(sleepDeadline = SystemClock.elapsedRealtime() + delay, sleepAfterChapter = false) }
            }
            else -> setState { copy(sleepDeadline = null, sleepAfterChapter = false) }
        }
        refreshNotification()
    }

    private fun onSleepTimer() {
        setState { copy(sleepDeadline = null) }
        pause()
    }

    private fun clearSleep() {
        mainHandler.removeCallbacks(sleepRunnable)
        sleepAfterChapter = false
        setState { copy(sleepDeadline = null, sleepAfterChapter = false) }
    }

    // ---- Audio focus and wake lock --------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                EventLog.log("Audio focus lost to another app")
                pausedByFocusLoss = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val wasActive = _state.value.status == PlaybackStatus.PLAYING || _state.value.status == PlaybackStatus.LOADING
                pause()
                pausedByFocusLoss = wasActive
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss) {
                    // Focus can come back while the next chapter is still loading; then it should just start.
                    when (_state.value.status) {
                        PlaybackStatus.PAUSED -> play()
                        PlaybackStatus.LOADING -> pauseRequested = false
                        else -> Unit
                    }
                    pausedByFocusLoss = false
                }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .setWillPauseWhenDucked(false)
            .build()
            .also { focusRequest = it }
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadLoud:tts")
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        if (!lock.isHeld) lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        // Online voices fetch audio as they go; keep Wi-Fi awake with the screen off too.
        val wifi = wifiLock ?: runCatching {
            @Suppress("DEPRECATION")
            applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ReadLoud:tts")
                .also {
                    it.setReferenceCounted(false)
                    wifiLock = it
                }
        }.getOrNull()
        if (wifi != null && !wifi.isHeld) runCatching { wifi.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
    }

    // ---- Media session and notification ---------------------------------------------------

    private fun updateMetadata(current: Chapter) {
        val host = if (BookUrl.isBook(current.url)) current.pageTitle else runCatching { URI(current.url).host }.getOrNull() ?: ""
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, current.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, host)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
                .build(),
        )
    }

    private fun updateSession(state: Int) {
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND,
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(CUSTOM_NEXT_CHAPTER, getString(R.string.action_next), R.drawable.ic_skip_next).build(),
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(CUSTOM_STOP, getString(R.string.action_stop), R.drawable.ic_stop).build(),
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val alerts = NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT)
        getSystemService(NotificationManager::class.java).createNotificationChannel(alerts)
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val s = _state.value
        val active = s.status == PlaybackStatus.PLAYING || s.status == PlaybackStatus.LOADING
        val toggle = if (active) {
            NotificationCompat.Action(R.drawable.ic_pause, getString(R.string.action_pause), servicePendingIntent(ACTION_PAUSE, 1))
        } else {
            NotificationCompat.Action(R.drawable.ic_play, getString(R.string.action_play), servicePendingIntent(ACTION_PLAY, 2))
        }
        val progress = if (s.chunkCount > 0) getString(R.string.progress_text, s.chunkIndex + 1, s.chunkCount) else ""
        val text = when (s.status) {
            PlaybackStatus.IDLE -> if (s.download != null) null else getString(R.string.status_idle)
            PlaybackStatus.LOADING -> s.message ?: getString(R.string.status_loading)
            PlaybackStatus.PLAYING -> getString(R.string.status_reading, s.chunkIndex + 1, s.chunkCount)
            PlaybackStatus.PAUSED -> getString(R.string.status_paused, s.chunkIndex + 1, s.chunkCount)
            PlaybackStatus.ERROR, PlaybackStatus.FINISHED -> s.message ?: progress
        }
        val sleepNote = when {
            s.sleepAfterChapter -> getString(R.string.sleep_after_chapter_short)
            s.sleepDeadline != null -> getString(R.string.sleep_remaining_short, ((s.sleepDeadline - SystemClock.elapsedRealtime()) / 60_000L + 1).coerceAtLeast(1))
            else -> null
        }
        val downloadNote = s.download?.let { getString(R.string.download_note_short, it.chaptersDone + 1, it.chaptersTotal) }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.title ?: getString(R.string.app_name))
            .setContentText(listOfNotNull(text, sleepNote, downloadNote).joinToString(" · "))
            .setContentIntent(openApp)
            .setDeleteIntent(servicePendingIntent(ACTION_STOP, 4))
            .setOnlyAlertOnce(true)
            .setOngoing(active)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(NotificationCompat.Action(R.drawable.ic_fast_rewind, getString(R.string.action_prev_chunk), servicePendingIntent(ACTION_PREV_CHUNK, 5)))
            .addAction(toggle)
            .addAction(NotificationCompat.Action(R.drawable.ic_fast_forward, getString(R.string.action_next_chunk), servicePendingIntent(ACTION_NEXT_CHUNK, 6)))
            .addAction(NotificationCompat.Action(R.drawable.ic_skip_next, getString(R.string.action_next), servicePendingIntent(ACTION_NEXT, 3)))
            .addAction(NotificationCompat.Action(R.drawable.ic_stop, getString(R.string.action_stop), servicePendingIntent(ACTION_STOP, 4)))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this, requestCode,
            Intent(this, ReaderService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private inline fun setState(block: ReaderState.() -> ReaderState) = _state.update(block)

    companion object {
        const val ACTION_START = "com.tung.readloud.action.START"
        const val ACTION_PLAY = "com.tung.readloud.action.PLAY"
        const val ACTION_PAUSE = "com.tung.readloud.action.PAUSE"
        const val ACTION_TOGGLE = "com.tung.readloud.action.TOGGLE"
        const val ACTION_NEXT = "com.tung.readloud.action.NEXT"
        const val ACTION_PREV_CHAPTER = "com.tung.readloud.action.PREV_CHAPTER"
        const val ACTION_BUFFER_SETTING = "com.tung.readloud.action.BUFFER_SETTING"
        const val VERIFY_NOTIFICATION_ID = 2
        const val MAX_BUFFER_CHAPTERS = 3
        const val ACTION_PREV_CHUNK = "com.tung.readloud.action.PREV_CHUNK"
        const val ACTION_NEXT_CHUNK = "com.tung.readloud.action.NEXT_CHUNK"
        const val ACTION_SEEK_CHUNK = "com.tung.readloud.action.SEEK_CHUNK"
        const val ACTION_SET_SLEEP = "com.tung.readloud.action.SET_SLEEP"
        const val ACTION_RELOAD_VOICE = "com.tung.readloud.action.RELOAD_VOICE"
        const val ACTION_DOWNLOAD = "com.tung.readloud.action.DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.tung.readloud.action.CANCEL_DOWNLOAD"
        const val ACTION_STOP = "com.tung.readloud.action.STOP"
        const val ACTION_SET_VOICE = "com.tung.readloud.action.SET_VOICE"
        const val EXTRA_URL = "url"
        const val EXTRA_INDEX = "index"
        const val EXTRA_NOVEL_ID = "novel_id"
        const val EXTRA_RATE = "rate"
        const val EXTRA_PITCH = "pitch"
        const val EXTRA_SLEEP_MINUTES = "sleep_minutes"
        const val EXTRA_COUNT = "count"
        const val SLEEP_END_OF_CHAPTER = -1

        private const val RECOVERY_INTERVAL_MS = 60_000L
        private const val MAX_RECOVERY_INTERVAL_MS = 8 * 60_000L
        private const val FLAKY_WINDOW_MS = 5 * 60_000L
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val PROBE_TEXT = "Xin chào."
        private const val CUSTOM_NEXT_CHAPTER = "com.tung.readloud.custom.NEXT_CHAPTER"
        private const val CUSTOM_PREV_CHAPTER = "com.tung.readloud.custom.PREV_CHAPTER"
        private const val CUSTOM_STOP = "com.tung.readloud.custom.STOP"
        private const val CHANNEL_ID = "reader"
        private const val ALERT_CHANNEL_ID = "alerts"
        private const val NOTIFICATION_ID = 1
        private const val CHUNK_TARGET_CHARS = 1000
        private const val MAX_CONSECUTIVE_ERRORS = 3
        private const val MAX_EMPTY_CHAPTERS = 5
        private const val BUFFER_START_DELAY_MS = 3_000L
        private const val MIN_FREE_BYTES_FOR_BUFFER = 300L * 1024 * 1024
        private const val DEFAULT_DOWNLOAD_COUNT = 10
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000
        private val utteranceIdPattern = Regex("c(\\d+)_p(\\d+)")

        private val _state = MutableStateFlow(ReaderState())
        val state: StateFlow<ReaderState> = _state.asStateFlow()

        fun start(context: Context, url: String, chunkIndex: Int = 0, novelId: Long? = null) {
            val intent = Intent(context, ReaderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_INDEX, chunkIndex)
            if (novelId != null) intent.putExtra(EXTRA_NOVEL_ID, novelId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun send(context: Context, action: String, configure: Intent.() -> Unit = {}) {
            val intent = Intent(context, ReaderService::class.java).setAction(action).apply(configure)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
