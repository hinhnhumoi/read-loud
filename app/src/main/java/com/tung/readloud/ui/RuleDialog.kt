package com.tung.readloud.ui

import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tung.readloud.R
import com.tung.readloud.data.ReplaceRule
import com.tung.readloud.data.ReplaceRules
import com.tung.readloud.data.RuleRepository
import com.tung.readloud.databinding.DialogRuleBinding
import com.tung.readloud.parse.TextNormalizer
import kotlinx.coroutines.launch

/** Add or edit one pronunciation rule; [sample] is the text the rule is previewed against. */
object RuleDialog {

    fun show(activity: AppCompatActivity, existing: ReplaceRule? = null, prefill: String? = null, sample: String? = null) {
        val binding = DialogRuleBinding.inflate(LayoutInflater.from(activity))
        val repo = RuleRepository(activity)
        binding.pattern.setText(existing?.pattern ?: prefill?.trim().orEmpty())
        binding.replacement.setText(existing?.replacement.orEmpty())
        binding.isRegex.isChecked = existing?.isRegex ?: false

        fun draft() = ReplaceRule(
            id = existing?.id ?: 0,
            pattern = binding.pattern.text?.toString().orEmpty(),
            replacement = binding.replacement.text?.toString().orEmpty(),
            isRegex = binding.isRegex.isChecked,
            enabled = existing?.enabled ?: true,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )

        fun refreshPreview() {
            val rule = draft()
            val source = sample?.takeIf { it.isNotBlank() } ?: rule.pattern
            if (rule.pattern.isBlank()) {
                binding.preview.text = activity.getString(R.string.rule_preview_empty)
                return
            }
            val compiled = ReplaceRules.compile(listOf(rule))
            binding.preview.text = if (compiled.isEmpty()) {
                activity.getString(R.string.rule_invalid_regex)
            } else {
                val spoken = TextNormalizer.forSpeech(ReplaceRules.apply(compiled, source))
                if (spoken.isBlank()) {
                    activity.getString(R.string.rule_removed)
                } else {
                    activity.getString(R.string.rule_preview, spoken.take(PREVIEW_CHARS))
                }
            }
        }
        binding.pattern.doAfterTextChanged { refreshPreview() }
        binding.replacement.doAfterTextChanged { refreshPreview() }
        binding.isRegex.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        refreshPreview()

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) R.string.rule_add else R.string.rule_edit)
            .setView(binding.root)
            .setPositiveButton(R.string.rule_save) { _, _ ->
                val rule = draft()
                if (rule.pattern.isBlank()) return@setPositiveButton
                activity.lifecycleScope.launch { repo.save(rule.copy(pattern = rule.pattern.trim())) }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) {
            builder.setNeutralButton(R.string.rule_delete) { _, _ -> activity.lifecycleScope.launch { repo.delete(existing.id) } }
        }
        builder.show()
    }

    private const val PREVIEW_CHARS = 300
}
