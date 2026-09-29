package com.tung.readloud.book

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.Writer

class PdfLayoutTest {

    /** Same line collection as PdfParser, on desktop PDFBox. */
    private class LineStripper : PDFTextStripper() {
        val pages = mutableListOf<List<PdfLine>>()
        private var page = mutableListOf<PdfLine>()
        private val text = StringBuilder()
        private var left = Float.MAX_VALUE
        private var right = 0f
        private var y = 0f

        override fun startPage(page: PDPage?) {
            this.page = mutableListOf()
        }

        override fun endPage(page: PDPage?) {
            flush()
            pages += this.page
        }

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            this.text.append(text.orEmpty())
            textPositions?.forEach { p ->
                left = minOf(left, p.xDirAdj)
                right = maxOf(right, p.xDirAdj + p.widthDirAdj)
                y = maxOf(y, p.yDirAdj)
            }
        }

        override fun writeWordSeparator() {
            text.append(' ')
        }

        override fun writeLineSeparator() {
            flush()
        }

        private fun flush() {
            if (text.isNotBlank()) page += PdfLine(text.toString(), left, right, y)
            text.clear()
            left = Float.MAX_VALUE
            right = 0f
            y = 0f
        }
    }

    private class Sample(val pages: List<List<PdfLine>>, val marks: List<PdfMark>)

    /** sample.pdf starts every chapter on a new page; in sample-flow.pdf chapters and paragraphs run across pages. */
    private fun sample(name: String = "sample.pdf"): Sample {
        val bytes = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()
        PDDocument.load(bytes).use { doc ->
            val stripper = LineStripper().apply { sortByPosition = true }
            stripper.writeText(doc, object : Writer() {
                override fun write(cbuf: CharArray, off: Int, len: Int) {}
                override fun flush() {}
                override fun close() {}
            })
            val marks = mutableListOf<PdfMark>()
            fun walk(node: PDOutlineNode) {
                for (item in node.children()) {
                    val index = item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) } ?: -1
                    if (index >= 0) marks += PdfMark(item.title.trim(), index)
                    walk(item)
                }
            }
            doc.documentCatalog.documentOutline?.let(::walk)
            return Sample(stripper.pages, marks)
        }
    }

    private val expectedTitles = (1..4).map { "Chương $it: Thử nghiệm $it" }

    private fun checkChapters(book: ParsedBook) {
        assertEquals(expectedTitles, book.chapters.map { it.title })
        book.chapters.forEachIndexed { i, chapter ->
            val c = i + 1
            // Ten narration paragraphs plus a line of dialogue after every other one.
            assertEquals(chapter.paragraphs.joinToString("\n"), 15, chapter.paragraphs.size)
            assertTrue(chapter.paragraphs[0].startsWith("Diệp Tu bước vào quán net"))
            assertTrue(chapter.paragraphs[0].endsWith("Đoạn 1 của chương $c."))
            assertTrue(chapter.paragraphs[1].startsWith("“Anh định làm gì"))
            assertTrue(chapter.paragraphs.last().endsWith("Đoạn 10 của chương $c."))
            for (p in chapter.paragraphs) {
                assertFalse(p, p.contains("file:") || p.contains("pdf-src") || Regex("\\d+/\\d+").containsMatchIn(p))
                assertTrue(p, p.last() in ".”")
            }
        }
    }

    @Test
    fun edgePrintedPdfSplitsAtBookmarks() {
        val sample = sample()
        assertEquals(expectedTitles, sample.marks.map { it.title })
        checkChapters(PdfLayout.build("Thử", null, sample.pages, sample.marks))
    }

    @Test
    fun withoutBookmarksFallsBackToHeadings() {
        checkChapters(PdfLayout.build("Thử", null, sample().pages, emptyList()))
    }

    @Test
    fun chaptersStartingMidPageSplitAtTheirHeading() {
        val sample = sample("sample-flow.pdf")
        assertTrue(sample.pages.size > sample.marks.map { it.page }.distinct().size)
        checkChapters(PdfLayout.build("Thử", null, sample.pages, sample.marks))
        checkChapters(PdfLayout.build("Thử", null, sample.pages, emptyList()))
    }

    private fun line(text: String, left: Float = 72f, right: Float = 520f, y: Float) = PdfLine(text, left, right, y)

    @Test
    fun joinsHyphenationAndParagraphsAcrossPages() {
        val page1 = listOf(
            line("The Long Book", left = 250f, right = 350f, y = 30f),
            line("It was a dark and stormy night; the rain fell in", left = 90f, y = 100f),
            line("torrents, except at occasional intervals, when it was", y = 114f),
            line("checked by a violent gust of wind which swept up the", y = 128f),
            line("streets, rattling along the housetops, and fiercely agi-", y = 142f),
            line("tating the scanty flame of the lamps that struggled", y = 156f),
            line("against the darkness. Through one of the obscurest", y = 170f),
            line("12", left = 290f, right = 300f, y = 780f),
        )
        val page2 = listOf(
            line("The Long Book", left = 250f, right = 350f, y = 30f),
            line("quarters of London, among houses of a mean", y = 100f),
            line("appearance, a man was walking.", right = 300f, y = 114f),
            line("Nobody saw him go by the old church and the market", left = 90f, y = 128f),
            line("square, nor did anybody hear the bells of the tower.", y = 142f),
            line("13", left = 290f, right = 300f, y = 780f),
        )
        val page3 = listOf(
            line("The Long Book", left = 250f, right = 350f, y = 30f),
            line("“Who goes there?” asked the watchman at the gate of", left = 90f, y = 100f),
            line("the city, raising his lantern high above his head.", y = 114f),
            line("14", left = 290f, right = 300f, y = 780f),
        )
        val paragraphs = PdfLayout.paragraphs(PdfLayout.stripFurniture(listOf(page1, page2, page3))).map { it.text }
        assertEquals(3, paragraphs.size)
        assertTrue(paragraphs[0], paragraphs[0].startsWith("It was a dark"))
        assertTrue(paragraphs[0], paragraphs[0].contains("fiercely agitating the scanty"))
        assertTrue(paragraphs[0], paragraphs[0].contains("obscurest quarters of London"))
        assertTrue(paragraphs[0], paragraphs[0].endsWith("a man was walking."))
        assertTrue(paragraphs[1].startsWith("Nobody saw him"))
        assertTrue(paragraphs[2].startsWith("“Who goes there?”"))
    }

    @Test(expected = IOException::class)
    fun scannedPdfWithoutTextIsRejected() {
        PdfLayout.build("Ảnh", null, listOf(emptyList(), listOf(line("12", y = 780f))), emptyList())
    }
}
