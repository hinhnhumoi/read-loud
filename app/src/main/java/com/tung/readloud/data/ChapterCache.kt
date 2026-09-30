package com.tung.readloud.data

import android.content.Context
import com.tung.readloud.model.Chapter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Parsed chapters on disk so a chapter can be read again, or offline, without refetching. */
class ChapterCache(context: Context) {
    private val dir = File(context.filesDir, "chapters").apply { mkdirs() }

    fun get(url: String): Chapter? = runCatching {
        val file = file(url)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val paragraphs = json.getJSONArray("paragraphs").let { arr -> List(arr.length()) { arr.getString(it) } }
        Chapter(
            url = json.getString("url"),
            title = json.getString("title"),
            paragraphs = paragraphs,
            nextUrl = json.optString("nextUrl").takeIf { it.isNotEmpty() },
            pageTitle = json.optString("pageTitle"),
            tocUrl = json.optString("tocUrl").takeIf { it.isNotEmpty() },
            prevUrl = json.optString("prevUrl").takeIf { it.isNotEmpty() },
        )
    }.getOrNull()

    fun contains(url: String): Boolean = file(url).exists()

    fun put(chapter: Chapter) {
        val json = JSONObject()
            .put("url", chapter.url)
            .put("title", chapter.title)
            .put("nextUrl", chapter.nextUrl ?: "")
            .put("pageTitle", chapter.pageTitle)
            .put("tocUrl", chapter.tocUrl ?: "")
            .put("prevUrl", chapter.prevUrl ?: "")
            .put("paragraphs", JSONArray(chapter.paragraphs))
        runCatching { file(chapter.url).writeText(json.toString()) }
    }

    fun trim(maxFiles: Int = 3000) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(maxFiles).forEach { it.delete() }
    }

    private fun file(url: String) = File(dir, Hashing.sha1(url) + ".json")
}
