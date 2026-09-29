package com.tung.readloud.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesKeyTest {
    @Test
    fun stripsChapterMarkerFromTitle() {
        assertEquals("TCCT", SeriesKey.seriesName("[TCCT] Chương 192"))
        assertEquals("Toàn Chức Cao Thủ", SeriesKey.seriesName("Toàn Chức Cao Thủ - Chương 12: Bắt đầu"))
        assertEquals("Lord of Mysteries", SeriesKey.seriesName("Lord of Mysteries Chapter 7"))
        assertNull(SeriesKey.seriesName("Chương 192: Suy ngẫm tỉ lệ rơi đạo cụ"))
    }

    @Test
    fun sameSeriesAcrossChapters() {
        val a = SeriesKey.of("https://alldiepall.wordpress.com/2015/05/01/tcct-chuong-192/", "[TCCT] Chương 192", "")
        val b = SeriesKey.of("https://alldiepall.wordpress.com/2015/05/02/tcct-chuong-193/", "[TCCT] Chương 193", "")
        assertEquals(a, b)
        assertEquals("alldiepall.wordpress.com|tcct", a)
    }

    @Test
    fun fallsBackToPageTitleThenPath() {
        val key = SeriesKey.of(
            "https://example.com/truyen/tien-nghich/chuong-5",
            "Chương 5: Khởi đầu",
            "Chương 5: Khởi đầu | Tiên Nghịch | Example",
        )
        assertEquals("example.com|tiên nghịch", key)
        assertEquals("Tiên Nghịch", SeriesKey.displayName("https://example.com/truyen/tien-nghich/chuong-5", "Chương 5: Khởi đầu", "Chương 5 | Tiên Nghịch | Example"))

        val pathOnly = SeriesKey.of("https://example.com/truyen/tien-nghich/chuong-5", "Chương 5", "")
        assertEquals("example.com|truyen/tien-nghich/chuong-", pathOnly)
    }
}
