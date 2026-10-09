package com.tung.readloud.parse

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Forums running XenForo, where each chapter is a thread: the first post holds the chapter and everything
 * after it is comments. The chapter is taken by the forum's own markup, the body of the first post, so the
 * poster's card, signature, likes and the comments stay out without guessing which lines are junk.
 */
object XenForo {
    private val laterPage = Regex("/page-(\\d+)")

    /** Inside a post: quoted posts, spoiler buttons and the "Click to expand" bar. */
    val removeSelectors = listOf(
        ".bbCodeBlock--quote", ".bbCodeBlock-expandLink", ".bbCodeBlock-title", ".bbCodeSpoiler-button",
        ".bbMediaWrapper", ".bbImageWrapper",
    )

    fun isForum(doc: Document): Boolean =
        doc.selectFirst("article.message--post .message-body .bbWrapper, li.message blockquote.messageText") != null

    /** The body of the thread's first post, or null on a later page of comments. */
    fun firstPost(doc: Document, url: String): Element? {
        val page = laterPage.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        if (page > 1) return null
        return doc.selectFirst("article.message--post .message-body .bbWrapper")
            ?: doc.selectFirst("li.message blockquote.messageText")
    }

    fun title(doc: Document): String? =
        doc.selectFirst("h1.p-title-value, .titleBar h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
}
