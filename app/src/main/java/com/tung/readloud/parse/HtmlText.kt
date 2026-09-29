package com.tung.readloud.parse

import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** Turns a content element into clean paragraphs, keeping paragraph breaks. */
object HtmlText {
    private val whitespace = Regex("[\\s\\u00a0\\u200b]+")
    private val junkLine = Regex(
        "(?i)^(share this|like this|loading\\.{3}|advertisements?|quảng cáo|đăng bởi|posted by|" +
            "chia sẻ|bình luận|comments?|tags?:|related|bài viết liên quan|[-=_*~.]{3,})\\s*:?\\s*$",
    )

    /** [webJunk] also strips ad, share and comment blocks, which only web pages have. */
    fun paragraphs(content: Element, removeSelectors: List<String>, webJunk: Boolean = true): List<String> {
        val el = content.clone()
        val common = if (webJunk) SiteConfigs.commonRemoveSelectors else listOf("script", "style", "noscript")
        (common + removeSelectors).forEach { sel ->
            runCatching { el.select(sel).remove() }
        }
        el.select("br").forEach { it.after(TextNode("\n")) }
        el.select("p, div, h1, h2, h3, h4, h5, h6, li, blockquote, tr, pre, section, article")
            .forEach {
                it.prependChild(TextNode("\n"))
                it.appendChild(TextNode("\n"))
            }
        return el.wholeText()
            .split('\n')
            .map { whitespace.replace(it, " ").trim() }
            .filter { it.isNotEmpty() && !junkLine.matches(it) }
    }
}
