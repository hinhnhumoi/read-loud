package com.tung.readloud.fetch

import com.tung.readloud.parse.TocParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Walks every page of a paginated table of contents and collects its chapter links in page order.
 * Known pages are fetched a few at a time, since each may need a slow WebView load.
 */
class TocLoader(private val fetcher: PageFetcher) {

    suspend fun load(tocUrl: String, onProgress: (pagesDone: Int, pagesKnown: Int) -> Unit): List<TocParser.Entry> =
        coroutineScope {
            val order = mutableListOf(tocUrl)
            val seen = mutableSetOf(TocParser.normalize(tocUrl))
            val entriesByPage = HashMap<String, List<TocParser.Entry>>()
            var next = 0
            onProgress(0, 1)
            while (next < order.size && next < MAX_PAGES) {
                val batch = order.subList(next, minOf(order.size, next + PARALLEL, MAX_PAGES)).toList()
                next += batch.size
                val pages = batch.map { url -> async { url to TocParser.parse(fetcher.fetch(url), url) } }.awaitAll()
                for ((url, page) in pages) {
                    entriesByPage[url] = page.entries
                    page.pageUrls.forEach { if (seen.add(TocParser.normalize(it))) order += it }
                }
                onProgress(next, order.size)
            }
            val merged = LinkedHashMap<String, TocParser.Entry>()
            order.forEach { url -> entriesByPage[url]?.forEach { merged.putIfAbsent(TocParser.normalize(it.url), it) } }
            merged.values.toList()
        }

    private companion object {
        const val MAX_PAGES = 100
        const val PARALLEL = 3
    }
}
