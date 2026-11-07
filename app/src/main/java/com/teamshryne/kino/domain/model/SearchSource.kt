package com.teamshryne.kino.domain.model

import org.json.JSONArray
import org.json.JSONObject

data class SearchSource(
    val title: String,
    val url: String,
    val siteName: String = "",
    val snippet: String = "",
    val content: String = "",
)

object SearchSourceCodec {
    fun fromFragment(fragment: JSONObject): List<SearchSource> =
        fromArray(fragment.optJSONArray("results"))

    fun fromRaw(raw: Any?): List<SearchSource> = when (raw) {
        is JSONArray -> fromArray(raw)
        is JSONObject -> {
            val results = raw.optJSONArray("results")
            if (results != null) fromArray(results) else listOfNotNull(fromObject(raw))
        }
        is String -> runCatching {
            val value = raw.trim()
            when {
                value.startsWith("[") -> fromArray(JSONArray(value))
                value.startsWith("{") -> fromRaw(JSONObject(value))
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        else -> emptyList()
    }

    fun fromOpenFragment(fragment: JSONObject): SearchSource? {
        val raw = fragment.optJSONObject("raw")
        val url = firstNonBlank(fragment, "url", "link", "source_url")
            .ifBlank { raw?.let { firstNonBlank(it, "url", "link", "source_url") }.orEmpty() }
        val title = firstNonBlank(fragment, "title", "name")
            .ifBlank { raw?.let { firstNonBlank(it, "title", "name") }.orEmpty() }
        val site = firstNonBlank(fragment, "site_name", "site")
            .ifBlank { raw?.let { firstNonBlank(it, "site_name", "site") }.orEmpty() }
        if (url.isBlank() && title.isBlank()) return null
        val content = firstNonBlank(fragment, "content", "text")
            .ifBlank { raw?.let { firstNonBlank(it, "content", "text") }.orEmpty() }
        return SearchSource(title = title.ifBlank { site.ifBlank { url } }, url = url, siteName = site, content = content)
    }

    fun decode(detail: String): List<SearchSource> {
        if (detail.isBlank()) return emptyList()
        val decoded = fromRaw(detail)
        if (decoded.isNotEmpty()) return decoded

        // Older chat rows stored title, URL, and snippet as plain lines.
        val lines = detail.lines().map(String::trim).filter(String::isNotBlank)
        return lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("https://") && !line.startsWith("http://")) return@mapIndexedNotNull null
            SearchSource(
                title = lines.getOrNull(index - 1)?.takeUnless { it.startsWith("Search:") }.orEmpty().ifBlank { line },
                url = line,
                snippet = lines.getOrNull(index + 1)?.takeUnless { it.startsWith("http://") || it.startsWith("https://") }.orEmpty(),
            )
        }
    }

    fun encode(sources: List<SearchSource>): String = JSONArray().apply {
        sources.forEach { source ->
            put(JSONObject()
                .put("title", source.title)
                .put("url", source.url)
                .put("site_name", source.siteName)
                .put("snippet", source.snippet)
                .put("content", source.content))
        }
    }.toString()

    fun merge(existing: List<SearchSource>, incoming: List<SearchSource>): List<SearchSource> {
        val result = existing.toMutableList()
        incoming.forEach { source ->
            val index = result.indexOfFirst { it.url.isNotBlank() && it.url.key() == source.url.key() }
            if (index < 0) result += source
            else {
                val current = result[index]
                result[index] = current.copy(
                    title = source.title.ifBlank { current.title },
                    siteName = source.siteName.ifBlank { current.siteName },
                    snippet = source.snippet.ifBlank { current.snippet },
                    content = source.content.ifBlank { current.content },
                )
            }
        }
        return result
    }

    fun mergeOpen(existing: List<SearchSource>, page: SearchSource): List<SearchSource> {
        if (page.url.isNotBlank()) return merge(existing, listOf(page))
        if (existing.isEmpty()) return listOf(page)
        return existing.toMutableList().also { sources ->
            val last = sources.lastIndex
            sources[last] = sources[last].copy(
                title = page.title.ifBlank { sources[last].title },
                siteName = page.siteName.ifBlank { sources[last].siteName },
                content = page.content.ifBlank { sources[last].content },
            )
        }
    }

    fun appendLastContent(existing: List<SearchSource>, chunk: String): List<SearchSource> {
        if (chunk.isEmpty() || existing.isEmpty()) return existing
        return existing.toMutableList().also { sources ->
            val last = sources.lastIndex
            sources[last] = sources[last].copy(content = sources[last].content + chunk)
        }
    }

    private fun fromArray(array: JSONArray?): List<SearchSource> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            fromObject(array.optJSONObject(index))?.let(::add)
        }
    }

    private fun fromObject(value: JSONObject?): SearchSource? {
        if (value == null) return null
        val url = firstNonBlank(value, "url", "link", "source_url")
        if (url.isBlank()) return null
        return SearchSource(
            title = firstNonBlank(value, "title", "name").ifBlank { url },
            url = url,
            siteName = firstNonBlank(value, "site_name", "site"),
            snippet = firstNonBlank(value, "snippet", "description"),
            content = firstNonBlank(value, "content", "text"),
        )
    }

    private fun firstNonBlank(value: JSONObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> value.optString(key).takeIf { it.isNotBlank() && it != "null" } }.orEmpty()

    private fun String.key(): String = trimEnd('/').lowercase()
}
