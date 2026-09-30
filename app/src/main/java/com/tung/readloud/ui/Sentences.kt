package com.tung.readloud.ui

/** Splits chunk text into sentences and phrases, for showing the line being spoken. */
object Sentences {
    private val sentenceEnd = Regex("(?<=[.!?…][\"”’)»]?)\\s+")
    private val phraseEnd = Regex("(?<=[,;:—–])\\s+")

    /** Start offsets and text of each sentence, in order. */
    fun split(text: String): List<Pair<Int, String>> = pieces(text, sentenceEnd)

    fun phrases(sentence: String): List<Pair<Int, String>> = pieces(sentence, phraseEnd)

    /** The sentence around [offset], with the ones before and after it. */
    fun around(text: String, offset: Int): Triple<String?, Pair<Int, String>, String?> {
        val all = split(text).ifEmpty { listOf(0 to text) }
        val i = all.indexOfLast { it.first <= offset }.coerceAtLeast(0)
        return Triple(all.getOrNull(i - 1)?.second, all[i], all.getOrNull(i + 1)?.second)
    }

    fun last(text: String): String? = split(text).lastOrNull()?.second

    fun first(text: String): String? = split(text).firstOrNull()?.second

    private fun pieces(text: String, boundary: Regex): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var start = 0
        for (m in boundary.findAll(text)) {
            val piece = text.substring(start, m.range.first)
            if (piece.isNotBlank()) out += start to piece
            start = m.range.last + 1
        }
        if (start < text.length && text.substring(start).isNotBlank()) out += start to text.substring(start)
        return out
    }
}
