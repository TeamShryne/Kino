package com.teamshryne.kino.ui.components

/**
 * A single renderable piece of a markdown message.
 *
 * Kino renders every piece with the control it needs: prose and display math go through
 * Markwon, code blocks become cards with copy/download actions and tables keep their own
 * horizontally scrollable container.
 */
internal sealed interface MarkdownSegment {
    /** Regular markdown: paragraphs, lists, quotes, inline math, ... */
    data class Prose(val markdown: String) : MarkdownSegment

    /** A fenced code block, with the info string (language) and the raw body. */
    data class Code(val language: String, val code: String) : MarkdownSegment

    /** A pipe table. */
    data class Table(val markdown: String, val columnCount: Int) : MarkdownSegment

    /** A display formula, without the surrounding `$$` markers. */
    data class Math(val latex: String) : MarkdownSegment
}

/**
 * Splits a markdown message into [MarkdownSegment]s and normalizes LaTeX so that the
 * renderer never has to guess.
 *
 * LaTeX is accepted in every notation a model is likely to produce:
 *
 *  * `$x^2$` inline math
 *  * `$$ ... $$` display math, on one line or spread over several
 *  * `\(x\)` and `\[x\]` inline/display math
 *  * `\begin{math} ... \end{math}` and the amsmath environments
 *    (`align`, `gather`, `multline`, `cases`, `matrix`, `array`, ...)
 *
 * Everything that is not a formula keeps its literal `$` characters: they are escaped so the
 * math renderer never swallows the text around them.
 */
internal object MarkdownParser {

    /** Environments that hold display math. */
    private val DISPLAY_ENVIRONMENTS = setOf(
        "align", "align*", "aligned", "aligned*", "alignat", "alignat*", "alignedat", "alignedat*",
        "array", "cases", "displaymath", "eqnarray", "equation", "equation*", "flalign", "flalign*",
        "gather", "gather*", "gathered", "multline", "multline*", "split",
    )

    /** Environments the formula engine knows under a different name. */
    private val ENVIRONMENT_ALIASES = mapOf(
        "align*" to "align",
        "aligned*" to "aligned",
        "alignat*" to "alignat",
        "alignedat*" to "alignedat",
        "flalign*" to "flalign",
        "gather*" to "gather",
        "multline*" to "multline",
    )

    /** Environments that only wrap a formula and carry no layout of their own. */
    private val PLAIN_ENVIRONMENTS = setOf("equation", "equation*")

    /** The inline math delimiter understood by the formula renderer. */
    private const val INLINE_MATH = "$$"

    /** Commands that carry no meaning for the formula engine. */
    private val NOISE_COMMANDS = listOf("\\tag{", "\\label{", "\\nonumber", "\\notag")

    fun segments(markdown: String): List<MarkdownSegment> {
        if (markdown.isBlank()) return emptyList()

        val lines = markdown.split('\n')
        val segments = mutableListOf<MarkdownSegment>()
        val prose = StringBuilder()

        fun flushProse() {
            if (prose.isEmpty()) return
            val value = prose.toString()
            prose.setLength(0)
            if (value.isNotBlank()) segments += MarkdownSegment.Prose(normalizeInline(value))
        }

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()

            val fence = codeFence(line)
            if (fence != null) {
                flushProse()
                val info = trimmed.substring(fence.length).trim()
                val language = info.substringBefore(' ').substringBefore('\t').trim().lowercase()
                val code = StringBuilder()
                index++
                while (index < lines.size && !closesFence(lines[index], fence)) {
                    code.append(lines[index]).append('\n')
                    index++
                }
                if (index < lines.size) index++
                segments += MarkdownSegment.Code(language, code.toString().trimEnd('\n'))
                continue
            }

            // `$$` alone on a line opens a display formula.
            if (trimmed == "$$") {
                flushProse()
                val body = StringBuilder()
                var closed = false
                index++
                while (index < lines.size) {
                    val current = lines[index].trim()
                    if (current == "$$") {
                        closed = true
                        index++
                        break
                    }
                    body.append(lines[index]).append('\n')
                    index++
                }
                if (closed) {
                    segments += MarkdownSegment.Math(cleanLatex(body.toString().trimEnd('\n')))
                } else {
                    // Still streaming: keep the raw text instead of swallowing the rest.
                    prose.append("$$\n").append(body.toString().trimEnd('\n'))
                    if (index < lines.size) prose.append('\n')
                }
                continue
            }

            // `$$x$$` alone on a line is a display formula as well.
            val singleLine = singleLineDisplay(trimmed)
            if (singleLine != null) {
                flushProse()
                segments += MarkdownSegment.Math(cleanLatex(singleLine))
                index++
                continue
            }

            // `\[ ... \]` display math.
            if (trimmed.startsWith("\\[")) {
                val inlineBody = if (trimmed.length > 3 && trimmed.endsWith("\\]")) {
                    trimmed.removePrefix("\\[").removeSuffix("\\]").trim().ifBlank { null }
                } else {
                    null
                }
                if (inlineBody != null) {
                    flushProse()
                    segments += MarkdownSegment.Math(inlineBody)
                    index++
                    continue
                }
                val end = findClosing(lines, index + 1, "\\]")
                if (end >= 0) {
                    flushProse()
                    val body = StringBuilder()
                    body.append(lines[index].trim().removePrefix("\\[").trim())
                    for (lineIndex in index + 1..end) {
                        body.append('\n')
                        body.append(
                            if (lineIndex == end) lines[lineIndex].substringBefore("\\]").trim()
                            else lines[lineIndex].trim(),
                        )
                    }
                    segments += MarkdownSegment.Math(cleanLatex(body.toString().trim()))
                    index = end + 1
                    continue
                }
            }

            // `\begin{env} ... \end{env}` display math.
            val environment = displayEnvironment(trimmed)
            if (environment != null) {
                val end = findEnvironmentEnd(lines, index, environment)
                if (end >= 0) {
                    flushProse()
                    val body = lines.subList(index, end + 1)
                    segments += MarkdownSegment.Math(rewriteEnvironment(environment, body))
                    index = end + 1
                    continue
                }
            }

            if (isTableHeader(line) && index + 1 < lines.size && isTableDivider(lines[index + 1])) {
                flushProse()
                val table = StringBuilder().append(line).append('\n').append(lines[index + 1])
                val columns = cellCount(line)
                index += 2
                while (index < lines.size && lines[index].isNotBlank() && lines[index].contains('|')) {
                    table.append('\n').append(lines[index])
                    index++
                }
                segments += MarkdownSegment.Table(normalizeInline(table.toString()), columns)
                continue
            }

            prose.append(line)
            if (index < lines.lastIndex) prose.append('\n')
            index++
        }

        flushProse()
        return segments
    }

    /**
     * Rewrites inline math into the delimiter the formula renderer understands and escapes every
     * other `$` so literal prices and shell variables keep working.
     */
    fun normalizeInline(text: String): String {
        if ('$' !in text && "\\(" !in text && "\\[" !in text && "\\begin{math}" !in text) return text

        val out = StringBuilder(text.length + 16)
        var index = 0
        while (index < text.length) {
            when (val char = text[index]) {
                '`' -> {
                    val run = runLength(text, index, '`')
                    val close = findRun(text, index + run, '`', run)
                    val stop = if (close < 0) text.length else close + run
                    out.append(text, index, stop)
                    index = stop
                }
                '$' -> index += appendDollar(text, index, out)
                '\\' -> index += appendLatexCommand(text, index, out)
                else -> {
                    out.append(char)
                    index++
                }
            }
        }
        return out.toString()
    }

    private fun appendDollar(text: String, start: Int, out: StringBuilder): Int {
        // `$$ ... $$` inline math.
        if (start + 1 < text.length && text[start + 1] == '$') {
            val end = text.indexOf("$$", start + 2)
            val newline = text.indexOf('\n', start + 2)
            if (end > start + 2 && (newline < 0 || newline > end)) {
                out.append(INLINE_MATH).append(text, start + 2, end).append(INLINE_MATH)
                return end + 2 - start
            }
            out.append("\\$\\$")
            return 2
        }

        // `$ ... $` inline math.
        val close = inlineMathEnd(text, start)
        if (close > 0) {
            out.append(INLINE_MATH).append("\\textstyle{")
                .append(cleanLatex(text.substring(start + 1, close))).append("}").append(INLINE_MATH)
            return close + 1 - start
        }

        out.append("\\$")
        return 1
    }

    private fun appendLatexCommand(text: String, start: Int, out: StringBuilder): Int {
        val mathEnvironment = "\\begin{math}"
        if (text.regionMatches(start, mathEnvironment, 0, mathEnvironment.length)) {
            val end = text.indexOf("\\end{math}", start + mathEnvironment.length)
            if (end > 0) {
                val body = text.substring(start + mathEnvironment.length, end)
                out.append(INLINE_MATH).append("\\textstyle{").append(body).append("}").append(INLINE_MATH)
                return end + "\\end{math}".length - start
            }
            out.append('\\')
            return 1
        }

        val opening = text.getOrNull(start + 1)
        if (opening != '(' && opening != '[') {
            out.append('\\')
            return 1
        }
        val closing = if (opening == '(') ')' else ']'
        val end = text.indexOf("\\$closing", start + 2)
        if (end < 0 || text.indexOf('\n', start + 2) in 0 until end) {
            out.append('\\')
            return 1
        }
        val body = text.substring(start + 2, end)
        if (opening == '(') {
            out.append(INLINE_MATH).append("\\textstyle{").append(body).append("}").append(INLINE_MATH)
        } else {
            out.append(INLINE_MATH).append(body).append(INLINE_MATH)
        }
        return end + 2 - start
    }

    /**
     * Finds the closing `$` of an inline formula. A formula never starts or ends with a space and
     * a closing `$` is never followed by a digit, which keeps prices such as `$5 and $10` intact.
     */
    private fun inlineMathEnd(text: String, start: Int): Int {
        var index = start + 1
        if (index >= text.length || text[index].isWhitespace()) return -1
        while (index < text.length) {
            val char = text[index]
            if (char == '\n') return -1
            if (char == '$') {
                if (index == start + 1) return -1
                if (text[index - 1].isWhitespace()) return -1
                if (index + 1 < text.length && text[index + 1].isDigit()) return -1
                return index
            }
            index++
        }
        return -1
    }

    private fun runLength(text: String, start: Int, char: Char): Int {
        var count = 0
        while (start + count < text.length && text[start + count] == char) count++
        return count
    }

    private fun findRun(text: String, from: Int, char: Char, length: Int): Int {
        var index = from
        while (index < text.length) {
            if (text[index] == char) {
                val run = runLength(text, index, char)
                if (run == length) return index
                index += run
            } else {
                index++
            }
        }
        return -1
    }

    private data class CodeFence(val char: Char, val length: Int)

    private fun codeFence(line: String): CodeFence? {
        val trimmed = line.trimStart()
        // Four spaces of indentation is an indented code block, not a fence.
        if (line.length - trimmed.length > 3) return null
        if (trimmed.length < 3) return null
        val char = trimmed[0]
        if (char != '`' && char != '~') return null
        val length = runLength(trimmed, 0, char)
        if (length < 3) return null
        return CodeFence(char, length)
    }

    private fun closesFence(line: String, fence: CodeFence): Boolean {
        val trimmed = line.trim()
        return trimmed.length >= fence.length && trimmed.all { it == fence.char }
    }

    private fun singleLineDisplay(trimmed: String): String? {
        if (!trimmed.startsWith("$$") || !trimmed.endsWith("$$") || trimmed.length < 5) return null
        val body = trimmed.substring(2, trimmed.length - 2)
        if (body.contains("$$")) return null
        return body.trim().ifBlank { null }
    }

    private fun displayEnvironment(trimmed: String): String? {
        if (!trimmed.startsWith("\\begin{")) return null
        val end = trimmed.indexOf('}')
        if (end < 0) return null
        val name = trimmed.substring("\\begin{".length, end).trim()
        return name.takeIf { it in DISPLAY_ENVIRONMENTS }
    }

    private fun findClosing(lines: List<String>, from: Int, marker: String): Int {
        for (index in from until lines.size) {
            if (lines[index].contains(marker)) return index
        }
        return -1
    }

    private fun findEnvironmentEnd(lines: List<String>, from: Int, environment: String): Int {
        val marker = "\\end{$environment}"
        for (index in from + 1 until lines.size) {
            if (lines[index].contains(marker)) return index
        }
        return -1
    }

    private fun rewriteEnvironment(environment: String, lines: List<String>): String {
        val alias = ENVIRONMENT_ALIASES[environment] ?: environment
        if (environment !in PLAIN_ENVIRONMENTS) {
            return cleanLatex(
                lines.joinToString("\n")
                    .replace("\\begin{$environment}", "\\begin{$alias}")
                    .replace("\\end{$environment}", "\\end{$alias}"),
            )
        }
        if (lines.size < 2) return ""
        val opening = lines.first().replace("\\begin{$environment}", "").trim()
        val closing = lines.last().replace("\\end{$environment}", "").trim()
        val middle = lines.subList(1, lines.size - 1)
        return cleanLatex(
            (listOf(opening) + middle + listOf(closing))
                .filter { it.isNotEmpty() }
                .joinToString("\n"),
        )
    }

    /** Removes commands the formula engine does not know but that carry no meaning. */
    private fun cleanLatex(latex: String): String {
        var result = latex
        for (command in NOISE_COMMANDS) {
            result = removeCommand(result, command)
        }
        return result
    }

    private fun removeCommand(latex: String, command: String): String {
        var result = latex
        while (true) {
            val index = result.indexOf(command)
            if (index < 0) return result
            val end = if (command.endsWith("{")) matchingBrace(result, index + command.length - 1) else -1
            result = if (end < 0) result.removeRange(index, index + command.length)
            else result.removeRange(index, end + 1)
        }
    }

    private fun matchingBrace(text: String, openIndex: Int): Int {
        if (openIndex < 0 || openIndex >= text.length || text[openIndex] != '{') return -1
        var depth = 0
        for (index in openIndex until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun isTableHeader(line: String): Boolean = line.contains('|') && cellCount(line) >= 2

    private fun isTableDivider(line: String): Boolean {
        val cells = line.trim().trim('|').split('|').map(String::trim)
        return cells.size >= 2 && cells.all { it.matches(Regex(":?-{3,}:?")) }
    }

    private fun cellCount(line: String): Int = line.trim().trim('|').split('|').size
}
