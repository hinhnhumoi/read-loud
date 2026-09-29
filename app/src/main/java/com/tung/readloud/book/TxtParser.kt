package com.tung.readloud.book

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Splits a plain-text novel into chapters at lines like "Chương 12: ...". Volume headings with no text of
 * their own are folded into the next chapter's title; a file with no headings is cut into equal parts.
 */
object TxtParser {
    private const val HEADING_MAX_CHARS = 120
    private const val INTRO_MIN_CHARS = 200
    private const val PART_CHARS = 8000

    private val heading = Regex(
        "(?iu)^(chương|chuong|chapter|hồi|quyển|quyen|tập|phần|vol\\.?|volume)\\s*([0-9]+|[ivxlcdm]+)\\b.*$|^第\\s*[0-9一二三四五六七八九十百千]+\\s*[章回卷节].*$",
    )
    private val whitespace = Regex("[\\s\\u00a0\\u200b]+")

    fun parse(bytes: ByteArray, fallbackTitle: String): ParsedBook {
        val lines = decode(bytes).lines()
            .map { whitespace.replace(it, " ").trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) throw IOException("File TXT trống")
        return ParsedBook(fallbackTitle, null, split(lines))
    }

    /** Chapters from non-empty paragraphs: at headings when there are at least two, else in equal parts. */
    internal fun split(lines: List<String>): List<BookChapter> {
        val headingIndexes = lines.indices.filter { isHeading(lines[it]) }
        return if (headingIndexes.size >= 2) byHeadings(lines, headingIndexes) else byLength(lines)
    }

    internal fun isHeading(line: String): Boolean = line.length <= HEADING_MAX_CHARS && heading.matches(line)

    private fun byHeadings(lines: List<String>, headings: List<Int>): List<BookChapter> {
        val out = mutableListOf<BookChapter>()
        val intro = lines.subList(0, headings.first())
        if (intro.sumOf { it.length } >= INTRO_MIN_CHARS) out += BookChapter("Mở đầu", intro)
        var pendingTitle: String? = null
        headings.forEachIndexed { k, start ->
            val end = headings.getOrNull(k + 1) ?: lines.size
            val title = listOfNotNull(pendingTitle, lines[start]).joinToString(" · ")
            val body = lines.subList(start + 1, end)
            if (body.isEmpty()) {
                pendingTitle = title
            } else {
                out += BookChapter(title, body)
                pendingTitle = null
            }
        }
        return out
    }

    private fun byLength(lines: List<String>): List<BookChapter> {
        val out = mutableListOf<BookChapter>()
        val current = mutableListOf<String>()
        var size = 0
        for (line in lines) {
            current += line
            size += line.length
            if (size >= PART_CHARS) {
                out += BookChapter("Phần ${out.size + 1}", current.toList())
                current.clear()
                size = 0
            }
        }
        if (current.isNotEmpty()) out += BookChapter("Phần ${out.size + 1}", current.toList())
        return out
    }

    /** UTF-8 or UTF-16 by BOM, strict UTF-8 otherwise, then the Vietnamese Windows code page. */
    internal fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            val fallback = runCatching { Charset.forName("windows-1258") }.getOrDefault(Charsets.ISO_8859_1)
            return String(bytes, fallback)
        }
    }
}
