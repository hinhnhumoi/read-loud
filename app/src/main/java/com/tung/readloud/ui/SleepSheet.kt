package com.tung.readloud.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tung.readloud.R
import com.tung.readloud.data.ProgressStore
import com.tung.readloud.databinding.SheetSleepBinding
import com.tung.readloud.tts.ReaderService
import com.tung.readloud.tts.ReaderState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** "Hẹn giờ tắt": a countdown ring, a few durations and chapter counts, and a way to switch it off. */
class SleepSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSleepBinding? = null
    private val binding get() = _binding!!

    /** Minutes, or chapters when negative. */
    private val choices = listOf(15, 30, 60, -1, -2, -3)
    private val optionViews = mutableListOf<TextView>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = SheetSleepBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        buildOptions()
        bindExtras()
        binding.btnOff.setOnClickListener {
            set(minutes = 0, chapters = 0)
            dismiss()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ReaderService.state.collect { render(it) } }
                launch {
                    while (true) {
                        delay(1_000)
                        if (ReaderService.state.value.sleepDeadline != null) render(ReaderService.state.value)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        optionViews.clear()
        super.onDestroyView()
    }

    private fun buildOptions() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val gap = (10 * density).toInt()
        choices.forEachIndexed { i, choice ->
            val label = when {
                choice > 0 -> getString(R.string.sleep_minutes, choice)
                choice == -1 -> getString(R.string.sleep_after_chapter_option)
                else -> getString(R.string.sleep_chapters_option, -choice)
            }
            val option = TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 15f
                maxLines = 1
                typeface = ResourcesCompat.getFont(ctx, R.font.be_vietnam_pro_semibold)
                setTextColor(ContextCompat.getColorStateList(ctx, R.color.choice_text))
                setBackgroundResource(R.drawable.bg_choice)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (choice > 0) set(minutes = choice, chapters = 0) else set(minutes = 0, chapters = -choice)
                }
            }
            val params = GridLayout.LayoutParams(GridLayout.spec(i / 3), GridLayout.spec(i % 3, 1f)).apply {
                width = 0
                height = (52 * density).toInt()
                setMargins(if (i % 3 == 0) 0 else gap / 2, if (i < 3) 0 else gap, if (i % 3 == 2) 0 else gap / 2, 0)
            }
            binding.options.addView(option, params)
            optionViews += option
        }
    }

    /** The three switches under the choices; each row toggles its switch, as a setting row does. */
    private fun bindExtras() {
        val store = ProgressStore(requireContext())
        val rows = listOf(
            Triple(binding.fadeRow, binding.fadeSwitch, store::setSleepFade),
            Triple(binding.shakeRow, binding.shakeSwitch, store::setSleepShake),
            Triple(binding.nightRow, binding.nightSwitch, store::setSleepAutoNight),
        )
        rows.forEach { (row, switch, save) ->
            // Only a touch saves; the switch is also set from the stored value below.
            switch.setOnCheckedChangeListener { button, checked ->
                if (button.isPressed) lifecycleScope.launch { save(checked) }
            }
            row.setOnClickListener {
                if (!switch.isEnabled) return@setOnClickListener
                switch.toggle()
                lifecycleScope.launch { save(switch.isChecked) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            combine(store.sleepFade, store.sleepShake, store.sleepAutoNight) { fade, shake, night -> Triple(fade, shake, night) }
                .collect { (fade, shake, night) ->
                    val b = _binding ?: return@collect
                    b.fadeSwitch.isChecked = fade
                    b.shakeSwitch.isChecked = shake
                    b.nightSwitch.isChecked = night
                    // A shake only counts while the voice fades, so without the fade it would never work.
                    b.shakeSwitch.isEnabled = fade
                    b.shakeRow.alpha = if (fade) 1f else 0.5f
                    b.shakeHint.setText(if (fade) R.string.sleep_shake_hint else R.string.sleep_shake_needs_fade)
                }
        }
    }

    private fun set(minutes: Int, chapters: Int) {
        ReaderService.send(requireContext(), ReaderService.ACTION_SET_SLEEP) {
            putExtra(ReaderService.EXTRA_SLEEP_MINUTES, minutes)
            putExtra(ReaderService.EXTRA_SLEEP_CHAPTERS, chapters)
        }
    }

    private fun render(s: ReaderState) {
        val b = _binding ?: return
        val deadline = s.sleepDeadline
        val left = deadline?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0) }
        when {
            left != null -> {
                val seconds = left / 1000
                b.ringTime.text = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
                b.ringLabel.setText(R.string.sleep_left)
                val fraction = if (s.sleepTotalMs > 0) left.toFloat() / s.sleepTotalMs else 0f
                b.ring.setProgressCompat((fraction * 1000).toInt(), false)
                val stopAt = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + left))
                b.sleepStatus.text = getString(R.string.sleep_stop_at, stopAt)
            }
            s.sleepChapters > 0 -> {
                b.ringTime.text = s.sleepChapters.toString()
                b.ringLabel.setText(R.string.sleep_chapters_unit)
                b.ring.setProgressCompat(1000, false)
                b.sleepStatus.text = if (s.sleepChapters == 1) {
                    getString(R.string.sleep_after_this_chapter)
                } else {
                    getString(R.string.sleep_after_n_chapters, s.sleepChapters)
                }
            }
            else -> {
                b.ringTime.text = "–"
                b.ringLabel.text = ""
                b.ring.setProgressCompat(0, false)
                b.sleepStatus.setText(R.string.sleep_none)
            }
        }
        val selected = when {
            s.sleepChapters > 0 -> choices.indexOf(-s.sleepChapters)
            s.sleepTotalMs > 0 && deadline != null -> choices.indexOf((s.sleepTotalMs / 60_000L).toInt())
            else -> -1
        }
        optionViews.forEachIndexed { i, v -> v.isSelected = i == selected }
        b.btnOff.isEnabled = deadline != null || s.sleepChapters > 0
    }

    companion object {
        const val TAG = "sleep"
    }
}
