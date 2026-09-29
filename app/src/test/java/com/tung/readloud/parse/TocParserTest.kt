package com.tung.readloud.parse

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TocParserTest {
    private val tocUrl = "https://alldiepall.wordpress.com/muc-luc-toan-chuc-cao-thu/"

    @Test
    fun findsTocLinkOnChapterPage() {
        val url = "https://alldiepall.wordpress.com/2015/05/01/tcct-chuong-192/"
        val doc = Jsoup.parse(javaClass.getResource("/tcct192.html")!!.readText(), url)
        assertEquals(tocUrl, TocParser.findTocUrl(doc, url, SiteConfigs.forUrl(url)))
    }

    @Test
    fun readsChaptersAndPagesFromTocPage() {
        val page = TocParser.parse(javaClass.getResource("/toc.html")!!.readText(), tocUrl)

        assertTrue("too few chapters: ${page.entries.size}", page.entries.size >= 80)
        assertEquals("Chương 1 – Chương 2", page.entries.first().title)
        assertEquals("https://alldiepall.wordpress.com/2014/10/27/tcct-chuong-1-chuong-2/", page.entries.first().url)
        assertEquals("Chương 100", page.entries.last().title)
        assertTrue(page.entries.none { it.title.contains("Thuật ngữ") || it.url.contains("share=") })
        assertEquals((2..6).map { "$tocUrl$it/" }, page.pageUrls)
    }

    @Test
    fun tocBaseIgnoresPageNumbers() {
        assertEquals(TocParser.tocBase(tocUrl), TocParser.tocBase(tocUrl + "3/"))
        assertEquals(TocParser.tocBase("https://x.com/truyen/abc/"), TocParser.tocBase("https://x.com/truyen/abc/trang-4/"))
        assertEquals(TocParser.tocBase("https://x.com/truyen/abc"), TocParser.tocBase("https://x.com/truyen/abc?page=2"))
    }
}
