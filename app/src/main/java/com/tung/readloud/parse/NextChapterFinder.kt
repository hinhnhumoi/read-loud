package com.tung.readloud.parse

import org.jsoup.nodes.Element
import java.net.URI

/** Finds the URL of the next or previous chapter: site selector, rel=next/prev, link text, then URL numbering. */
object NextChapterFinder {
    private val nextText = Regex(
        "(?i)(ch[ưu][ơo]ng\\s*(ti[eế]p|sau|k[eế])|chap(ter)?\\s*(ti[eế]p|sau)|trang\\s*sau|" +
            "ti[eế]p\\s*theo|^\\s*(next|ti[eế]p)\\s*$|next\\s*(chapter|chap|page)|»|›|>>|→)",
    )
    private val prevText = Regex("(?i)(tr[ưu][ơo]c|prev|previous|«|‹|<<|←)")
    private val prevChapterText = Regex(
        "(?i)(ch[ưu][ơo]ng\\s*tr[ưu][ơo]c|chap(ter)?\\s*tr[ưu][ơo]c|trang\\s*tr[ưu][ơo]c|" +
            "^\\s*(prev|previous|tr[ưu][ơo]c)\\s*$|prev(ious)?\\s*(chapter|chap|page)|«|‹|<<|←)",
    )
    private val chapterNumber = Regex("(?i)(ch[ưu][ơo]ng|chapter|chap|ch|c)[-_/=]?(\\d+)")
    private val trailingNumber = Regex("(\\d+)(?=[^\\d]*$)")

    /** [guess] falls back to the URL's own number, which means nothing for thread ids on a forum. */
    fun find(doc: Element, currentUrl: String, config: SiteConfig?, guess: Boolean = true): String? {
        val current = normalize(currentUrl)
        val host = hostOf(currentUrl)

        config?.nextSelector?.let { sel ->
            doc.select(sel).firstNotNullOfOrNull { valid(it.absUrl("href"), current, host) }?.let { return it }
        }
        doc.select("a[rel~=(?i)^next$], link[rel~=(?i)^next$]")
            .firstNotNullOfOrNull { valid(it.absUrl("href"), current, host) }
            ?.let { return it }
        doc.select("a[href]").asSequence()
            .filter { a ->
                val label = a.text().ifBlank { a.attr("title") }.ifBlank { a.attr("aria-label") }
                nextText.containsMatchIn(label) && !prevText.containsMatchIn(label)
            }
            .mapNotNull { valid(it.absUrl("href"), current, host) }
            .firstOrNull()
            ?.let { return it }
        return if (guess) guessFromUrl(currentUrl) else null
    }

    fun findPrevious(doc: Element, currentUrl: String, config: SiteConfig?): String? {
        val current = normalize(currentUrl)
        val host = hostOf(currentUrl)

        config?.prevSelector?.let { sel ->
            doc.select(sel).firstNotNullOfOrNull { valid(it.absUrl("href"), current, host) }?.let { return it }
        }
        doc.select("a[rel~=(?i)^prev$], link[rel~=(?i)^prev$]")
            .firstNotNullOfOrNull { valid(it.absUrl("href"), current, host) }
            ?.let { return it }
        return doc.select("a[href]").asSequence()
            .filter { a ->
                val label = a.text().ifBlank { a.attr("title") }.ifBlank { a.attr("aria-label") }
                prevChapterText.containsMatchIn(label) && !nextText.containsMatchIn(label)
            }
            .mapNotNull { valid(it.absUrl("href"), current, host) }
            .firstOrNull()
    }

    /** The same URL with its chapter number moved by [delta]; null when there is no number or it would go below 0. */
    fun guessFromUrl(url: String, delta: Int = 1): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val path = uri.rawPath ?: return null
        val match = chapterNumber.findAll(path).lastOrNull()
        val (range, digits) = when {
            match != null -> match.groups[2]!!.range to match.groupValues[2]
            else -> {
                val m = trailingNumber.find(path) ?: return null
                m.range to m.value
            }
        }
        val next = (digits.toLongOrNull() ?: return null) + delta
        if (next < 0) return null
        val replaced = path.replaceRange(range, next.toString().padStart(digits.length, '0'))
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        return "${uri.scheme}://${uri.rawAuthority}$replaced$query"
    }

    private fun valid(href: String, current: String, host: String?): String? {
        if (href.isBlank()) return null
        if (!href.startsWith("http://") && !href.startsWith("https://")) return null
        if (host != null && hostOf(href) != host) return null
        if (normalize(href) == current) return null
        return href
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    private fun normalize(url: String): String = TocParser.normalize(url)
}
