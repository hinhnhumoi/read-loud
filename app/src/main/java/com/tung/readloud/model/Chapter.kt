package com.tung.readloud.model

data class Chapter(
    val url: String,
    val title: String,
    val paragraphs: List<String>,
    val nextUrl: String?,
    /** Raw document title, usually "chapter | series | site". */
    val pageTitle: String = "",
    /** Link to the novel's table of contents, when the page has one. */
    val tocUrl: String? = null,
    val prevUrl: String? = null,
)
