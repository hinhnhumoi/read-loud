package com.tung.readloud.tts.speech

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Hits the real Edge endpoint; runs only with EDGE_TEST=1 in the environment. */
class EdgeSynthesizerTest {
    @Test
    fun synthesizesVietnamese() {
        assumeTrue(System.getenv("EDGE_TEST") == "1")
        val bytes = runBlocking {
            EdgeSynthesizer(Http.client).synthesize(
                "Xin chào. Diệp Tu bước vào quán net, gọi một chai Coca rồi ngồi xuống máy số mười.",
                OnlineVoices.DEFAULT,
            )
        }
        File("build/edge-test.mp3").writeBytes(bytes)
        assertTrue("audio too small: ${bytes.size}", bytes.size > 10_000)
    }
}
