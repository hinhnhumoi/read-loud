package com.tung.readloud.parse

import com.tung.readloud.data.ReplaceRule
import com.tung.readloud.data.ReplaceRules
import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {
    @Test
    fun dropsJunkLinesOnly() {
        val input = listOf(
            "Chương 192: Suy ngẫm tỉ lệ rơi đạo cụ",
            "Edit: Na | Beta: Kha",
            "Ảnh Đao Khách thoắt cái hợp thể, mọi người ập lên điên cuồng tấn công.",
            "***",
            "Cầu vote, cầu đề cử các bạn ơi!",
            "Chương sau",
            "Hắn nói: \"Edit: cái này không phải credit.\" rồi bỏ đi, để lại cả đám người đứng nhìn nhau chẳng hiểu chuyện gì vừa xảy ra ở đây cả.",
        )
        assertEquals(listOf(input[0], input[2], input[6]), TextNormalizer.clean(input))
    }

    @Test
    fun rewritesAwkwardText() {
        assertEquals("Hắn lên cấp 25 rồi…", TextNormalizer.forSpeech("Hắn lên Lv.25 rồi......"))
        assertEquals("Chương 4 : Quyển 12", TextNormalizer.forSpeech("【Chương IV】: Quyển XII"))
        assertEquals("Sao lại thế!", TextNormalizer.forSpeech("Sao lại thế!!!"))
        assertEquals("Tô Mộc Tranh và Diệp Tu", TextNormalizer.forSpeech("Tô Mộc Tranh & Diệp Tu"))
        assertEquals("Hắn nói mi đi", TextNormalizer.forSpeech("Hắn nói mi đi"))
    }

    @Test
    fun appliesUserRules() {
        val rules = ReplaceRules.compile(
            listOf(
                ReplaceRule(pattern = "HP", replacement = "máu"),
                ReplaceRule(pattern = "Diệp Tu", replacement = "Diệp Tu", enabled = false),
                ReplaceRule(pattern = "(\\d+)k", replacement = "$1 nghìn", isRegex = true),
                ReplaceRule(pattern = "[bad", replacement = "x", isRegex = true),
            ),
        )
        assertEquals("Còn 30 máu, rơi 5 nghìn vàng. HPX giữ nguyên", ReplaceRules.apply(rules, "Còn 30 hp, rơi 5k vàng. HPX giữ nguyên"))
    }
}
