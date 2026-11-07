package com.teamshryne.kino.data.tools

import com.teamshryne.kino.domain.model.KinoMessage
import com.teamshryne.kino.domain.model.MessageRole
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class LocalToolResult(val name: String, val label: String, val detail: String)

/** A deliberately small, local-only tool surface. Tool arguments are validated again at execution. */
class LocalToolRegistry(
    private val messages: suspend (String) -> List<KinoMessage>,
    private val renderPdf: suspend (String, String) -> String,
) {
    suspend fun execute(chatId: String, name: String, arguments: JSONObject): LocalToolResult {
        val spec = toolSpec(name) ?: error("Unknown tool: $name")
        val keys = arguments.keys().asSequence().toSet()
        require(keys.all { it in spec.allowedArguments }) { "Unexpected argument for $name." }
        fun stringArgument(key: String, required: Boolean) {
            if (required || arguments.has(key)) require(arguments.opt(key) is String) { "$key must be a string." }
        }
        when (name) {
            "calculator" -> stringArgument("expression", true)
            "date_time" -> stringArgument("timezone", false)
            "search_chat" -> {
                stringArgument("query", true)
                if (arguments.has("roles")) require(arguments.optJSONArray("roles") != null) { "roles must be an array." }
                listOf("include_tools", "case_sensitive").forEach { key ->
                    if (arguments.has(key)) require(arguments.opt(key) is Boolean) { "$key must be true or false." }
                }
                listOf("max_results" to 1..MAX_RESULTS, "excerpt_chars" to 80..MAX_EXCERPT_CHARS).forEach { (key, range) ->
                    if (arguments.has(key)) require(arguments.opt(key) is Number && arguments.optInt(key) in range) { "$key is outside its supported range." }
                }
            }
            "html_to_pdf" -> {
                stringArgument("title", false)
                stringArgument("html", true)
            }
        }
        val result = when (name) {
        "calculator" -> LocalToolResult(name, "Used calculator", Calculator.evaluate(arguments.getString("expression")))
        "date_time" -> {
            val zone = arguments.optString("timezone").ifBlank { ZoneId.systemDefault().id }
            val date = ZonedDateTime.now(ZoneId.of(zone))
            LocalToolResult(name, "Checked date and time", date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy · HH:mm:ss z (VV)")))
        }
        "search_chat" -> LocalToolResult(name, "Searched this chat", search(chatId, arguments))
        "html_to_pdf" -> {
            val title = arguments.optString("title", "Kino document").take(120).ifBlank { "Kino document" }
            val html = arguments.getString("html")
            require(html.length <= MAX_HTML_CHARS) { "HTML exceeds the ${MAX_HTML_CHARS / 1000} KB tool limit." }
            val filename = renderPdf(title, html)
            LocalToolResult(name, "Created PDF", JSONObject().put("filename", filename).put("title", title).toString())
        }
        else -> error("Unknown tool: $name")
        }
        return result
    }

    private suspend fun search(chatId: String, args: JSONObject): String {
        val query = args.getString("query").trim()
        require(query.isNotEmpty() && query.length <= 200) { "Query must be 1–200 characters." }
        val roles = args.optJSONArray("roles")
        val allowed = if (roles == null || roles.length() == 0) setOf("user", "assistant") else buildSet {
            for (index in 0 until roles.length()) add(roles.optString(index).lowercase())
        }
        require(allowed.all { it in setOf("user", "assistant", "tool") }) { "roles may contain user, assistant, and tool." }
        val includeTools = args.optBoolean("include_tools", false)
        val caseSensitive = args.optBoolean("case_sensitive", false)
        val maxResults = args.optInt("max_results", 5)
        val perExcerpt = args.optInt("excerpt_chars", 240)
        val needle = if (caseSensitive) query else query.lowercase()
        val all = messages(chatId)
        val matches = all.asSequence().filter { message ->
            val role = message.role.name.lowercase()
            role in allowed && (includeTools || message.role != MessageRole.TOOL) &&
                (if (caseSensitive) searchableText(message) else searchableText(message).lowercase()).contains(needle)
        }.toList()
        if (matches.isEmpty()) return "No matching messages. Searched ${all.size} local messages."
        val result = matches.takeLast(maxResults).asReversed().map { message ->
            val text = searchableText(message)
            val haystack = if (caseSensitive) text else text.lowercase()
            val index = haystack.indexOf(needle).coerceAtLeast(0)
            val start = (index - perExcerpt / 3).coerceAtLeast(0)
            val excerpt = text.substring(start, minOf(text.length, start + perExcerpt)).replace('\n', ' ')
            "[${message.role.name.lowercase()} · ${DateTimeFormatter.ISO_LOCAL_DATE.format(java.time.Instant.ofEpochMilli(message.createdAt).atZone(ZoneId.systemDefault()))}] $excerpt"
        }
        val header = "${matches.size} match(es); showing ${result.size} newest."
        return (listOf(header) + result).joinToString("\n").take(MAX_SEARCH_OUTPUT_CHARS)
    }

    private fun searchableText(message: KinoMessage): String = buildString {
        append(message.text)
        if (message.role == MessageRole.TOOL && !message.tool?.detail.isNullOrBlank()) {
            append("\n")
            append(message.tool?.detail)
        }
    }

    companion object {
        const val MAX_HTML_CHARS = 200_000
        private const val MAX_RESULTS = 8
        private const val MAX_EXCERPT_CHARS = 360
        private const val MAX_SEARCH_OUTPUT_CHARS = 3_200

        data class ToolSpec(val name: String, val signature: String, val allowedArguments: Set<String>)
        private val catalog = listOf(
            ToolSpec("calculator", "calculator(expression: string): safe arithmetic evaluation", setOf("expression")),
            ToolSpec("date_time", "date_time(timezone?: string): current time in an IANA timezone; defaults to device timezone", setOf("timezone")),
            ToolSpec("search_chat", "search_chat(query: string, roles?: [user|assistant|tool], include_tools?: boolean, case_sensitive?: boolean, max_results?: 1..8, excerpt_chars?: 80..360): search saved messages in this chat; defaults to user+assistant, five newest short excerpts", setOf("query", "roles", "include_tools", "case_sensitive", "max_results", "excerpt_chars")),
            ToolSpec("html_to_pdf", "html_to_pdf(title: string, html: string): render self-contained HTML as a shareable PDF; JavaScript and network resources are disabled", setOf("title", "html")),
        )

        val promptDescriptions: String get() = catalog.joinToString("\n") { "- ${it.signature}" }
        fun hasTool(name: String): Boolean = catalog.any { it.name == name }
        fun activityLabel(name: String): String = when (name) {
            "calculator" -> "Used calculator"
            "date_time" -> "Checked date and time"
            "search_chat" -> "Searched this chat"
            "html_to_pdf" -> "Created PDF"
            else -> "Used tool"
        }
        private fun toolSpec(name: String): ToolSpec? = catalog.firstOrNull { it.name == name }

        fun progress(name: String, arguments: JSONObject): Pair<String, String> = when (name) {
            "calculator" -> "Using calculator" to "Evaluating ${arguments.optString("expression").take(80)}"
            "date_time" -> "Checking date and time" to "Timezone: ${arguments.optString("timezone").ifBlank { "device timezone" }.take(80)}"
            "search_chat" -> "Searching this chat" to "Query: ${arguments.optString("query").take(100)}"
            "html_to_pdf" -> "Working on PDF" to "Preparing ${arguments.optString("title").ifBlank { "document" }.take(100)}"
            else -> "Running tool" to "Validating request"
        }
    }
}

/** Arithmetic parser; intentionally excludes variables, calls, assignment and any executable syntax. */
private object Calculator {
    fun evaluate(expression: String): String {
        require(expression.length in 1..500) { "Expression must be 1–500 characters." }
        val parser = Parser(expression)
        val value = parser.parse()
        require(value.isFinite() && abs(value) <= 1e100) { "Result is outside the supported range." }
        return "${expression.trim()} = ${if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()}"
    }

    private class Parser(private val source: String) {
        private var index = 0
        fun parse(): Double {
            val value = expression(0)
            whitespace()
            require(index == source.length) { "Unexpected input at position ${index + 1}." }
            return value
        }
        private fun expression(minBinding: Int): Double {
            whitespace()
            var left = when {
                take('+') -> expression(30)
                take('-') -> -expression(30)
                take('(') -> expression(0).also { whitespace(); require(take(')')) { "Missing closing parenthesis." } }
                else -> number()
            }
            while (true) {
                whitespace()
                val op = source.getOrNull(index) ?: break
                val binding = when (op) { '+', '-' -> 10; '*', '/', '%' -> 20; '^' -> 40; else -> break }
                if (binding < minBinding) break
                index++
                val right = expression(if (op == '^') binding else binding + 1)
                left = when (op) {
                    '+' -> left + right; '-' -> left - right; '*' -> left * right
                    '/' -> { require(right != 0.0) { "Division by zero." }; left / right }
                    '%' -> { require(right != 0.0) { "Remainder by zero." }; left % right }
                    else -> left.pow(right)
                }
                require(left.isFinite() && abs(left) <= 1e100) { "Result is outside the supported range." }
            }
            return left
        }
        private fun number(): Double {
            whitespace()
            val start = index
            var dot = false
            while (index < source.length) {
                val c = source[index]
                if (c.isDigit()) index++ else if (c == '.' && !dot) { dot = true; index++ } else break
            }
            require(index > start && source.substring(start, index) != ".") { "Expected a number at position ${start + 1}." }
            return source.substring(start, index).toDouble()
        }
        private fun whitespace() { while (source.getOrNull(index)?.isWhitespace() == true) index++ }
        private fun take(c: Char): Boolean = if (source.getOrNull(index) == c) { index++; true } else false
    }
}

private fun Double.pow(other: Double): Double = Math.pow(this, other)
