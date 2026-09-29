package com.tung.readloud.parse

import java.net.URI

/** Per-domain CSS selectors. Add an entry here when a site needs special handling. */
data class SiteConfig(
    val hostSuffix: String,
    val contentSelector: String,
    val titleSelector: String? = null,
    val nextSelector: String? = null,
    val prevSelector: String? = null,
    val removeSelectors: List<String> = emptyList(),
    /** Link on a chapter page to the novel's table of contents. */
    val tocSelector: String? = null,
)

object SiteConfigs {
    private val configs = listOf(
        SiteConfig(
            hostSuffix = "wordpress.com",
            contentSelector = "div.entry-content, div.post-content",
            titleSelector = "h1.entry-title, h2.entry-title, h1.post-title",
            nextSelector = "a[rel=next]",
            prevSelector = "a[rel=prev]",
            removeSelectors = listOf(
                ".sharedaddy", ".jp-relatedposts", "#jp-post-flair", ".wpcnt", ".wpa",
                ".post-navigation", ".nav-links", ".entry-meta", ".entry-footer", "nav",
            ),
        ),
    )

    /** Selectors tried, in order, on sites without a dedicated config. */
    val genericContentSelectors = listOf(
        "#chapter-content", ".chapter-content", "#chapter-c", ".chapter-c",
        "#chapter_content", ".reading-content", ".box-chap", "#content-chapter",
        "div.entry-content", "article .content", "article",
    )

    /** Junk removed from any content block before text extraction. */
    val commonRemoveSelectors = listOf(
        "script", "style", "noscript", "iframe", "ins", "form", "button", "input",
        "select", "textarea", "svg", "figure", "figcaption", "video", "audio",
        "[class*=ads]", "[id*=ads]", "[class*=advert]", "[class*=share]", "[class*=comment]",
    )

    fun forUrl(url: String): SiteConfig? {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return null
        return configs.firstOrNull { host == it.hostSuffix || host.endsWith("." + it.hostSuffix) }
    }
}
