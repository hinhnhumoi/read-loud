package com.tung.readloud.ui

import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.tung.readloud.R
import com.tung.readloud.data.NovelRepository
import com.tung.readloud.follow.NewChapterWorker
import com.tung.readloud.databinding.ViewStatusBannerBinding
import com.tung.readloud.tts.PlaybackStatus
import com.tung.readloud.tts.ReaderService
import kotlinx.coroutines.launch

/** Shows the one state that needs the listener's hand, with the action that fixes it. */
object StatusBanner {

    fun bind(banner: ViewStatusBannerBinding, now: NowPlaying, activity: MainActivity) {
        val s = now.state
        val verifyUrl = s.verifyUrl
        when {
            s.status == PlaybackStatus.ERROR && verifyUrl != null -> show(
                banner, activity,
                icon = R.drawable.ic_shield,
                title = activity.getString(R.string.verify_needed),
                text = activity.getString(R.string.verify_hint),
                primary = activity.getString(R.string.action_verify) to {
                    activity.startActivity(VerifyActivity.intent(activity, verifyUrl, resume = true, chunkIndex = s.verifyResumeIndex, novelId = s.novelId))
                },
            )
            s.status == PlaybackStatus.ERROR && s.ttsNeedsData -> show(
                banner, activity,
                title = s.message ?: activity.getString(R.string.error_tts_missing),
                warn = true,
                primary = activity.getString(R.string.action_install_voice) to { Dialogs.openTtsSettings(activity) },
            )
            s.status == PlaybackStatus.ERROR -> show(
                banner, activity,
                title = s.message ?: activity.getString(R.string.status_label_error),
                warn = true,
                secondary = activity.getString(R.string.action_retry) to { ReaderService.send(activity, ReaderService.ACTION_PLAY) },
                tertiary = activity.getString(R.string.action_event_log) to { Dialogs.showEventLog(activity) },
            )
            s.status == PlaybackStatus.FINISHED -> {
                val novel = now.item?.novel?.takeIf { !now.item.isBook }
                show(
                    banner, activity,
                    title = activity.getString(R.string.banner_finished_title),
                    text = if (novel?.followNew == true) activity.getString(R.string.following_new) else s.message,
                    primary = novel?.takeIf { !it.followNew }?.let { n ->
                        activity.getString(R.string.action_follow_new) to {
                            activity.lifecycleScope.launch {
                                NovelRepository(activity).setFollow(n.id, true)
                                NewChapterWorker.sync(activity)
                            }
                            Unit
                        }
                    },
                    secondary = activity.getString(R.string.action_paste_other) to { activity.showAddSheet() },
                    tertiary = activity.getString(R.string.action_event_log) to { Dialogs.showEventLog(activity) },
                )
            }
            else -> banner.bannerRoot.isVisible = false
        }
    }

    private fun show(
        banner: ViewStatusBannerBinding,
        activity: MainActivity,
        title: String,
        text: String? = null,
        icon: Int? = null,
        warn: Boolean = false,
        primary: Pair<String, () -> Unit>? = null,
        secondary: Pair<String, () -> Unit>? = null,
        tertiary: Pair<String, () -> Unit>? = null,
    ) {
        banner.bannerRoot.isVisible = true
        banner.bannerIcon.isVisible = icon != null
        icon?.let(banner.bannerIcon::setImageResource)
        banner.bannerTitle.text = title
        banner.bannerTitle.setTextColor(ContextCompat.getColor(activity, if (warn) R.color.rl_warn else R.color.rl_text))
        banner.bannerText.text = text
        banner.bannerText.isVisible = !text.isNullOrBlank()
        banner.bannerActions.isVisible = primary != null || secondary != null || tertiary != null
        bindButton(banner.bannerPrimary, primary)
        bindButton(banner.bannerSecondary, secondary)
        bindButton(banner.bannerTertiary, tertiary)
    }

    private fun bindButton(button: com.google.android.material.button.MaterialButton, action: Pair<String, () -> Unit>?) {
        button.isVisible = action != null
        if (action == null) return
        button.text = action.first
        button.setOnClickListener { action.second() }
    }
}
