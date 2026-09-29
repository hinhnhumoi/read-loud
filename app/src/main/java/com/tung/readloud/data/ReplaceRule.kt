package com.tung.readloud.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** "Read [pattern] as [replacement]". Plain patterns match whole words, ignoring case. */
@Entity(tableName = "replace_rules")
data class ReplaceRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean = false,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface ReplaceRuleDao {
    @Query("SELECT * FROM replace_rules ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ReplaceRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: ReplaceRule): Long

    @Query("DELETE FROM replace_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

class CompiledRule(val regex: Regex, val replacement: String)

object ReplaceRules {
    private const val WORD_BEFORE = "(?<![\\p{L}\\p{N}])"
    private const val WORD_AFTER = "(?![\\p{L}\\p{N}])"

    /** Compiles enabled rules, longest pattern first so "Diệp Thu" wins over "Diệp"; invalid regexes are skipped. */
    fun compile(rules: List<ReplaceRule>): List<CompiledRule> = rules
        .filter { it.enabled && it.pattern.isNotBlank() }
        .sortedByDescending { it.pattern.length }
        .mapNotNull { rule ->
            runCatching {
                if (rule.isRegex) {
                    CompiledRule(Regex(rule.pattern, RegexOption.IGNORE_CASE), rule.replacement)
                } else {
                    CompiledRule(
                        Regex(WORD_BEFORE + Regex.escape(rule.pattern.trim()) + WORD_AFTER, RegexOption.IGNORE_CASE),
                        Regex.escapeReplacement(rule.replacement),
                    )
                }
            }.getOrNull()
        }

    fun apply(rules: List<CompiledRule>, text: String): String =
        rules.fold(text) { acc, rule -> runCatching { rule.regex.replace(acc, rule.replacement) }.getOrDefault(acc) }
}

class RuleRepository(context: android.content.Context) {
    private val dao = AppDatabase.get(context).rules()

    val rules: Flow<List<ReplaceRule>> = dao.observeAll()

    suspend fun save(rule: ReplaceRule) = dao.upsert(rule)

    suspend fun delete(id: Long) = dao.delete(id)
}
