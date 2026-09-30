package com.tung.readloud.ui

import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tung.readloud.R
import com.tung.readloud.data.ReplaceRule
import com.tung.readloud.data.RuleRepository
import com.tung.readloud.databinding.ActivityRulesBinding
import com.tung.readloud.databinding.ItemRuleBinding
import kotlinx.coroutines.launch

/** The list of "read X as Y" rules, general or for one novel; changes apply to chunks queued after the edit. */
class RulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRulesBinding
    private val repo by lazy { RuleRepository(this) }
    private val novelId by lazy { intent.getLongExtra(EXTRA_NOVEL_ID, -1L).takeIf { it > 0 } }
    private val adapter = RuleAdapter(
        onClick = { RuleDialog.show(this, existing = it) },
        onToggle = { rule, enabled -> lifecycleScope.launch { repo.save(rule.copy(enabled = enabled)) } },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnAdd.setOnClickListener { RuleDialog.show(this, novelId = novelId) }
        intent.getStringExtra(EXTRA_NOVEL_NAME)?.let { name ->
            binding.toolbar.title = getString(R.string.own_rules_title, name)
            binding.toolbar.subtitle = getString(R.string.own_rules_hint)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.rulesFor(novelId).collect { rules ->
                    adapter.submitList(rules)
                    binding.empty.isVisible = rules.isEmpty()
                }
            }
        }
    }

    private class RuleAdapter(
        private val onClick: (ReplaceRule) -> Unit,
        private val onToggle: (ReplaceRule, Boolean) -> Unit,
    ) : ListAdapter<ReplaceRule, RuleAdapter.Holder>(Diff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        inner class Holder(private val binding: ItemRuleBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(rule: ReplaceRule) {
                val ctx = binding.root.context
                binding.pattern.text = rule.pattern
                binding.replacement.text = if (rule.replacement.isEmpty()) {
                    ctx.getString(R.string.rule_removed)
                } else {
                    ctx.getString(R.string.rule_reads_as, rule.replacement)
                }
                binding.regexTag.isVisible = rule.isRegex
                binding.enabled.setOnCheckedChangeListener(null)
                binding.enabled.isChecked = rule.enabled
                binding.enabled.setOnCheckedChangeListener { _, checked -> onToggle(rule, checked) }
                binding.root.setOnClickListener { onClick(rule) }
            }
        }

        private object Diff : DiffUtil.ItemCallback<ReplaceRule>() {
            override fun areItemsTheSame(a: ReplaceRule, b: ReplaceRule) = a.id == b.id
            override fun areContentsTheSame(a: ReplaceRule, b: ReplaceRule) = a == b
        }
    }

    companion object {
        private const val EXTRA_NOVEL_ID = "novel_id"
        private const val EXTRA_NOVEL_NAME = "novel_name"

        /** The rules for [novelId] only; without one, the general rules. */
        fun intent(context: Context, novelId: Long? = null, novelName: String? = null): Intent =
            Intent(context, RulesActivity::class.java).apply {
                if (novelId != null) putExtra(EXTRA_NOVEL_ID, novelId)
                if (novelName != null) putExtra(EXTRA_NOVEL_NAME, novelName)
            }
    }
}
