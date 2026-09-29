package com.tung.readloud.book

import java.io.IOException
import java.text.Normalizer

/** One line of text as the PDF placed it, in points from the page's top-left corner; [y] is the baseline. */
data class PdfLine(val text: String, val left: Float, val right: Float, val y: Float)

/** A bookmark from the PDF outline, pointing at a zero-based page. */
data class PdfMark(val title: String, val page: Int)

/**
 * Turns the lines of a text PDF back into chapters of paragraphs: drops page numbers and running headers
 * and footers, glues together lines that the page width broke apart, then splits at the bookmarks or,
 * without usable bookmarks, at "Chương N" headings like a TXT file.
 */
object PdfLayout {
    /** Lines at the top and bottom of each page that may be running headers, footers or page numbers. */
    private const val EDGE_LINES = 2
    private const val MIN_TEXT_CHARS = 50
    private const val INTRO_MIN_CHARS = 200
    private const val BODY_LINE_CHARS = 20

    private val pageNumber = Regex("(?iu)^[-–—|\\s]*(trang|page|tr\\.|p\\.)?\\s*(\\d{1,4}|[ivxlc]{1,7})(\\s*(/|of|trên|\\|)\\s*\\d{1,4})?[-–—|\\s]*$")
    private val whitespace = Regex("[\\s\\u00a0\\u200b]+")
    private const val SENTENCE_END = ".!?…:;\"”’»)]"
    private const val DIALOGUE_START = "\"“‘«-–—"

    fun build(title: String, author: String?, pages: List<List<PdfLine>>, marks: List<PdfMark>): ParsedBook {
        val clean = pages.map { page ->
            page.mapNotNull { line ->
                val text = whitespace.replace(Normalizer.normalize(line.text, Normalizer.Form.NFC), " ").trim()
                if (text.isEmpty()) null else line.copy(text = text)
            }
        }
        if (clean.sumOf { page -> page.sumOf { it.text.length } } < MIN_TEXT_CHARS) {
            throw IOException("PDF này không có chữ (có thể là bản scan dạng ảnh), app chỉ đọc được PDF dạng chữ")
        }
        val paragraphs = paragraphs(stripFurniture(clean))
        if (paragraphs.isEmpty()) throw IOException("Không tìm thấy nội dung trong PDF")
        val chapters = byMarks(paragraphs, marks) ?: TxtParser.split(paragraphs.map { it.text })
        return ParsedBook(title, author, chapters)
    }

    /** Removes page numbers and lines repeated at the same edge of many pages. */
    internal fun stripFurniture(pages: List<List<PdfLine>>): List<List<PdfLine>> {
        val exactCounts = HashMap<String, Int>()
        val maskedCounts = HashMap<String, Int>()
        for (page in pages) {
            val edges = edgeLines(page)
            edges.map { exactKey(it.text) }.distinct().forEach { exactCounts.merge(it, 1, Int::plus) }
            edges.map { maskedKey(it.text) }.distinct().forEach { maskedCounts.merge(it, 1, Int::plus) }
        }
        val exactMin = if (pages.size <= 3) 2 else 3
        val maskedMin = maxOf(exactMin, (pages.size + 1) / 2)

        fun isFurniture(line: PdfLine): Boolean {
            val text = line.text
            if (pageNumber.matches(text)) return true
            // Repeated lines that read like a sentence, such as "Ừ." or "Hết chương 3.", are story text.
            if (pages.size < 2 || text.last() in SENTENCE_END) return false
            if ((exactCounts[exactKey(text)] ?: 0) >= exactMin) return true
            // With the numbers masked out, "Chương 1" and "Chương 2" would look alike, so spare headings.
            return (maskedCounts[maskedKey(text)] ?: 0) >= maskedMin && !TxtParser.isHeading(text)
        }

        return pages.map { page ->
            val edges = edgeLines(page).toSet()
            page.filterNot { it in edges && isFurniture(it) }
        }
    }

    private fun edgeLines(page: List<PdfLine>): List<PdfLine> =
        if (page.size <= EDGE_LINES * 2) page else page.take(EDGE_LINES) + page.takeLast(EDGE_LINES)

    private fun exactKey(text: String) = text.lowercase()

    private fun maskedKey(text: String) = text.lowercase().replace(Regex("\\d+"), "#")

    internal data class Paragraph(val text: String, val page: Int)

    /** Where the body text sits on a page, measured from its full-width lines. */
    private class Metrics(val left: Float, val right: Float, val charWidth: Float, val lineStep: Float)

    private fun steps(page: List<PdfLine>) = page.zipWithNext { a, b -> b.y - a.y }.filter { it > 0f }

    private fun metrics(lines: List<PdfLine>, lineSteps: List<Float>): Metrics? {
        val body = lines.filter { it.text.length >= BODY_LINE_CHARS && it.right > it.left }
        if (body.size < 3) return null
        val rights = body.map { it.right }.sorted()
        val steps = lineSteps.sorted()
        return Metrics(
            left = body.minOf { it.left },
            right = rights[(rights.size * 9 / 10).coerceAtMost(rights.size - 1)],
            charWidth = body.map { (it.right - it.left) / it.text.length }.sorted()[body.size / 2],
            lineStep = if (steps.isEmpty()) 0f else steps[steps.size / 2],
        )
    }

    /** Joins lines back into paragraphs, also across page breaks. */
    internal fun paragraphs(pages: List<List<PdfLine>>): List<Paragraph> {
        val document = metrics(pages.flatten(), pages.flatMap(::steps))
        val pageMetrics = pages.map { metrics(it, steps(it)) ?: document }
        val lines = pages.flatMapIndexed { index, page -> page.map { index to it } }

        val out = mutableListOf<Paragraph>()
        val current = StringBuilder()
        var startPage = 0
        lines.forEachIndexed { i, (page, line) ->
            if (current.isEmpty()) {
                startPage = page
                current.append(line.text)
            } else {
                appendLine(current, line.text)
            }
            val next = lines.getOrNull(i + 1)
            if (next == null || endsParagraph(line, pageMetrics[page], next.second, pageMetrics[next.first], next.first == page)) {
                out += Paragraph(current.toString(), startPage)
                current.clear()
            }
        }
        return out
    }

    private fun appendLine(current: StringBuilder, text: String) {
        val last = current.last()
        val beforeLast = current.getOrNull(current.length - 2)
        if (last == '-' && beforeLast?.isLetter() == true && text.first().isLowerCase()) {
            current.setLength(current.length - 1)
            current.append(text)
        } else {
            current.append(' ').append(text)
        }
    }

    private fun endsParagraph(line: PdfLine, m: Metrics?, next: PdfLine, nextM: Metrics?, samePage: Boolean): Boolean {
        val endsSentence = line.text.last() in SENTENCE_END
        if (endsSentence && next.text.first() in DIALOGUE_START) return true
        if (m == null || nextM == null) return endsSentence
        if (next.left > nextM.left + 1.5f * nextM.charWidth) return true
        if (samePage && m.lineStep > 0f && next.y - line.y > 1.6f * m.lineStep) return true
        val short = line.right < m.right - 3f * m.charWidth
        // A short line followed by lowercase text is ragged-right wrapping, not the end of a paragraph.
        return short && (endsSentence || !next.text.first().isLowerCase())
    }

    /** Chapters from the bookmarks, or null when there are too few usable ones. */
    internal fun byMarks(paragraphs: List<Paragraph>, marks: List<PdfMark>): List<BookChapter>? {
        val lastPage = paragraphs.last().page
        var previousPage = -1
        val ordered = marks.filter { mark ->
            val ok = mark.title.isNotBlank() && mark.page >= previousPage && mark.page <= lastPage
            if (ok) previousPage = mark.page
            ok
        }
        if (ordered.map { it.page }.distinct().size < 2) return null

        class Start(val titles: MutableList<String>, val index: Int, val skipHeading: Boolean)
        val starts = mutableListOf<Start>()
        for (mark in ordered) {
            val title = Normalizer.normalize(mark.title, Normalizer.Form.NFC).let { whitespace.replace(it, " ").trim() }
            val from = maxOf(
                paragraphs.indexOfFirst { it.page >= mark.page }.takeIf { it >= 0 } ?: paragraphs.size,
                (starts.lastOrNull()?.index ?: -1) + 1,
            )
            val titleKey = matchKey(title)
            var heading: Int? = null
            var exact = false
            var k = from
            while (k < paragraphs.size && paragraphs[k].page <= mark.page) {
                val key = matchKey(paragraphs[k].text)
                if (key == titleKey) {
                    heading = k
                    exact = true
                    break
                }
                if (titleKey.isNotEmpty() && key.startsWith(titleKey) && !key[titleKey.length].isDigit()) {
                    heading = k
                    break
                }
                k++
            }
            val index = heading ?: paragraphs.indexOfFirst { it.page >= mark.page }.takeIf { it >= 0 } ?: paragraphs.size
            val previous = starts.lastOrNull()
            if (previous != null && index <= previous.index) {
                previous.titles += title
            } else {
                starts += Start(mutableListOf(title), index, exact)
            }
        }

        val out = mutableListOf<BookChapter>()
        val intro = paragraphs.subList(0, starts.first().index)
        if (intro.sumOf { it.text.length } >= INTRO_MIN_CHARS) out += BookChapter("Mở đầu", intro.map { it.text })
        var pendingTitles = mutableListOf<String>()
        starts.forEachIndexed { i, start ->
            val end = starts.getOrNull(i + 1)?.index ?: paragraphs.size
            val bodyStart = if (start.skipHeading) start.index + 1 else start.index
            val body = if (bodyStart < end) paragraphs.subList(bodyStart, end).map { it.text } else emptyList()
            pendingTitles += start.titles
            if (body.isNotEmpty()) {
                out += BookChapter(pendingTitles.distinct().joinToString(" · "), body)
                pendingTitles = mutableListOf()
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun matchKey(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
}
