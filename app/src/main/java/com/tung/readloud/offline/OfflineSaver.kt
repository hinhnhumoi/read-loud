package com.tung.readloud.offline

import kotlinx.coroutines.flow.first
import com.tung.readloud.data.ProgressStore
import com.tung.readloud.data.EventLog
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.tung.readloud.data.AppDatabase
import com.tung.readloud.data.SavedChapter
import com.tung.readloud.tts.speech.OfflineAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** The offline queue across all novels: chapters still to save, and the one being saved now. */
data class SaveProgress(val remaining: Int, val current: String?, val fraction: Float)

/**
 * The offline queue: chapters asked for are stored first and saved by [OfflineWorker], which survives the
 * app being closed and picks up where it stopped. A saved chapter is let go once it has gone unused for
 * the days set in the settings.
 */
object OfflineSaver {
    private const val WORK = "offline-save"
    private const val NIGHT_WORK = "offline-night"
    private const val NIGHT_HOUR = 1
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun dao(context: Context) = AppDatabase.get(context).savedChapters()

    fun observe(context: Context, novelId: Long): Flow<List<SavedChapter>> = dao(context).observe(novelId)

    /** What the queue is doing now, or null when nothing is waiting. */
    fun progress(context: Context): Flow<SaveProgress?> = dao(context).observeAll().map { rows ->
        val active = rows.filter { it.state == SavedChapter.QUEUED || it.state == SavedChapter.SAVING }
        if (active.isEmpty()) return@map null
        val saving = active.firstOrNull { it.state == SavedChapter.SAVING }
        val fraction = saving?.takeIf { it.piecesTotal > 0 }?.let { it.piecesDone.toFloat() / it.piecesTotal } ?: 0f
        SaveProgress(active.size, saving?.title, fraction)
    }

    /** Saved chapters and the space they take. */
    fun summary(context: Context): Flow<Pair<Int, Long>> = dao(context).observeAll().map { rows ->
        val done = rows.filter { it.state == SavedChapter.DONE }
        done.size to done.sumOf { it.bytes }
    }

    /** Saves [count] chapters of the novel, from the one being heard, in the background. */
    fun saveNext(context: Context, novelId: Long, count: Int) {
        enqueue(context, workDataOf(OfflineWorker.KEY_NOVEL to novelId, OfflineWorker.KEY_COUNT to count))
    }

    /** Puts the given chapters in the queue; one that failed before is tried again. */
    suspend fun save(context: Context, novelId: Long, chapters: List<Triple<String, String, Int>>) {
        val dao = dao(context)
        val now = System.currentTimeMillis()
        dao.insertNew(chapters.mapIndexed { i, (url, title, position) -> SavedChapter(novelId, url, title, position, queuedAt = now + i) })
        chapters.forEach { (url, _, _) -> dao.retry(novelId, url, now) }
        enqueue(context, workDataOf())
    }

    private fun enqueue(context: Context, input: androidx.work.Data) {
        val request = OneTimeWorkRequestBuilder<OfflineWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Stops saving and forgets the chapters not finished yet; finished ones stay. */
    suspend fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        dao(context).dropUnfinished()
        releaseStray(context)
    }

    /** Removes one saved chapter, or takes it out of the queue; audio another saved chapter shares stays. */
    suspend fun remove(context: Context, novelId: Long, url: String) {
        dao(context).delete(novelId, url)
        releaseStray(context)
    }

    /** The chapter is being played, so its saved copy counts as used. */
    suspend fun touch(context: Context, novelId: Long, url: String) =
        dao(context).touch(novelId, url, System.currentTimeMillis())

    /** Removes saved chapters nobody has played or saved for the days set in the settings. */
    suspend fun expire(context: Context) {
        val days = ProgressStore(context).savedKeepDays.first()
        if (days <= 0) return
        val old = dao(context).unusedSince(System.currentTimeMillis() - days * DAY_MS)
        if (old.isEmpty()) return
        EventLog.log("Offline: removing ${old.size} chapter(s) unused for $days days")
        old.forEach { dao(context).delete(it.novelId, it.url) }
        releaseStray(context)
    }

    suspend fun removeNovel(context: Context, novelId: Long) {
        dao(context).clear(novelId)
        releaseStray(context)
    }

    suspend fun clearAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        dao(context).clearAll()
        releaseStray(context)
    }

    /** Gives back audio no saved chapter claims any more; chapters can share pieces, such as a repeated line. */
    suspend fun releaseStray(context: Context) {
        val claimed = dao(context).all().flatMap { it.keyList }.toSet()
        withContext(Dispatchers.IO) { OfflineAudio(context).releaseAllBut(claimed) }
    }

    /**
     * Saves the next chapters of the novels heard lately, once a night, while the phone charges on an
     * unmetered connection; [count] 0 turns it off.
     */
    fun syncNight(context: Context, count: Int) {
        val work = WorkManager.getInstance(context)
        if (count <= 0) {
            work.cancelUniqueWork(NIGHT_WORK)
            return
        }
        val now = LocalDateTime.now()
        var next = now.withHour(NIGHT_HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<OfflineWorker>(1, TimeUnit.DAYS)
            .setInputData(workDataOf(OfflineWorker.KEY_NIGHT to true))
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        work.enqueueUniquePeriodicWork(NIGHT_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
