package com.tung.readloud.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NewChaptersTest {
    private fun novel(seen: Int?) = Novel(
        seriesKey = "k", name = "n", host = "h", currentUrl = "u", currentTitle = "t",
        chunkIndex = 0, chunkCount = 0, lastReadAt = 0, createdAt = 0, seenTocCount = seen,
    )

    @Test
    fun countsChaptersPastTheSeenListAndThePlaceReached() {
        // The list had 720 chapters when seen; 5 were added; the listener is on chapter 700.
        assertEquals(5, newChapters(novel(720), tocSize = 725, currentIndex = 699))
        // Listening into the new ones counts them down.
        assertEquals(3, newChapters(novel(720), tocSize = 725, currentIndex = 721))
        assertEquals(0, newChapters(novel(720), tocSize = 725, currentIndex = 724))
    }

    @Test
    fun nothingIsNewBeforeAListWasEverSaved() {
        assertEquals(0, newChapters(novel(null), tocSize = 725, currentIndex = 10))
        assertEquals(0, newChapters(novel(725), tocSize = 725, currentIndex = null))
    }
}
