package com.tung.readloud.follow

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tung.readloud.R
import com.tung.readloud.data.ChapterCache
import com.tung.readloud.data.EventLog
import com.tung.readloud.data.Novel
import com.tung.readloud.data.NovelRepository
import com.tung.readloud.fetch.PageFetcher
import com.tung.readloud.fetch.TocLoader
import com.tung.readloud.parse.ChapterParser
import com.tung.readloud.parse.TocParser
import com.tung.readloud.ui.NovelDetailActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Every morning, reloads the chapter list of each followed novel. New chapters are announced, and on an
 * unmetered connection the first few are saved so they can be heard offline.
 */
class NewChapterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val novels = NovelRepository(context)
    private val fetcher = PageFetcher(context)

    override suspend fun doWork(): Result {
        val followed = novels.followed()
        EventLog.init(applicationContext)
        EventLog.log("New chapters: checking ${followed.size} novel(s)")
        for (novel in followed) {
            try {
                check(novel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.log("New chapters: '${novel.name}' failed", e)
            }
        }
        return Result.success()
    }

    private suspend fun check(novel: Novel) {
        val tocUrl = novel.tocUrl ?: return
        val before = novels.tocEntries(novel.id)
        val loaded = TocLoader(fetcher).load(tocUrl) { _, _ -> }
        // A page that failed to load fully would look like chapters vanished; keep the longer list.
        if (loaded.size <= before.size) {
            EventLog.log("New chapters: '${novel.name}' unchanged at ${before.size}")
            return
        }
        novels.saveToc(novel.id, tocUrl, loaded)
        val current = TocParser.normalize(novel.currentUrl)
        val index = loaded.indexOfFirst { TocParser.normalize(it.url) == current }
        val firstNew = maxOf(novel.seenTocCount ?: before.size, index + 1)
        val fresh = loaded.drop(firstNew)
        EventLog.log("New chapters: '${novel.name}' has ${loaded.size - before.size} more, ${fresh.size} unheard")
        if (fresh.isEmpty()) return
        if (unmetered()) save(fresh.take(SAVE_AHEAD))
        announce(novel, fresh.size, fresh.first().title)
    }

    private suspend fun save(entries: List<TocParser.Entry>) {
        val cache = ChapterCache(applicationContext)
        val parser = ChapterParser()
        for (entry in entries) {
            if (withContext(Dispatchers.IO) { cache.contains(entry.url) }) continue
            val chapter = runCatching { parser.parse(entry.url, fetcher.fetch(entry.url)) }.getOrNull() ?: break
            if (chapter.paragraphs.isNotEmpty()) withContext(Dispatchers.IO) { cache.put(chapter) }
        }
    }

    private fun unmetered(): Boolean {
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun announce(novel: Novel, count: Int, firstTitle: String) {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) {
            return
        }
        val manager = ctx.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, ctx.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            ctx, novel.id.toInt(), NovelDetailActivity.intent(ctx, novel.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.follow_notify_title, count, novel.name))
            .setContentText(firstTitle)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFY_BASE + novel.id.toInt(), notification) }
    }

    companion object {
        private const val WORK_NAME = "new-chapters"
        private const val NOW_NAME = "new-chapters-now"
        private const val CHANNEL_ID = "updates"
        private const val NOTIFY_BASE = 1000
        private const val SAVE_AHEAD = 5
        private const val CHECK_HOUR = 7

        /** Checks the followed novels once, now, instead of waiting for the morning. */
        fun checkNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<NewChapterWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Runs the morning check while any novel is followed, and stops it when none is. */
        suspend fun sync(context: Context) {
            val work = WorkManager.getInstance(context)
            if (!NovelRepository(context).anyFollowed()) {
                work.cancelUniqueWork(WORK_NAME)
                return
            }
            val now = LocalDateTime.now()
            var next = now.withHour(CHECK_HOUR).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<NewChapterWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // Keeping the schedule already set means opening the app does not push the check back.
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
