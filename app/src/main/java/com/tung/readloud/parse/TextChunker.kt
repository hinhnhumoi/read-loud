package com.tung.readloud.parse

/** Groups paragraphs into TTS-sized chunks, splitting long paragraphs on sentence ends. */
object TextChunker {
    private val sentenceEnd = Regex("(?<=[.!?…;:][\"”’)]?)\\s+")

    fun chunk(paragraphs: List<String>, maxLen: Int): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotBlank()) out += sb.toString().trim()
            sb.setLength(0)
        }
        for (paragraph in paragraphs) {
            val pieces = if (paragraph.length <= maxLen) listOf(paragraph) else splitLong(paragraph, maxLen)
            for (piece in pieces) {
                if (sb.isNotEmpty() && sb.length + piece.length + 1 > maxLen) flush()
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(piece)
            }
        }
        flush()
        return out
    }

    private fun splitLong(text: String, maxLen: Int): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) out += sb.toString()
            sb.setLength(0)
        }
        for (sentence in text.split(sentenceEnd)) {
            var rest = sentence
            while (rest.length > maxLen) {
                flush()
                val cut = rest.lastIndexOf(' ', maxLen).takeIf { it > 0 } ?: maxLen
                out += rest.substring(0, cut)
                rest = rest.substring(cut).trimStart()
            }
            if (sb.isNotEmpty() && sb.length + rest.length + 1 > maxLen) flush()
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(rest)
        }
        flush()
        return out
    }
}
