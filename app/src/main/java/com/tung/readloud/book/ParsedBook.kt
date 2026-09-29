package com.tung.readloud.book

data class BookChapter(val title: String, val paragraphs: List<String>)

data class ParsedBook(val title: String, val author: String?, val chapters: List<BookChapter>)

/**
 * Chapters of local books get addresses like `book://<id>/<index>`, so the rest of the app can treat
 * them exactly like web chapters: saved progress, next chapter and the TOC all keep working.
 */
object BookUrl {
    private const val PREFIX = "book://"
    private const val TOC = "toc"

    fun isBook(url: String?): Boolean = url?.startsWith(PREFIX) == true

    fun chapter(bookId: String, index: Int) = "$PREFIX$bookId/$index"

    fun toc(bookId: String) = "$PREFIX$bookId/$TOC"

    fun bookId(url: String): String? = url.takeIf(::isBook)?.removePrefix(PREFIX)?.substringBefore('/')?.takeIf { it.isNotEmpty() }

    fun index(url: String): Int? = url.takeIf(::isBook)?.removePrefix(PREFIX)?.substringAfter('/', "")?.toIntOrNull()
}
