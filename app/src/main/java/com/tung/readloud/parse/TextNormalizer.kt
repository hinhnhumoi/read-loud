package com.tung.readloud.parse

/**
 * Cleans chapter text before it is shown and spoken. [clean] drops junk lines such as translator credits
 * and vote begging; [forSpeech] rewrites what the voice would read awkwardly, after the user's own rules.
 */
object TextNormalizer {
    private const val JUNK_MAX_CHARS = 120

    private val junkLines = listOf(
        Regex("(?iu)^(edit(or)?|beta(-?reader)?|biên tập|dịch giả|dịch|người dịch|translator|trans|converter|convert|nguồn|source|raw)\\s*[:：]"),
        Regex("(?iu)(cầu|xin)\\s+(vote|phiếu|đề cử|like|kim phiếu|nguyệt phiếu|donate|ủng hộ|comment|bình luận)"),
        Regex("(?iu)^(chương|chap)\\s*(trước|sau|tiếp)$|^mục lục$|^(previous|next)(\\s+chapter)?$"),
        Regex("^[\\s\\p{P}\\p{S}]+$"),
    )

    fun clean(paragraphs: List<String>): List<String> = paragraphs.filterNot { p ->
        val line = p.trim()
        line.length <= JUNK_MAX_CHARS && junkLines.any { it.containsMatchIn(line) }
    }

    private val ellipsis = Regex("\\.{3,}|…+|。{2,}")
    private val repeatedMarks = Regex("([!?])\\1+")
    private val levelNumber = Regex("(?i)\\blv\\.?\\s*(\\d+)")
    private val levelWord = Regex("(?i)\\blv\\b\\.?")
    private val romanAfterWord = Regex("(?iu)\\b(chương|quyển|tập|phần|hồi|chapter|book|vol\\.?)(?-i)\\s+([IVXLCDM]+)\\b")
    private val ampersand = Regex("\\s*&\\s*")
    private val decoration = Regex("[*~#=_^|<>\\[\\]{}【】「」『』《》〈〉]+")
    private val spaces = Regex("[ \\t\\u00a0]+")

    fun forSpeech(text: String): String {
        var t = text
        t = ellipsis.replace(t, "…")
        t = repeatedMarks.replace(t, "$1")
        t = levelNumber.replace(t) { "cấp " + it.groupValues[1] }
        t = levelWord.replace(t, "cấp")
        t = romanAfterWord.replace(t) { m ->
            val n = romanToInt(m.groupValues[2])
            if (n in 1..3999) "${m.groupValues[1]} $n" else m.value
        }
        t = ampersand.replace(t, " và ")
        t = decoration.replace(t, " ")
        t = spaces.replace(t, " ")
        return t.trim()
    }

    internal fun romanToInt(roman: String): Int {
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        for (i in roman.indices) {
            val v = values[roman[i]] ?: return -1
            val next = if (i + 1 < roman.length) values[roman[i + 1]] ?: return -1 else 0
            total += if (v < next) -v else v
        }
        return total
    }
}
