package com.tung.readloud.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SentencesTest {
    private val text = "Diệp Tu bước vào quán net. Hắn gọi một chai Coca!\nRồi ngồi xuống máy số mười."

    @Test
    fun startsListeningAtTheSentenceOfThePressedWord() {
        assertEquals(0, Sentences.startOf(text, text.indexOf("quán")))
        assertEquals(text.indexOf("Hắn"), Sentences.startOf(text, text.indexOf("Coca")))
        // A new line in the chunk ends a sentence too.
        assertEquals(text.indexOf("Rồi"), Sentences.startOf(text, text.indexOf("mười")))
    }

    @Test
    fun theFirstWordOfASentenceStartsThere() {
        assertEquals(text.indexOf("Hắn"), Sentences.startOf(text, text.indexOf("Hắn")))
    }
}
