package com.tung.readloud.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterParserTest {
    private val url = "https://alldiepall.wordpress.com/2015/05/01/tcct-chuong-192/"
    private val html = javaClass.getResource("/tcct192.html")!!.readText()

    @Test
    fun parsesWordpressChapter() {
        val chapter = ChapterParser().parse(url, html)

        assertEquals("[TCCT] Chương 192", chapter.title)
        assertEquals("https://alldiepall.wordpress.com/2015/05/02/tcct-chuong-193/", chapter.nextUrl)
        assertTrue("expected many paragraphs, got ${chapter.paragraphs.size}", chapter.paragraphs.size > 30)
        val text = chapter.paragraphs.joinToString("\n")
        assertTrue("content too short: ${text.length}", text.length > 8000)
        assertTrue(chapter.paragraphs.none { it.contains("Share this", ignoreCase = true) })
    }

    @Test
    fun chunksStayUnderLimit() {
        val chapter = ChapterParser().parse(url, html)
        val chunks = TextChunker.chunk(chapter.paragraphs, 1000)

        assertTrue(chunks.all { it.length <= 1000 })
        assertEquals(chapter.paragraphs.joinToString("").replace("\\s".toRegex(), ""), chunks.joinToString("").replace("\\s".toRegex(), ""))
    }

    @Test
    fun guessesNextChapterFromUrl() {
        assertEquals("https://example.com/truyen/abc/chuong-124", NextChapterFinder.guessFromUrl("https://example.com/truyen/abc/chuong-123"))
        assertEquals("https://example.com/read/ch-010.html", NextChapterFinder.guessFromUrl("https://example.com/read/ch-009.html"))
        assertEquals("https://example.com/chapter/8", NextChapterFinder.guessFromUrl("https://example.com/chapter/7"))
        assertNull(NextChapterFinder.guessFromUrl("https://example.com/about"))
    }

    @Test
    fun findsPreviousChapter() {
        val chapter = ChapterParser().parse(url, html)
        assertEquals("https://alldiepall.wordpress.com/2015/04/30/tcct-chuong-191/", chapter.prevUrl)

        val page = org.jsoup.Jsoup.parse(
            """<a href="/truyen/abc/chuong-11">Chương sau</a><a href="/truyen/abc/chuong-9">« Chương trước</a>""",
            "https://example.com/truyen/abc/chuong-10",
        )
        assertEquals("https://example.com/truyen/abc/chuong-9", NextChapterFinder.findPrevious(page, "https://example.com/truyen/abc/chuong-10", null))
        assertEquals("https://example.com/read/ch-008.html", NextChapterFinder.guessFromUrl("https://example.com/read/ch-009.html", -1))
        assertNull(NextChapterFinder.guessFromUrl("https://example.com/chapter/0", -1))
    }
}
