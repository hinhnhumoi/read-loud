package com.tung.readloud.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.tung.readloud.R
import com.tung.readloud.book.BookStore
import com.tung.readloud.book.BookUrl
import com.tung.readloud.data.AppDatabase
import com.tung.readloud.data.ChapterCache
import com.tung.readloud.data.EventLog
import com.tung.readloud.data.Novel
import com.tung.readloud.data.NovelRepository
import com.tung.readloud.data.ProgressStore
import com.tung.readloud.data.ReplaceRules
import com.tung.readloud.data.RuleRepository
import com.tung.readloud.data.SavedChapter
import com.tung.readloud.data.applyingTo
import com.tung.readloud.fetch.ChallengeRequiredException
import com.tung.readloud.fetch.PageFetcher
import com.tung.readloud.model.Chapter
import com.tung.readloud.parse.ChapterParser
import com.tung.readloud.parse.TocParser
import com.tung.readloud.tts.ChapterText
import com.tung.readloud.tts.speech.AudioCache
import com.tung.readloud.tts.speech.OfflineAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Saves queued chapters one at a time: text first, then each piece of audio, recording progress as it goes
 * so a run stopped by the system continues from the same piece. A chapter that keeps failing is marked and
 * skipped instead of holding up the rest.
 *
 * With [KEY_NOVEL] and [KEY_COUNT] it first queues that many chapters of the novel from the one being
 * heard; with [KEY_NIGHT] it does that for the novels heard lately.
 */
class OfflineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val db = AppDatabase.get(context)
    private val dao = db.savedChapters()
    private val novels = NovelRepository(context)
    private val store = ProgressStore(context)
    private val books = BookStore(context)
    private val chapterCache = ChapterCache(context)
    private val audio = OfflineAudio(context)
    private val fetcher = PageFetcher(context)
    private val parser = ChapterParser()

    override suspend fun doWork(): Result {
        EventLog.init(applicationContext)
        return lock.withLock {
            dao.resetInterrupted()
            OfflineSaver.expire(applicationContext)
            OfflineSaver.releaseStray(applicationContext)
            if (inputData.getBoolean(KEY_NIGHT, false)) queueForTheNight()
            val novelId = inputData.getLong(KEY_NOVEL, -1L)
            if (novelId > 0) novels.findById(novelId)?.let { queueNext(it, inputData.getInt(KEY_COUNT, 0)) }
            runQueue()
        }
    }

    private suspend fun runQueue(): Result {
        var failedInARow = 0
        while (!isStopped) {
            if (freeBytes() < MIN_FREE_BYTES) {
                EventLog.log("Offline: stopped, less than ${MIN_FREE_BYTES / MB} MB free")
                return Result.success()
            }
            val row = dao.nextPending() ?: return Result.success()
            val novel = novels.findById(row.novelId)
            if (novel == null) {
                dao.delete(row.novelId, row.url)
                continue
            }
            showProgress(row.title)
            val ok = try {
                saveChapter(novel, row)
                true
            } catch (e: CancellationException) {
                // Stopped by the system: the row stays half done and is picked up next run.
                throw e
            } catch (e: ChallengeRequiredException) {
                giveUp(row, applicationContext.getString(R.string.offline_error_verify))
                false
            } catch (e: Exception) {
                EventLog.log("Offline: '${row.title}' failed", e)
                val attempts = row.attempts + 1
                if (attempts >= MAX_ATTEMPTS) {
                    giveUp(row, e.message ?: e.javaClass.simpleName)
                } else {
                    dao.update(row.copy(state = SavedChapter.QUEUED, attempts = attempts, queuedAt = System.currentTimeMillis()))
                }
                false
            }
            failedInARow = if (ok) 0 else failedInARow + 1
            // Several chapters failing together is the voice or the site being down: wait and try later.
            if (failedInARow >= FAILURES_BEFORE_PAUSE) {
                EventLog.log("Offline: $failedInARow chapters failed in a row, trying again later")
                return Result.retry()
            }
        }
        return Result.success()
    }

    private suspend fun giveUp(row: SavedChapter, reason: String) {
        EventLog.log("Offline: gave up on '${row.title}': $reason")
        dao.update(row.copy(state = SavedChapter.FAILED, error = reason))
    }

    private suspend fun saveChapter(novel: Novel, queued: SavedChapter) {
        var row = queued.copy(state = SavedChapter.SAVING)
        dao.update(row)
        val chapter = loadChapter(row.url)
        if (row.title.isBlank() || row.position < 0) row = row.copy(title = chapter.title.ifBlank { row.title })
        val settings = ChapterText.withOverrides(store.voiceSettings.first(), novel)
        val online = ChapterText.onlineSource(settings)
        if (online == null) {
            // The phone's voice needs only the text, which is saved now.
            dao.update(
                row.copy(state = SavedChapter.DONE, voice = "", keys = "", piecesDone = 0, piecesTotal = 0, bytes = 0, lastUsedAt = System.currentTimeMillis()),
            )
            EventLog.log("Offline: saved text of '${row.title}'")
            return
        }
        val (synthesizer, voice) = online
        val rules = ReplaceRules.compile(RuleRepository(applicationContext).rules.first().applyingTo(novel.id))
        val pieces = ChapterText.chunks(chapter, novel.skipAuthorNotes).flatMap { ChapterText.pieces(it, rules) }
        val keys = pieces.map { AudioCache.key(voice, it) }
        row = row.copy(voice = voice, keys = keys.joinToString(","), piecesTotal = pieces.size)
        dao.update(row)
        for ((i, piece) in pieces.withIndex()) {
            if (isStopped) throw CancellationException("stopped")
            keepWithRetry(synthesizer, voice, piece)
            if (i % PROGRESS_EVERY == 0 || i == pieces.lastIndex) {
                row = row.copy(piecesDone = i + 1)
                dao.update(row)
                showProgress(row.title, i + 1, pieces.size)
            }
        }
        val bytes = keys.sumOf { audio.size(it) }
        dao.update(row.copy(state = SavedChapter.DONE, piecesDone = pieces.size, bytes = bytes, error = null, lastUsedAt = System.currentTimeMillis()))
        EventLog.log("Offline: saved '${row.title}', ${pieces.size} pieces, ${bytes / 1024} KB")
    }

    private suspend fun keepWithRetry(synthesizer: com.tung.readloud.tts.speech.Synthesizer, voice: String, piece: String) {
        var attempt = 0
        while (true) {
            try {
                withContext(Dispatchers.IO) { audio.keep(synthesizer, voice, piece) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++attempt >= PIECE_ATTEMPTS) throw e
                delay(PIECE_RETRY_MS * attempt)
            }
        }
    }

    /** The saved text when it is there, otherwise the page, which is saved for next time. */
    private suspend fun loadChapter(url: String): Chapter {
        if (BookUrl.isBook(url)) return withContext(Dispatchers.IO) { books.chapter(url) }
        withContext(Dispatchers.IO) { chapterCache.get(url) }?.let { return it }
        val parsed = parser.parse(url, fetcher.fetch(url))
        if (parsed.paragraphs.isEmpty()) throw IOException(applicationContext.getString(R.string.error_no_content))
        withContext(Dispatchers.IO) { chapterCache.put(parsed) }
        return parsed
    }

    // ---- Choosing chapters ----------------------------------------------------------------

    /** Queues [count] chapters from the one being heard: from the chapter list when known, else by next links. */
    private suspend fun queueNext(novel: Novel, count: Int) {
        if (count <= 0) return
        val rows = mutableListOf<Triple<String, String, Int>>()
        val bookId = BookUrl.bookId(novel.currentUrl)
        val toc = if (bookId != null) {
            withContext(Dispatchers.IO) { runCatching { books.toc(bookId) }.getOrDefault(emptyList()) }
        } else {
            novels.tocEntries(novel.id).map { TocParser.Entry(it.title, it.url) }
        }
        val start = if (bookId != null) {
            BookUrl.index(novel.currentUrl)
        } else {
            val key = TocParser.normalize(novel.currentUrl)
            toc.indexOfFirst { TocParser.normalize(it.url) == key }.takeIf { it >= 0 }
        }
        if (start != null && toc.isNotEmpty()) {
            for (i in start until minOf(toc.size, start + count)) {
                val url = if (bookId != null) BookUrl.chapter(bookId, i) else toc[i].url
                rows += Triple(url, toc[i].title, i)
            }
        } else {
            // No list to go by: walk the next-chapter links, which also saves each page's text.
            var url: String? = novel.currentUrl
            while (url != null && rows.size < count && !isStopped) {
                val chapter = runCatching { loadChapter(url) }.getOrNull()
                rows += Triple(url, chapter?.title ?: novel.currentTitle, -1)
                url = chapter?.nextUrl?.takeIf { it != url }
            }
        }
        if (rows.isEmpty()) return
        EventLog.log("Offline: queued ${rows.size} chapter(s) of '${novel.name}'")
        OfflineSaver.save(applicationContext, novel.id, rows)
    }

    /** The night run: the next chapters of the few novels heard in the last days. */
    private suspend fun queueForTheNight() {
        val count = store.nightSaveCount.first()
        if (count <= 0) return
        val since = System.currentTimeMillis() - RECENT_DAYS * DAY_MS
        val recent = novels.novels.first().filter { it.lastReadAt >= since }.take(NIGHT_NOVELS)
        EventLog.log("Offline: night run for ${recent.size} novel(s), $count chapter(s) each")
        // The chapter being heard is likely saved or cached already; the next ones are what the night is for.
        recent.forEach { queueNext(it, count + 1) }
    }

    // ---- Progress -------------------------------------------------------------------------

    private suspend fun showProgress(title: String, done: Int = 0, total: Int = 0) {
        val ctx = applicationContext
        val manager = ctx.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, ctx.getString(R.string.channel_saving), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.offline_notify_title))
            .setContentText(title)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
        // Without the foreground slot (refused from the background on new Android) the run is merely shorter.
        runCatching { setForeground(info) }
    }

    private fun freeBytes(): Long = runCatching { StatFs(applicationContext.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    companion object {
        const val KEY_NOVEL = "novel"
        const val KEY_COUNT = "count"
        const val KEY_NIGHT = "night"

        /** Manual saves and the night run never work on the queue at the same time. */
        private val lock = Mutex()
        private const val CHANNEL_ID = "saving"
        private const val NOTIFICATION_ID = 3
        private const val MAX_ATTEMPTS = 3
        private const val PIECE_ATTEMPTS = 3
        private const val PIECE_RETRY_MS = 2_000L
        private const val FAILURES_BEFORE_PAUSE = 2
        private const val PROGRESS_EVERY = 3
        private const val MB = 1024L * 1024
        private const val MIN_FREE_BYTES = 300 * MB
        private const val RECENT_DAYS = 7L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val NIGHT_NOVELS = 3
    }
}
