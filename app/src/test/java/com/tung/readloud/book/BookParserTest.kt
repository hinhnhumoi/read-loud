package com.tung.readloud.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookParserTest {

    private fun zip(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
        <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    private fun xhtml(body: String) =
        """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title></head><body>$body</body></html>"""

    @Test
    fun epub3SplitsAnchoredChaptersAndSkipsCover() {
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Toàn Chức Cao Thủ</dc:title><dc:creator>Hồ Điệp Lam</dc:creator></metadata>
            <manifest>
              <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
              <item id="cover" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
              <item id="c1" href="Text/chuong%201.xhtml" media-type="application/xhtml+xml"/>
              <item id="c23" href="Text/c23.xhtml" media-type="application/xhtml+xml"/>
            </manifest>
            <spine><itemref idref="cover"/><itemref idref="c1"/><itemref idref="c23"/></spine></package>"""
        val nav = xhtml(
            """<nav epub:type="toc" xmlns:epub="http://www.idpf.org/2007/ops"><ol>
              <li><a href="Text/chuong%201.xhtml">Chương 1: Mở màn</a></li>
              <li><a href="Text/c23.xhtml#c2">Chương 2</a></li>
              <li><a href="Text/c23.xhtml#c3">Chương 3</a></li></ol></nav>""",
        )
        val bytes = zip(
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf,
                "OEBPS/nav.xhtml" to nav,
                "OEBPS/Text/cover.xhtml" to xhtml("<img src=\"cover.jpg\"/>"),
                "OEBPS/Text/chuong 1.xhtml" to xhtml("<h1>Chương 1: Mở màn</h1><p>Diệp Tu bước vào quán net.</p><p>Hắn gọi một chai Coca.</p>"),
                "OEBPS/Text/c23.xhtml" to xhtml("<h2 id=\"c2\">Chương 2</h2><p>Đoạn hai.</p><h2 id=\"c3\">Chương 3</h2><p>Đoạn ba.</p>"),
            ),
        )
        val book = EpubParser.parse(ByteArrayInputStream(bytes), "fallback")

        assertEquals("Toàn Chức Cao Thủ", book.title)
        assertEquals("Hồ Điệp Lam", book.author)
        assertEquals(listOf("Chương 1: Mở màn", "Chương 2", "Chương 3"), book.chapters.map { it.title })
        assertEquals(listOf("Diệp Tu bước vào quán net.", "Hắn gọi một chai Coca."), book.chapters[0].paragraphs)
        assertEquals(listOf("Đoạn hai."), book.chapters[1].paragraphs)
        assertEquals(listOf("Đoạn ba."), book.chapters[2].paragraphs)
    }

    @Test
    fun epub2UsesNcxTitles() {
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Sách cũ</dc:title></metadata>
            <manifest>
              <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
              <item id="a" href="a.html" media-type="application/xhtml+xml"/>
              <item id="b" href="b.html" media-type="application/xhtml+xml"/>
            </manifest>
            <spine toc="ncx"><itemref idref="a"/><itemref idref="b"/></spine></package>"""
        val ncx = """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
            <navPoint id="p1"><navLabel><text>Hồi thứ nhất</text></navLabel><content src="a.html"/></navPoint>
            <navPoint id="p2"><navLabel><text>Hồi thứ hai</text></navLabel><content src="b.html"/></navPoint>
            </navMap></ncx>"""
        val bytes = zip(
            mapOf(
                "META-INF/container.xml" to container.replace("OEBPS/content.opf", "content.opf"),
                "content.opf" to opf,
                "toc.ncx" to ncx,
                "a.html" to xhtml("<p>Mở đầu câu chuyện.</p>"),
                "b.html" to xhtml("<p>Tiếp theo.</p>"),
            ),
        )
        val book = EpubParser.parse(ByteArrayInputStream(bytes), "fallback")
        assertEquals(listOf("Hồi thứ nhất", "Hồi thứ hai"), book.chapters.map { it.title })
    }

    @Test
    fun txtSplitsAtHeadingsAndFoldsVolumes() {
        val text = "﻿Toàn Chức Cao Thủ\nTác giả: Hồ Điệp Lam\n\nQuyển 1\n\nChương 1: Diệp Thu bị đuổi\nĐoạn một.\n\nĐoạn hai.\nChương 2 - Quán net\nĐoạn ba.\n"
        val book = TxtParser.parse(text.toByteArray(Charsets.UTF_8), "tcct")
        assertEquals(listOf("Quyển 1 · Chương 1: Diệp Thu bị đuổi", "Chương 2 - Quán net"), book.chapters.map { it.title })
        assertEquals(listOf("Đoạn một.", "Đoạn hai."), book.chapters[0].paragraphs)
    }

    @Test
    fun txtWithoutHeadingsIsCutIntoParts() {
        val text = (1..400).joinToString("\n") { "Câu thứ $it của một truyện không có tiêu đề chương nào cả." }
        val book = TxtParser.parse(text.toByteArray(Charsets.UTF_8), "x")
        assertTrue(book.chapters.size >= 2)
        assertEquals("Phần 1", book.chapters.first().title)
    }

    @Test
    fun txtDecodesUtf16WithBom() {
        val text = "Chương 1\nMột\nChương 2\nHai"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(listOf("Chương 1", "Chương 2"), TxtParser.parse(bytes, "x").chapters.map { it.title })
    }

    @Test
    fun bookUrlsRoundTrip() {
        val url = BookUrl.chapter("abc123", 42)
        assertTrue(BookUrl.isBook(url))
        assertEquals("abc123", BookUrl.bookId(url))
        assertEquals(42, BookUrl.index(url))
        assertEquals(null, BookUrl.index(BookUrl.toc("abc123")))
    }
}
