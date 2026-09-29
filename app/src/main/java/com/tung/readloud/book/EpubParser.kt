package com.tung.readloud.book

import com.tung.readloud.parse.HtmlText
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

/**
 * Reads an EPUB (2 or 3) in spine order. Chapter titles come from the book's own TOC; when the TOC points
 * at anchors inside one file, that file is split at those anchors into separate chapters.
 */
object EpubParser {
    private const val SPLIT_MARK = "\u0001RL_SPLIT_"
    private val textEntry = Regex("(?i)\\.(xhtml|html|htm|xml|opf|ncx)$")
    private val headingSelector = "h1, h2, h3"

    private class Item(val path: String, val mediaType: String, val properties: String)

    private class TocMark(val fragment: String?, val title: String)

    fun parse(input: InputStream, fallbackTitle: String): ParsedBook {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && textEntry.containsMatchIn(entry.name)) files[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        val container = files["META-INF/container.xml"] ?: throw IOException("File EPUB không hợp lệ: thiếu container.xml")
        val opfPath = xml(container).selectFirst("rootfile")?.attr("full-path")?.takeIf { it.isNotEmpty() }
            ?: throw IOException("File EPUB không hợp lệ: không thấy file OPF")
        val opf = xml(files[opfPath] ?: throw IOException("File EPUB thiếu $opfPath"))
        val opfDir = opfPath.substringBeforeLast('/', "")

        val title = opf.select("*").firstOrNull { it.tagName().endsWith("title") && it.parents().any { p -> p.tagName().endsWith("metadata") } }
            ?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle
        val author = opf.select("*").firstOrNull { it.tagName().endsWith("creator") }?.text()?.trim()?.takeIf { it.isNotEmpty() }

        val manifest = opf.select("manifest > item").associate { item ->
            item.attr("id") to Item(resolve(opfDir, item.attr("href")), item.attr("media-type"), item.attr("properties"))
        }
        val spine = opf.select("spine > itemref")
            .filter { it.attr("linear") != "no" }
            .mapNotNull { manifest[it.attr("idref")] }
            .filter { it.mediaType.contains("html") || it.path.endsWith("html", ignoreCase = true) }
        val tocSource = opf.selectFirst("spine")?.attr("toc")?.let { manifest[it] }
        val marks = tocMarks(files, manifest.values, tocSource)

        val chapters = mutableListOf<BookChapter>()
        for (item in spine) {
            val bytes = files[item.path] ?: continue
            chapters += chaptersOf(html(bytes), marks[item.path].orEmpty(), chapters.size)
        }
        if (chapters.isEmpty()) throw IOException("Không đọc được chữ trong file EPUB này")
        return ParsedBook(title, author, chapters)
    }

    private fun chaptersOf(doc: Document, marks: List<TocMark>, startNumber: Int): List<BookChapter> {
        val body = doc.body() ?: return emptyList()
        val anchored = marks.filter { m -> m.fragment != null && body.getElementById(m.fragment) != null }
        anchored.forEachIndexed { k, m -> body.getElementById(m.fragment!!)!!.before(TextNode("\n$SPLIT_MARK$k\n")) }
        val fileTitle = marks.firstOrNull { it.fragment == null }?.title
            ?: body.selectFirst(headingSelector)?.text()?.trim()?.takeIf { it.isNotEmpty() }

        val segments = mutableListOf<Pair<String?, MutableList<String>>>(fileTitle to mutableListOf())
        for (line in HtmlText.paragraphs(body, emptyList(), webJunk = false)) {
            if (line.startsWith(SPLIT_MARK)) {
                val k = line.removePrefix(SPLIT_MARK).toIntOrNull() ?: continue
                segments += anchored[k].title to mutableListOf()
            } else {
                segments.last().second += line
            }
        }
        return segments.mapNotNull { (title, paragraphs) ->
            val body = paragraphs.dropWhile { title != null && it.equals(title.trim(), ignoreCase = true) }
            if (body.isEmpty()) return@mapNotNull null
            val name = title ?: body.first().takeIf { it.length <= 80 } ?: "Phần ${startNumber + 1}"
            BookChapter(name, body)
        }.mapIndexed { i, ch -> if (ch.title.startsWith("Phần ")) ch.copy(title = "Phần ${startNumber + i + 1}") else ch }
    }

    /** TOC entries grouped by the file they point into, in TOC order. */
    private fun tocMarks(files: Map<String, ByteArray>, items: Collection<Item>, ncx: Item?): Map<String, List<TocMark>> {
        val out = LinkedHashMap<String, MutableList<TocMark>>()
        fun add(target: String, title: String) {
            val clean = title.trim().replace(Regex("\\s+"), " ")
            if (clean.isEmpty()) return
            val path = target.substringBefore('#')
            val fragment = target.substringAfter('#', "").takeIf { it.isNotEmpty() }
            out.getOrPut(path) { mutableListOf() } += TocMark(fragment, clean)
        }

        items.firstOrNull { it.properties.split(' ').contains("nav") }?.let { nav ->
            val doc = html(files[nav.path] ?: return@let)
            val tocNav = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: doc.selectFirst("nav")
            val navDir = nav.path.substringBeforeLast('/', "")
            tocNav?.select("a[href]")?.forEach { a -> add(resolveWithFragment(navDir, a.attr("href")), a.text()) }
        }
        if (out.isEmpty() && ncx != null) {
            val doc = xml(files[ncx.path] ?: return out)
            val ncxDir = ncx.path.substringBeforeLast('/', "")
            doc.select("navPoint").forEach { point ->
                val label = point.selectFirst("navLabel")?.text().orEmpty()
                val src = point.selectFirst("content")?.attr("src").orEmpty()
                if (src.isNotEmpty()) add(resolveWithFragment(ncxDir, src), label)
            }
        }
        return out
    }

    private fun xml(bytes: ByteArray): Document = Jsoup.parse(String(bytes, Charsets.UTF_8), "", Parser.xmlParser())

    private fun html(bytes: ByteArray): Document = Jsoup.parse(ByteArrayInputStream(bytes), null, "")

    private fun resolveWithFragment(baseDir: String, href: String): String {
        val fragment = href.substringAfter('#', "")
        val path = resolve(baseDir, href)
        return if (fragment.isEmpty()) path else "$path#$fragment"
    }

    /** Resolves an href relative to a directory inside the zip, decoding %XX and folding "..". */
    internal fun resolve(baseDir: String, href: String): String {
        val raw = href.substringBefore('#').substringBefore('?')
        val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
        val parts = ArrayDeque<String>()
        val joined = if (decoded.startsWith("/") || baseDir.isEmpty()) decoded.trimStart('/') else "$baseDir/$decoded"
        for (segment in joined.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }
}
