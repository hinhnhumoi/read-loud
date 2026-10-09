package com.tung.readloud.parse

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URI

/** Finds a novel's table of contents from a chapter page and reads chapter links from TOC pages. */
object TocParser {
    data class Entry(val title: String, val url: String)

    data class Page(val entries: List<Entry>, val pageUrls: List<String>)

    private val tocLinkText = Regex(
        "(?iu)(mục lục|muc luc|danh sách chương|ds chương|table of contents|\\btoc\\b|all chapters|chapter list|chapters list)",
    )
    private val tocHref = Regex("(?i)(muc-luc|mucluc|danh-sach-chuong|table-of-contents|/toc/?$|/chapters/?$)")
    private val chapterText = Regex("(?iu)(chương|chuong|chapter|chap\\b|quyển|tập|hồi|\\bch\\.?\\s*\\d|第)")
    private val numberOnly = Regex("^\\s*\\d{1,4}\\s*$")
    private val pageSuffix = Regex("(?i)(/trang-\\d+|/page/\\d+|/\\d+)/?$")
    private const val MAX_LEAD_CHARS = 40
    private val skipHref = Regex("(?i)(\\?share=|/wp-content/|replytocom=|/wp-login|/feed/?$|#comment|/tag/|/category/|/author/)")

    /** The link on a chapter page that leads to the novel's chapter list, if the page has one. */
    fun findTocUrl(doc: Document, currentUrl: String, config: SiteConfig?): String? {
        val host = hostOf(currentUrl)
        val current = normalize(currentUrl)
        config?.tocSelector?.let { sel ->
            doc.select(sel).firstNotNullOfOrNull { a -> a.absUrl("href").takeIf { valid(it, host) && normalize(it) != current } }
                ?.let { return it }
        }
        return doc.select("a[href]").asSequence()
            .filter { a -> tocLinkText.containsMatchIn(a.text()) || tocHref.containsMatchIn(a.absUrl("href")) }
            .map { it.absUrl("href").substringBefore('#') }
            .firstOrNull { valid(it, host) && normalize(it) != current }
    }

    fun parse(html: String, url: String): Page {
        val doc = Jsoup.parse(html, url)
        val root = contentRoot(doc, SiteConfigs.forUrl(url))
        val host = hostOf(url)
        val base = tocBase(url)
        val entries = LinkedHashMap<String, Entry>()
        val pages = LinkedHashSet<String>()
        for (a in root.select("a[href]")) {
            val href = a.absUrl("href").substringBefore('#')
            if (!valid(href, host) || skipHref.containsMatchIn(href)) continue
            val text = labelOf(a)
            if (text.isEmpty()) continue
            val key = normalize(href)
            if (numberOnly.matches(text) && tocBase(href) == base) {
                if (key != normalize(url)) pages += href
                continue
            }
            if (key == normalize(url) || tocBase(href) == base) continue
            if (chapterText.containsMatchIn(text) || numberOnly.matches(text)) entries.putIfAbsent(key, Entry(text, href))
        }
        return Page(entries.values.toList(), pages.toList())
    }

    /**
     * The link's text, with the words just before it when the list reads "Chương 937: <a>Its name</a>", so
     * the entry keeps its number.
     */
    private fun labelOf(a: Element): String {
        val text = a.text().trim().ifEmpty { a.attr("title").trim() }
        if (chapterText.containsMatchIn(text)) return text
        // Only a short lead-in such as "Chương 937:", not a whole sentence that happens to mention a chapter.
        val before = (a.previousSibling() as? TextNode)?.text()?.trim()?.takeIf { it.length <= MAX_LEAD_CHARS }.orEmpty()
        return if (before.isNotEmpty() && chapterText.containsMatchIn(before)) "$before $text".trim() else text
    }

    private fun contentRoot(doc: Document, config: SiteConfig?): Element {
        // On a forum the list is the first post of its thread; the rest of the page is menus and comments.
        if (XenForo.isForum(doc)) XenForo.firstPost(doc, doc.location())?.let { return it }
        val selectors = listOfNotNull(config?.contentSelector) + SiteConfigs.genericContentSelectors
        return selectors.asSequence()
            .mapNotNull { sel -> runCatching { doc.select(sel).maxByOrNull { it.select("a[href]").size } }.getOrNull() }
            .firstOrNull { it.select("a[href]").size >= 5 }
            ?: doc.body()
    }

    /** The TOC URL with any page-number suffix removed, so page 1 and page 5 compare equal. */
    fun tocBase(url: String): String {
        val noQuery = url.substringBefore('#').replace(Regex("(?i)[?&]page=\\d+"), "")
        val plain = normalize(noQuery)
        // A forum thread's number names the thread, not a page of it; its pages are already folded in.
        if (forumThread.containsMatchIn(plain)) return plain
        return normalize(pageSuffix.replace(plain, ""))
    }

    /**
     * A key for comparing links to the same page. Forum threads are known by their number alone: a thread
     * is linked both as "threads/3/" and as "threads/some-title.3/", and its later pages are the same chapter.
     */
    fun normalize(url: String): String {
        val plain = url.substringBefore('#').trimEnd('/').removePrefix("https://").removePrefix("http://").removePrefix("www.")
        return forumThread.replace(plain) { "threads/" + it.groupValues[1] }
    }

    private val forumThread = Regex("threads/(?:[^/?#&]*\\.)?(\\d+)(?:/.*)?$")

    private fun valid(href: String, host: String?): Boolean =
        (href.startsWith("http://") || href.startsWith("https://")) && (host == null || hostOf(href) == host)

    private fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
}
