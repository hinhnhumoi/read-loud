package com.tung.readloud.book

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tung.readloud.data.Hashing
import com.tung.readloud.model.Chapter
import com.tung.readloud.parse.TocParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Imported EPUB, TXT and PDF books, stored as one JSON file per chapter under the app's files directory. */
class BookStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "books")

    data class Imported(val id: String, val title: String, val chapterCount: Int)

    /** Parses the picked file and stores it; importing the same file again returns the same id. */
    suspend fun import(resolver: ContentResolver, uri: Uri): Imported = withContext(Dispatchers.IO) {
        val (name, size) = nameAndSize(resolver, uri)
        val baseName = name.substringBeforeLast('.').ifBlank { "Sách" }
        val type = resolver.getType(uri)
        val format = when {
            name.endsWith(".epub", ignoreCase = true) || type == EPUB_MIME -> FORMAT_EPUB
            name.endsWith(".pdf", ignoreCase = true) || type == PDF_MIME -> FORMAT_PDF
            else -> FORMAT_TXT
        }
        val book = when (format) {
            FORMAT_EPUB -> resolver.openInputStream(uri)?.use { EpubParser.parse(it.buffered(), baseName) }
            FORMAT_PDF -> parsePdf(resolver, uri, baseName)
            else -> resolver.openInputStream(uri)?.use { TxtParser.parse(it.readBytes(), baseName) }
        } ?: throw IOException("Không mở được file")
        save(Hashing.sha1("$name|$size").take(16), book, format)
    }

    /** Stores pasted text as a book of its own; pasting the same text again gives the same book. */
    suspend fun importText(title: String, text: String): Imported = withContext(Dispatchers.IO) {
        val book = TxtParser.parse(text.toByteArray(Charsets.UTF_8), title)
        save(Hashing.sha1("text|$text").take(16), book, FORMAT_TXT)
    }

    private fun save(id: String, book: ParsedBook, format: String): Imported {
        val dir = File(root, id)
        dir.deleteRecursively()
        dir.mkdirs()
        book.chapters.forEachIndexed { i, ch ->
            File(dir, "$i.json").writeText(
                JSONObject().put("title", ch.title).put("paragraphs", JSONArray(ch.paragraphs)).toString(),
            )
        }
        File(dir, META).writeText(
            JSONObject()
                .put("title", book.title)
                .put("author", book.author ?: "")
                .put("titles", JSONArray(book.chapters.map { it.title }))
                .put("format", format)
                .toString(),
        )
        return Imported(id, book.title, book.chapters.size)
    }

    /** PdfBox needs random access, so the picked file is copied to the cache first. */
    private fun parsePdf(resolver: ContentResolver, uri: Uri, baseName: String): ParsedBook? {
        val temp = File.createTempFile("import", ".pdf", appContext.cacheDir)
        try {
            val copied = resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
            return copied?.let { PdfParser.parse(appContext, temp, baseName) }
        } finally {
            temp.delete()
        }
    }

    fun chapter(url: String): Chapter {
        val id = BookUrl.bookId(url) ?: throw IOException("Địa chỉ sách không hợp lệ")
        val index = BookUrl.index(url) ?: throw IOException("Địa chỉ chương không hợp lệ")
        val meta = meta(id)
        val file = File(File(root, id), "$index.json")
        if (!file.exists()) throw IOException("Sách đã bị xóa khỏi máy, hãy mở lại file")
        val json = JSONObject(file.readText())
        val paragraphs = json.getJSONArray("paragraphs").let { arr -> List(arr.length()) { arr.getString(it) } }
        val count = meta.getJSONArray("titles").length()
        return Chapter(
            url = url,
            title = json.getString("title"),
            paragraphs = paragraphs,
            nextUrl = if (index + 1 < count) BookUrl.chapter(id, index + 1) else null,
            prevUrl = if (index > 0) BookUrl.chapter(id, index - 1) else null,
            pageTitle = meta.getString("title"),
            tocUrl = BookUrl.toc(id),
        )
    }

    fun toc(id: String): List<TocParser.Entry> {
        val titles = meta(id).getJSONArray("titles")
        return List(titles.length()) { i -> TocParser.Entry(titles.getString(i), BookUrl.chapter(id, i)) }
    }

    fun title(id: String): String? = runCatching { meta(id).getString("title") }.getOrNull()

    data class Info(val title: String, val format: String?, val chapterCount: Int)

    /** Null when the book's files are gone, for example after clearing app data. */
    fun info(id: String): Info? = runCatching {
        val meta = meta(id)
        Info(meta.getString("title"), meta.optString("format").takeIf { it.isNotEmpty() }, meta.getJSONArray("titles").length())
    }.getOrNull()

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    private fun meta(id: String): JSONObject {
        val file = File(File(root, id), META)
        if (!file.exists()) throw IOException("Sách đã bị xóa khỏi máy, hãy mở lại file")
        return JSONObject(file.readText())
    }

    private fun nameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    size = if (c.isNull(1)) -1L else c.getLong(1)
                }
            }
        }
        return (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "sach.txt") to size
    }

    companion object {
        const val EPUB_MIME = "application/epub+zip"
        const val FORMAT_EPUB = "EPUB"
        const val PDF_MIME = "application/pdf"
        const val FORMAT_TXT = "TXT"
        const val FORMAT_PDF = "PDF"
        private const val META = "meta.json"
    }
}
