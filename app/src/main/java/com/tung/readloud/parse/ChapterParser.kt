package com.tung.readloud.parse

import com.tung.readloud.model.Chapter
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Extracts title, body paragraphs and the next and previous chapter URLs from a chapter page. */
class ChapterParser {

    fun parse(url: String, html: String): Chapter {
        val doc = Jsoup.parse(html, url)
        val config = SiteConfigs.forUrl(url)
        val nextUrl = NextChapterFinder.find(doc, url, config)

        val bySelector = selectContent(doc, config)
        val (content, readabilityTitle) = when {
            bySelector != null -> bySelector to null
            else -> readability(url, html)
        }
        val title = selectTitle(doc, config) ?: readabilityTitle ?: fallbackTitle(doc)
        val paragraphs = content?.let { HtmlText.paragraphs(it, config?.removeSelectors.orEmpty()) }.orEmpty()
            .filterNot { it == title }
        return Chapter(
            url, title, paragraphs, nextUrl, doc.title(), TocParser.findTocUrl(doc, url, config),
            prevUrl = NextChapterFinder.findPrevious(doc, url, config),
        )
    }

    private fun selectContent(doc: Document, config: SiteConfig?): Element? {
        val selectors = listOfNotNull(config?.contentSelector) + SiteConfigs.genericContentSelectors
        return selectors.asSequence()
            .mapNotNull { sel -> runCatching { doc.select(sel).maxByOrNull { it.text().length } }.getOrNull() }
            .firstOrNull { it.text().length >= MIN_CONTENT_CHARS }
    }

    private fun readability(url: String, html: String): Pair<Element?, String?> {
        val article = runCatching { Readability4J(url, html).parse() }.getOrNull() ?: return null to null
        val element = article.articleContent
            ?: article.content?.let { Jsoup.parseBodyFragment(it, url).body() }
        return element to article.title?.takeIf { it.isNotBlank() }
    }

    private fun selectTitle(doc: Document, config: SiteConfig?): String? {
        val selectors = listOfNotNull(config?.titleSelector) + listOf("h1.chapter-title", ".chapter-title", "h1", "h2.title")
        return selectors.asSequence()
            .mapNotNull { sel -> runCatching { doc.selectFirst(sel)?.text() }.getOrNull() }
            .firstOrNull { it.isNotBlank() }
            ?.trim()
    }

    private fun fallbackTitle(doc: Document): String {
        val raw = doc.title().ifBlank { return "Chương" }
        return raw.split(" | ", " – ", " - ", " — ").first().trim().ifBlank { raw }
    }

    private companion object {
        const val MIN_CONTENT_CHARS = 200
    }
}
