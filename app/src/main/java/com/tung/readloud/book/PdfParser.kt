package com.tung.readloud.book

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.IOException
import java.io.Writer

/** Reads the text layer of a PDF with PdfBox-Android; scanned pages without text are not supported. */
object PdfParser {
    private const val MAX_MARKS = 5000
    private const val MAX_MARK_DEPTH = 5

    fun parse(context: Context, file: File, fallbackTitle: String): ParsedBook {
        PDFBoxResourceLoader.init(context.applicationContext)
        val document = try {
            PDDocument.load(file)
        } catch (e: InvalidPasswordException) {
            throw IOException("PDF có mật khẩu, app chưa mở được loại này")
        }
        return document.use { doc ->
            val stripper = LineStripper()
            stripper.sortByPosition = true
            stripper.writeText(doc, NullWriter)
            val info = doc.documentInformation
            PdfLayout.build(
                title = info?.title?.trim()?.takeIf(::usableTitle) ?: fallbackTitle,
                author = info?.author?.trim()?.takeIf { it.isNotEmpty() },
                pages = stripper.pages,
                marks = marks(doc),
            )
        }
    }

    /** Word processors often leave titles like "Microsoft Word - truyen.docx" or "Untitled". */
    private fun usableTitle(title: String): Boolean {
        val lower = title.lowercase()
        return title.isNotEmpty() && listOf("microsoft word", ".doc", ".pdf", "untitled", "document").none { it in lower }
    }

    /** Bookmarks in reading order, children after their parent. */
    private fun marks(doc: PDDocument): List<PdfMark> {
        val outline = runCatching { doc.documentCatalog.documentOutline }.getOrNull() ?: return emptyList()
        val out = mutableListOf<PdfMark>()
        fun walk(node: PDOutlineNode, depth: Int) {
            for (item in node.children()) {
                if (out.size >= MAX_MARKS) return
                val page = runCatching { item.findDestinationPage(doc) }.getOrNull()
                val index = page?.let { doc.pages.indexOf(it) } ?: -1
                val title = item.title?.trim().orEmpty()
                if (index >= 0 && title.isNotEmpty()) out += PdfMark(title, index)
                if (depth < MAX_MARK_DEPTH) walk(item, depth + 1)
            }
        }
        runCatching { walk(outline, 0) }
        return out
    }

    /** Collects each page's lines with their position instead of writing plain text. */
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

    private object NullWriter : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) {}
        override fun flush() {}
        override fun close() {}
    }
}
