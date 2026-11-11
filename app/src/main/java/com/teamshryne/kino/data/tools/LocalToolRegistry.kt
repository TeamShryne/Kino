package com.teamshryne.kino.data.tools

import com.teamshryne.kino.domain.model.KinoMessage
import com.teamshryne.kino.domain.model.MessageRole
import com.teamshryne.kino.data.browser.KinoBrowserSession
import com.teamshryne.kino.office.OfficeFamily
import com.teamshryne.kino.office.OfficeFormat
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class LocalToolResult(val name: String, val label: String, val detail: String)

/**
 * User-set (Settings → PDF) caps for HTML-to-PDF. Values are clamped to the
 * hard maxima below, so settings can only make exports stricter, never
 * looser than the tested budget. Defaults match the hard maxima.
 */
data class PdfLimits(
    val maxPages: Int = LocalToolRegistry.MAX_PAGES,
    val maxHtmlChars: Int = LocalToolRegistry.MAX_HTML_CHARS,
    val maxPageChars: Int = LocalToolRegistry.MAX_PAGE_CHARS,
    val maxPdfBytes: Long = LocalToolRegistry.MAX_PDF_BYTES.toLong(),
) {
    // Defaults (20 / 200 KB / 100 KB / 25 MB) are the factory values; Settings
    // lets users go above them, so only absolute safety ceilings apply here.
    fun sanitized(): PdfLimits = PdfLimits(
        maxPages = maxPages.coerceIn(1, 100),
        maxHtmlChars = maxHtmlChars.coerceIn(1024, 1024 * 1024),
        maxPageChars = maxPageChars.coerceIn(1024, 512 * 1024),
        maxPdfBytes = maxPdfBytes.coerceIn(1024 * 1024, 100L * 1024 * 1024),
    )
}

/** A deliberately small, local-only tool surface. Tool arguments are validated again at execution. */
class LocalToolRegistry(
    private val messages: suspend (String) -> List<KinoMessage>,
    private val renderPdf: suspend (String, List<String>, Boolean) -> String,
    private val browse: suspend (JSONObject) -> String,
    private val office: OfficeToolExecutor? = null,
    private val pdfLimits: () -> PdfLimits = { PdfLimits() },
) {
    suspend fun execute(chatId: String, name: String, arguments: JSONObject, browserEnabled: Boolean): LocalToolResult {
        val canonicalName = canonicalToolName(name)
        if (canonicalName.startsWith("create_") && !arguments.has("format")) {
            inferredFormat(name)?.let { arguments.put("format", it) }
        }
        val spec = toolSpec(canonicalName) ?: error("Unknown tool: $name")
        val keys = arguments.keys().asSequence().toSet()
        require(keys.all { it in spec.allowedArguments }) { "Unexpected argument for $name." }
        fun stringArgument(key: String, required: Boolean) {
            if (required || arguments.has(key)) require(arguments.opt(key) is String) { "$key must be a string." }
        }
        when (canonicalName) {
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
                stringArgument("html", false)
                if (arguments.has("pages")) require(arguments.optJSONArray("pages") != null) { "pages must be an array of HTML strings." }
                require(arguments.has("pages") != arguments.has("html")) { "Provide exactly one of pages or html for html_to_pdf." }
            }
            "create_document", "create_spreadsheet", "create_presentation", "create_data_file" -> {
                stringArgument("format", true)
                stringArgument("theme", false)
                require(arguments.opt("document") != null) { "document must be a JSON object describing the file." }
                val format = officeFormat(arguments, familyFor(name))
                require(format != OfficeFormat.PDF || name == "create_document") { "PDF is only available from create_document." }
            }
            "document_help" -> stringArgument("topic", false)
            "browser" -> {
                stringArgument("action", true)
                require(browserEnabled) { "Web browsing is turned off. Ask the user to enable Web browsing in Message options." }
                when (arguments.getString("action")) {
                    "open" -> { stringArgument("url", true); if (arguments.has("new_tab")) require(arguments.opt("new_tab") is Boolean) }
                    "click" -> { stringArgument("element_id", false); stringArgument("selector", false); stringArgument("frame_selector", false); require(arguments.has("element_id") || arguments.has("selector")) { "click needs element_id or selector." } }
                    "type" -> { stringArgument("element_id", false); stringArgument("selector", false); stringArgument("frame_selector", false); stringArgument("text", true); require(arguments.has("element_id") || arguments.has("selector")) { "type needs element_id or selector." } }
                    "scroll" -> { stringArgument("direction", false); if (arguments.has("pages")) require(arguments.opt("pages") is Number) }
                    "read" -> stringArgument("query", false)
                    "tabs" -> stringArgument("tab_id", false)
                    "wait_for" -> { stringArgument("element_id", false); stringArgument("selector", false); stringArgument("query", false); stringArgument("text", false); stringArgument("state", false); require(listOf("element_id", "selector", "query", "text").count { arguments.has(it) } == 1) { "wait_for needs exactly one element_id, selector/query, or text." }; if (arguments.has("timeout_ms")) require(arguments.opt("timeout_ms") is Number) }
                    "press_key" -> { stringArgument("key", true); stringArgument("element_id", false) }
                    "hover" -> stringArgument("element_id", true)
                    "select_option" -> {
                        stringArgument("selector", false)
                        stringArgument("element_id", false)
                        stringArgument("frame_selector", false)
                        stringArgument("value", true)
                        require(arguments.has("selector") || arguments.has("element_id")) { "select_option needs selector or element_id." }
                    }
                    "query" -> { stringArgument("selector", true); stringArgument("mode", false); stringArgument("frame_selector", false) }
                    "element_state" -> stringArgument("selector", true)
                    "links", "tables" -> { if (arguments.has("offset")) require(arguments.opt("offset") is Number); if (arguments.has("limit")) require(arguments.opt("limit") is Number) }
                    "refresh", "forward", "images", "back", "close_tab" -> Unit
                    "viewport" -> stringArgument("mode", true)
                    "evaluate" -> stringArgument("script", true)
                    else -> error("Unsupported browser action.")
                }
                if (arguments.has("tab_id")) stringArgument("tab_id", false)
            }
        }
        val result = when (canonicalName) {
        "calculator" -> LocalToolResult(canonicalName, "Used calculator", Calculator.evaluate(arguments.getString("expression")))
        "date_time" -> {
            val zone = arguments.optString("timezone").ifBlank { ZoneId.systemDefault().id }
            val date = ZonedDateTime.now(ZoneId.of(zone))
            LocalToolResult(canonicalName, "Checked date and time", date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy · HH:mm:ss z (VV)")))
        }
        "search_chat" -> LocalToolResult(canonicalName, "Searched this chat", search(chatId, arguments))
        "html_to_pdf" -> {
            val title = arguments.optString("title", "Kino document").take(120).ifBlank { "Kino document" }
            val limits = pdfLimits().sanitized()
            val (pages, paged) = if (arguments.has("pages")) {
                val array = arguments.optJSONArray("pages") ?: error("pages must be an array.")
                require(array.length() in 1..limits.maxPages) { "pages must contain 1–${limits.maxPages} HTML pages." }
                val list = buildList {
                    for (i in 0 until array.length()) {
                        val page = array.optString(i)
                        require(page.isNotBlank()) { "Each page in pages must be a non-empty HTML string." }
                        require(page.length <= limits.maxPageChars) { "A single HTML page exceeds the ${limits.maxPageChars / 1000} KB limit." }
                        add(page)
                    }
                }
                require(list.sumOf { it.length } <= limits.maxHtmlChars) { "HTML exceeds the ${limits.maxHtmlChars / 1000} KB tool limit." }
                list to true
            } else {
                val html = arguments.getString("html")
                require(html.isNotBlank() && html.length <= limits.maxHtmlChars) { "HTML exceeds the ${limits.maxHtmlChars / 1000} KB tool limit." }
                listOf(html) to false
            }
            val filename = renderPdf(title, pages, paged)
            LocalToolResult(canonicalName, "Created PDF", JSONObject().put("filename", filename).put("title", title).put("pages", pages.size).toString())
        }
        "create_document", "create_spreadsheet", "create_presentation", "create_data_file" -> {
            val executor = requireNotNull(office) { "Office export is not available." }
            val format = officeFormat(arguments, familyFor(name))
            val payload = documentPayload(arguments)
            executor.create(name, format, payload, arguments.optString("theme").ifBlank { null })
        }
        "document_help" -> {
            val executor = requireNotNull(office) { "Office export is not available." }
            executor.help(arguments.optString("topic").ifBlank { null })
        }
        "browser" -> LocalToolResult(canonicalName, "Browsed website", browse(arguments).take(KinoBrowserSession.MAX_BROWSER_RESULT_CHARS))
        else -> error("Unknown tool: $name")
        }
        return result
    }

    private fun familyFor(toolName: String): OfficeFamily = when (toolName) {
        "create_spreadsheet" -> OfficeFamily.SHEET
        "create_presentation" -> OfficeFamily.DECK
        "create_data_file" -> OfficeFamily.DATA
        else -> OfficeFamily.DOCUMENT
    }

    private fun officeFormat(arguments: JSONObject, family: OfficeFamily): OfficeFormat {
        val raw = arguments.getString("format").trim().lowercase()
        val format = OfficeFormat.byId(raw)
            ?: throw IllegalArgumentException("Unknown format \"$raw\". Supported: ${OfficeFormat.byFamily(family).joinToString(", ") { it.id }}.")
        require(format.family == family) {
            "Format \"$raw\" is not supported by this tool. Supported: ${OfficeFormat.byFamily(family).joinToString(", ") { it.id }}."
        }
        return format
    }

    /** Accepts the payload as a nested object or as a JSON string. */
    private fun documentPayload(arguments: JSONObject): String = when (val value = arguments.opt("document")) {
        is JSONObject -> value.toString()
        is String -> value
        else -> throw IllegalArgumentException("document must be a JSON object describing the file.")
    }

    private suspend fun search(chatId: String, args: JSONObject): String {        val query = args.getString("query").trim()
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
        const val MAX_PAGE_CHARS = 100_000
        const val MAX_PAGES = 20
        const val MAX_PDF_BYTES = 25 * 1024 * 1024
        private const val MAX_RESULTS = 8
        private const val MAX_EXCERPT_CHARS = 360
        private const val MAX_SEARCH_OUTPUT_CHARS = 3_200

        data class ToolSpec(val name: String, val signature: String, val allowedArguments: Set<String>)
        private val catalog = listOf(
            ToolSpec("calculator", "calculator(expression: string): safe arithmetic evaluation", setOf("expression")),
            ToolSpec("date_time", "date_time(timezone?: string): current time in an IANA timezone; defaults to device timezone", setOf("timezone")),
            ToolSpec("search_chat", "search_chat(query: string, roles?: [user|assistant|tool], include_tools?: boolean, case_sensitive?: boolean, max_results?: 1..8, excerpt_chars?: 80..360): search saved messages in this chat; defaults to user+assistant, five newest short excerpts", setOf("query", "roles", "include_tools", "case_sensitive", "max_results", "excerpt_chars")),
            ToolSpec("html_to_pdf", "html_to_pdf(title?: string, pages?: [string], html?: string): render responsive HTML as a shareable PDF with Android WebView; JavaScript runs before printing. Prefer pages: one HTML fragment per A4 page (e.g. <style>…</style><main>…</main>, no <html> wrapper needed — Kino wraps each page), typically 1–20 pages (user limits in Settings may allow more). Each page is EXACTLY 794 CSS px wide × 1123 CSS px tall (A4) and Kino adds 14mm/12mm padding: design inside ~700px usable width and ~980px usable height per page. No fixed widths, min-widths, or flex/grid rows wider than ~700px, no vw widths beyond 100vw, no horizontal scrolling; use width:100%, box-sizing:border-box, flexible wrapping grid/flex, relative units, and keep text in normal wrapping flow. Images max-width:100% (height auto), tables width:100% with small padding and wrapped cells. Kino auto-shrinks the page if anything overflows, so stay near one page per fragment. Inline <script> and data-URI images work; set window.__kinoPdfReady=true after async rendering. Legacy html (single flowing document) is also accepted.", setOf("title", "html", "pages")),
            ToolSpec("browser", "browser(action: open|read|click|type|scroll|wait_for|press_key|hover|select_option|query|element_state|links|tables|refresh|forward|back|tabs|images|close_tab|viewport|evaluate, url?, element_id?, selector?, frame_selector?, text?, state?, key?, value?, mode?, script?, query?, direction?, pages?, offset?, limit?, timeout_ms?, tab_id?, new_tab?): browse websites in the shared Kino Browser when Web browsing is enabled. open/read prioritize named form fields and nearby buttons, including placeholders. type supports text inputs, textareas, and contenteditable fields, then returns updated controls. Displayed IDs such as a17 go in element_id, never in selector. wait_for can target element_id, real CSS selector/query, or text, with states visible/appear, hidden/disappear, attached, or detached. For example, wait_for(element_id=\"a17\", state=\"detached\") waits for that control to disappear. query supports CSS/XPath and same-origin iframe selection; links and tables return paged structured data; evaluate only accepts a safe editable-field assignment", setOf("action", "url", "element_id", "selector", "frame_selector", "text", "query", "direction", "pages", "tab_id", "new_tab", "state", "timeout_ms", "key", "value", "mode", "script", "offset", "limit")),
            ToolSpec("document_help", "document_help(topic?: document|sheet|deck|data): read the payload schema and a worked example before you build a file. Call it once per format family instead of guessing the shape.", setOf("topic")),
            ToolSpec("create_document", "create_document(format: docx|odt|pdf|html|epub|md|rtf|txt, document: object, theme?: string): build a properly typeset document from a structured payload. Write content and structure only - headings, paragraphs, lists, tables, key values, callouts, quotes, code, images, stat tiles and charts - never HTML, CSS or XML. Kino applies the typography, spacing, colours and pagination, and reports back anything that landed badly so you can fix it. Call document_help(topic=\"document\") first for the schema and an example.", setOf("format", "document", "theme")),
            ToolSpec("create_spreadsheet", "create_spreadsheet(format: xlsx|ods|csv|tsv, document: object, theme?: string): build a spreadsheet with typed columns, number formats, formulas, frozen headers and a totals row. Call document_help(topic=\"sheet\") for the schema.", setOf("format", "document", "theme")),
            ToolSpec("create_presentation", "create_presentation(format: pptx|odp, document: object, theme?: string): build a slide deck with title, section, content, two column, quote and metrics layouts plus speaker notes. Call document_help(topic=\"deck\") for the schema.", setOf("format", "document", "theme")),
            ToolSpec("create_data_file", "create_data_file(format: json|yaml|xml, document: object): write a data file. document = {title?: string, content: string}. Kino checks that JSON and XML parse.", setOf("format", "document")),
        )

        val promptDescriptions: String get() = catalog.joinToString("\n") { "- ${it.signature}" }
        fun hasTool(name: String): Boolean = catalog.any { it.name == canonicalToolName(name) }

        /**
         * Maps the tool names models reach for onto the canonical ones.
         *
         * The office tools are split by family, but models often ask for
         * "create_word" or "office_document". The alias table keeps those
         * working, and remembers the format the alias implies so the file
         * still comes out in the shape the user asked for.
         */
        private data class Alias(val tool: String, val format: String?)

        private val aliases: Map<String, Alias> = mapOf(
            "office_document" to Alias("create_document", null),
            "office" to Alias("create_document", null),
            "create_office" to Alias("create_document", null),
            "office_file" to Alias("create_document", null),
            "document" to Alias("create_document", null),
            "create_docx" to Alias("create_document", "docx"),
            "create_word" to Alias("create_document", "docx"),
            "word_document" to Alias("create_document", "docx"),
            "word" to Alias("create_document", "docx"),
            "create_excel" to Alias("create_spreadsheet", "xlsx"),
            "create_xlsx" to Alias("create_spreadsheet", "xlsx"),
            "spreadsheet" to Alias("create_spreadsheet", "xlsx"),
            "excel" to Alias("create_spreadsheet", "xlsx"),
            "sheet" to Alias("create_spreadsheet", "xlsx"),
            "create_ppt" to Alias("create_presentation", "pptx"),
            "create_pptx" to Alias("create_presentation", "pptx"),
            "presentation" to Alias("create_presentation", "pptx"),
            "powerpoint" to Alias("create_presentation", "pptx"),
            "deck" to Alias("create_presentation", "pptx"),
            "slides" to Alias("create_presentation", "pptx"),
            "data_file" to Alias("create_data_file", null),
        )

        /** The canonical tool name for [name], or [name] itself when it is already canonical. */
        fun canonicalToolName(name: String): String {
            val key = name.trim().lowercase()
            return aliases[key]?.tool ?: key
        }

        /** The format an alias implies, when the alias implies one. */
        fun inferredFormat(name: String): String? = aliases[name.trim().lowercase()]?.format
        fun activityLabel(name: String): String = when (canonicalToolName(name)) {
            "calculator" -> "Used calculator"
            "date_time" -> "Checked date and time"
            "search_chat" -> "Searched this chat"
            "html_to_pdf" -> "Created PDF"
            "create_document" -> "Created a document"
            "create_spreadsheet" -> "Created a spreadsheet"
            "create_presentation" -> "Created a presentation"
            "create_data_file" -> "Created a data file"
            "document_help" -> "Read the document schema"
            "browser" -> "Browsed website"
            else -> "Used tool"
        }
        private fun toolSpec(name: String): ToolSpec? = catalog.firstOrNull { it.name == canonicalToolName(name) }

        fun progress(name: String, arguments: JSONObject): Pair<String, String> = when (canonicalToolName(name)) {
            "calculator" -> "Using calculator" to "Evaluating ${arguments.optString("expression").take(80)}"
            "date_time" -> "Checking date and time" to "Timezone: ${arguments.optString("timezone").ifBlank { "device timezone" }.take(80)}"
            "search_chat" -> "Searching this chat" to "Query: ${arguments.optString("query").take(100)}"
            "html_to_pdf" -> "Working on PDF" to "Preparing ${arguments.optString("title").ifBlank { "document" }.take(100)}"
            "create_document" -> "Building the document" to "Preparing ${arguments.optString("format").uppercase().take(40)}"
            "create_spreadsheet" -> "Building the spreadsheet" to "Preparing ${arguments.optString("format").uppercase().take(40)}"
            "create_presentation" -> "Building the presentation" to "Preparing ${arguments.optString("format").uppercase().take(40)}"
            "create_data_file" -> "Writing the data file" to "Preparing ${arguments.optString("format").uppercase().take(40)}"
            "document_help" -> "Reading the document schema" to "Loading ${arguments.optString("topic").ifBlank { "all formats" }.take(60)}"
            "browser" -> "Browsing the web" to when (arguments.optString("action")) {
                "open" -> "Opening ${arguments.optString("url").take(100)}"
                "click" -> "Following page control ${arguments.optString("element_id").take(16)}"
                "type" -> "Filling a visible, non-password field"
                "wait_for" -> "Waiting for page content"
                "press_key" -> "Sending ${arguments.optString("key").take(20)} key"
                "hover" -> "Hovering over a page control"
                "select_option" -> "Choosing a form option"
                "query" -> "Inspecting selected page elements"
                "element_state" -> "Checking a page control's state"
                "links" -> "Collecting page links"
                "tables" -> "Extracting table data"
                "refresh" -> "Refreshing the page"
                "forward" -> "Going forward one page"
                "viewport" -> "Switching to ${arguments.optString("mode").take(20)} view"
                "evaluate" -> "Updating a form field safely"
                "scroll" -> "Reading the next page section"
                "tabs" -> "Checking open tabs"
                "images" -> "Collecting visible image URLs"
                "back" -> "Going back one page"
                else -> "Reading the current page"
            }
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
