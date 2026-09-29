package com.tung.readloud.data

import com.tung.readloud.book.BookUrl
import java.net.URI

/** Derives a stable identity and display name for a novel from one of its chapter pages. */
object SeriesKey {
    private val chapterMarker = Regex("(?iu)\\b(chương|chuong|chapter|chap|ch|c|q|quyển|tập)\\b\\.?\\s*\\d+.*$")
    private val edgeNoise = Regex("^[\\s\\-–—:|·.,\\[\\]()]+|[\\s\\-–—:|·.,\\[\\]()]+$")
    private val siteSeparators = Regex("\\s+[|–—-]\\s+")

    /** Series name from a chapter title such as "[TCCT] Chương 192" → "TCCT". */
    fun seriesName(title: String): String? {
        val stripped = chapterMarker.replace(title, "").replace(edgeNoise, "").trim()
        return stripped.takeIf { it.length >= 2 && it.any(Char::isLetterOrDigit) }
    }

    /** Series name from a document title, trying each separator-delimited part. */
    fun seriesNameFromPageTitle(pageTitle: String, host: String): String? =
        pageTitle.split(siteSeparators)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(host, true) && !host.contains(it, true) }
            .firstNotNullOfOrNull { seriesName(it) }

    /** A local book's page title is the book title itself. */
    fun displayName(url: String, title: String, pageTitle: String): String {
        if (BookUrl.isBook(url)) return pageTitle.ifBlank { title }
        val host = hostOf(url)
        return seriesName(title)
            ?: seriesNameFromPageTitle(pageTitle, host)
            ?: title.ifBlank { host }
    }

    fun of(url: String, title: String, pageTitle: String): String {
        BookUrl.bookId(url)?.let { return bookKey(it) }
        val host = hostOf(url)
        val name = seriesName(title) ?: seriesNameFromPageTitle(pageTitle, host) ?: pathKey(url)
        return host + "|" + name.lowercase()
    }

    fun bookKey(bookId: String) = "book|$bookId"

    fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: url

    private fun pathKey(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull() ?: return url
        return path.split('/')
            .filter { it.isNotEmpty() && !it.all(Char::isDigit) }
            .joinToString("/") { it.replace(Regex("\\d+"), "") }
    }
}
