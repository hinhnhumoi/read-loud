package com.tung.readloud.ui

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.tung.readloud.offline.OfflineSaver
import com.tung.readloud.data.NovelRepository
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatActivity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tung.readloud.R
import com.tung.readloud.data.EventLog
import com.tung.readloud.tts.ReaderService

/** Dialogs and system screens opened from more than one place. */
object Dialogs {
    private const val EVENT_LOG_SHOWN_CHARS = 20_000

    /** The playback log, so a stall on the phone can be looked at, or sent, without a computer. */
    fun showEventLog(context: Context) {
        val text = EventLog.read().takeLast(EVENT_LOG_SHOWN_CHARS).ifBlank { context.getString(R.string.event_log_empty) }
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val view = TextView(context).apply {
            this.text = text
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextColor(context.getColor(R.color.rl_muted))
            setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, 0)
        }
        val scroll = NestedScrollView(context).apply { addView(view) }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.event_log_title)
            .setView(scroll)
            .setPositiveButton(R.string.event_log_share) { _, _ ->
                val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, EventLog.read())
                context.startActivity(Intent.createChooser(share, context.getString(R.string.event_log_title)))
            }
            .setNeutralButton(R.string.event_log_clear) { _, _ -> EventLog.clear() }
            .setNegativeButton(R.string.action_close, null)
            .show()
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    fun isBatteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun showBattery(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_message)
            .setPositiveButton(R.string.battery_open_settings) { _, _ -> openBatterySettings(context) }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun openBatterySettings(context: Context) {
        val packageUri = Uri.parse("package:${context.packageName}")
        val candidates = buildList {
            if (!isBatteryUnrestricted(context)) add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
            if (Build.MANUFACTURER.contains("honor", true) || Build.MANUFACTURER.contains("huawei", true)) {
                add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
                add(Intent().setComponent(ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
        for (intent in candidates) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        Toast.makeText(context, R.string.toast_settings_unavailable, Toast.LENGTH_SHORT).show()
    }

    fun openTtsSettings(context: Context) {
        val intents = listOf(Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
        for (intent in intents) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    /** Offers to save the next chapters of a novel for offline listening, or to stop the saving under way. */
    fun showDownload(activity: AppCompatActivity, systemVoice: Boolean, novelId: Long? = null) {
        activity.lifecycleScope.launch {
            val running = OfflineSaver.progress(activity).first()
            if (running != null) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.download_cancel_title)
                    .setMessage(activity.getString(R.string.download_cancel_message, running.remaining))
                    .setPositiveButton(R.string.download_cancel) { _, _ -> activity.lifecycleScope.launch { OfflineSaver.stop(activity) } }
                    .setNegativeButton(R.string.download_keep, null)
                    .show()
                return@launch
            }
            val id = novelId ?: ReaderService.state.value.novelId ?: NovelRepository(activity).mostRecent()?.id
            if (id == null) {
                Toast.makeText(activity, R.string.download_nothing, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Items and a message do not show together in a dialog, so the explanation goes in the title area.
            val counts = intArrayOf(5, 10, 20)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.download_title)
                .setItems(counts.map { activity.getString(R.string.download_option, it) }.toTypedArray()) { _, which ->
                    OfflineSaver.saveNext(activity, id, counts[which])
                    Toast.makeText(activity, activity.getString(R.string.download_queued, counts[which]), Toast.LENGTH_SHORT).show()
                }
                .setCustomTitle(dialogTitle(activity, activity.getString(if (systemVoice) R.string.download_system_note else R.string.download_message)))
                .show()
        }
    }

    /** A dialog title with a line of explanation under it. */
    private fun dialogTitle(context: Context, note: String): android.view.View {
        val pad = (24 * context.resources.displayMetrics.density).toInt()
        return android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 3)
            addView(TextView(context).apply {
                setText(R.string.download_title)
                setTextAppearance(R.style.TextAppearance_RL_Headline)
            })
            addView(TextView(context).apply {
                text = note
                setTextAppearance(R.style.TextAppearance_RL_Meta)
                setPadding(0, pad / 3, 0, 0)
            })
        }
    }

    fun literata(context: Context) = ResourcesCompat.getFont(context, R.font.literata_semibold)
}
