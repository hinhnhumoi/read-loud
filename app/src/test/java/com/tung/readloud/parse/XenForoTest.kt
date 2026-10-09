package com.tung.readloud.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A chapter posted as a forum thread: only the first post is read, and the chapter list leads on. */
class XenForoTest {
    private val url = "https://forum.example.com/index.php?threads/3/"

    private val thread = """
        <html><head><title>Truyện Thử - Chương 7 | Diễn đàn</title></head><body>
        <a href="https://forum.example.com/index.php?threads/muc-luc-truyen-thu.94/">MỤC LỤC</a>
        <h1 class="p-title-value">Truyện Thử - Chương 7</h1>
        <nav class="pageNav"><a href="/index.php?threads/3/">1</a><a href="/index.php?threads/3/page-2">2</a>
          <a class="pageNav-jump--next" rel="next" href="/index.php?threads/3/page-2">Trang kế</a></nav>
        <article class="message message--post" data-author="Người Dịch">
          <div class="message-userDetails"><h4>Người Dịch</h4><h5>Sinh như Hạ Hoa, tử như Thu Diệp.</h5></div>
          <dl><dt>Số lượt thích</dt><dd>58,556</dd></dl>
          <div class="message-userContent"><article class="message-body js-selectToQuote">
            <div class="bbWrapper"><b>Chương 7: Ngày mưa</b><br /><br />
              Trời mưa từ sáng.<br /><br />
              Hắn nói: “Edit cái này đi.” rồi bỏ đi.<br /><br />
              <blockquote class="bbCodeBlock bbCodeBlock--quote"><div class="bbCodeBlock-title">Ai đó said:</div>
                <div class="bbCodeBlock-content">Câu trích dẫn.</div><div class="bbCodeBlock-expandLink">Click to expand...</div></blockquote>
              Cuối chương.<br /><br />
              Edit &amp; beta: Người Dịch
            </div>
          </article></div>
          <div class="message-lastEdit">Last edited by a moderator: 2/6/18</div>
          <aside class="message-signature"><div class="bbWrapper">Chữ ký của người dịch.</div></aside>
          <div class="reactionsBar">Thích, Vui và 28 người khác</div>
        </article>
        <article class="message message--post" data-author="Độc Giả">
          <div class="message-userContent"><article class="message-body js-selectToQuote">
            <div class="bbWrapper">Cảm ơn chủ nhà, chương sau đâu rồi?</div>
          </article></div>
        </article>
        </body></html>
    """.trimIndent()

    @Test
    fun readsOnlyTheFirstPost() {
        val chapter = ChapterParser().parse(url, thread)
        val text = TextNormalizer.clean(chapter.paragraphs)

        assertEquals("Truyện Thử - Chương 7", chapter.title)
        assertEquals(listOf("Chương 7: Ngày mưa", "Trời mưa từ sáng.", "Hắn nói: “Edit cái này đi.” rồi bỏ đi.", "Cuối chương."), text)
    }

    @Test
    fun pageLinksAreNotTheNextChapter() {
        val chapter = ChapterParser().parse(url, thread)

        assertNull(chapter.nextUrl)
        assertNull(chapter.prevUrl)
        assertEquals("https://forum.example.com/index.php?threads/muc-luc-truyen-thu.94/", chapter.tocUrl)
    }

    @Test
    fun aLaterPageOfCommentsHasNoChapter() {
        val chapter = ChapterParser().parse("https://forum.example.com/index.php?threads/3/page-2", thread)

        assertTrue(chapter.paragraphs.isEmpty())
    }

    @Test
    fun aThreadIsTheSameUnderAnyOfItsLinks() {
        val plain = TocParser.normalize("https://forum.example.com/index.php?threads/3/")

        assertEquals(plain, TocParser.normalize("http://www.forum.example.com/index.php?threads/truy%E1%BB%87n-th%E1%BB%AD-ch%C6%B0%C6%A1ng-7.3/"))
        assertEquals(plain, TocParser.normalize("https://forum.example.com/index.php?threads/3/page-2"))
        assertTrue(plain != TocParser.normalize("https://forum.example.com/index.php?threads/truyen-thu-chuong-8.4/"))
    }

    @Test
    fun readsAChapterListWhereNumbersStandBeforeTheLinks() {
        val toc = """
            <html><body><h1 class="p-title-value">Mục lục</h1>
            <article class="message message--post"><div class="message-userContent"><article class="message-body">
              <div class="bbWrapper">Chương 1 – 6: Xem ở <a href="https://other.example.com/">blog cũ</a><br />
                Chương 7: <a href="https://forum.example.com/index.php?threads/truyen-thu-chuong-7.3/">Ngày mưa</a><br />
                Chương 8: <a href="https://forum.example.com/index.php?threads/truyen-thu-chuong-8.4/">Ngày nắng</a><br />
                Chương 9: <a href="https://forum.example.com/index.php?threads/truyen-thu-chuong-9.12/">Gió lên</a><br />
                Chương 10: <a href="https://forum.example.com/index.php?threads/truyen-thu-chuong-10.15/">Mây tan</a><br />
                Chương 11: <a href="https://forum.example.com/index.php?threads/truyen-thu-chuong-11.18/">Trời quang</a>
              </div></article></div></article>
            <article class="message message--post"><div class="bbWrapper"><a href="https://forum.example.com/index.php?threads/99/">Chương nào hay nhất?</a></div></article>
            </body></html>
        """.trimIndent()
        val page = TocParser.parse(toc, "https://forum.example.com/index.php?threads/muc-luc-truyen-thu.94/")

        assertEquals(listOf("Chương 7: Ngày mưa", "Chương 8: Ngày nắng", "Chương 9: Gió lên", "Chương 10: Mây tan", "Chương 11: Trời quang"), page.entries.map { it.title })
        assertEquals(0, page.entries.indexOfFirst { TocParser.normalize(it.url) == TocParser.normalize(url) })
    }

    @Test
    fun dropsCreditLinesButKeepsStoryMentioningThem() {
        val lines = listOf(
            "Edit & beta: Lá Mùa Thu",
            "Dịch, biên tập: Ai Đó",
            "Edit và beta là hai người bạn thân của hắn từ thuở nhỏ, nay đã đi xa.",
        )

        assertEquals(listOf(lines[2]), TextNormalizer.clean(lines))
    }
}
