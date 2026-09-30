package com.tung.readloud.ui

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

    /** Offers to save the next chapters for offline listening, or to stop a download already running. */
    fun showDownload(context: Context, systemVoice: Boolean, novelId: Long? = null) {
        if (ReaderService.state.value.download != null) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.download_cancel_title)
                .setMessage(R.string.download_cancel_message)
                .setPositiveButton(R.string.download_cancel) { _, _ -> ReaderService.send(context, ReaderService.ACTION_CANCEL_DOWNLOAD) }
                .setNegativeButton(R.string.download_keep, null)
                .show()
            return
        }
        val counts = intArrayOf(5, 10, 20)
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.download_title)
            .setItems(counts.map { context.getString(R.string.download_option, it) }.toTypedArray()) { _, which ->
                ReaderService.send(context, ReaderService.ACTION_DOWNLOAD) {
                    putExtra(ReaderService.EXTRA_COUNT, counts[which])
                    if (novelId != null) putExtra(ReaderService.EXTRA_NOVEL_ID, novelId)
                }
            }
        if (systemVoice) builder.setMessage(R.string.download_system_note)
        builder.show()
    }

    fun literata(context: Context) = ResourcesCompat.getFont(context, R.font.literata_semibold)
}
