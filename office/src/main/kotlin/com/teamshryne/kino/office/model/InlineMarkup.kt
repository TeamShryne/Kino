package com.teamshryne.kino.office.model

/**
 * A run of text with inline formatting.
 *
 * The model writes content with lightweight markup (`**bold**`, `*italic*`,
 * `` `code` ``, `~~strike~~`, `[label](url)`) and the pipeline converts it into
 * styled runs. That keeps the model's job to writing prose instead of
 * hand-authoring presentation markup in five different XML dialects.
 */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
    val muted: Boolean = false,
)

/** Parses lightweight inline markup into styled spans. */
object InlineMarkup {

    private val LINK = Regex("^\\[([^\\]]*)]\\(([^)\\s]+)\\)")

    fun parse(input: String): List<Span> {
        val spans = mutableListOf<Span>()
        val buffer = StringBuilder()
        var bold = false
        var italic = false
        var code = false
        var strike = false

        fun flush() {
            if (buffer.isEmpty()) return
            spans += Span(buffer.toString(), bold, italic, code, strike)
            buffer.setLength(0)
        }

        var index = 0
        while (index < input.length) {
            val c = input[index]
            if (c == '\\' && index + 1 < input.length && input[index + 1] in "\\*_`~[]()") {
                buffer.append(input[index + 1])
                index += 2
                continue
            }
            if (!code && input.startsWith("**", index)) {
                flush(); bold = !bold; index += 2; continue
            }
            if (!code && !bold && input.startsWith("~~", index)) {
                flush(); strike = !strike; index += 2; continue
            }
            if (!code && !bold && !strike && (c == '*' || c == '_')) {
                flush(); italic = !italic; index++; continue
            }
            if (!code && c == '`') {
                flush(); code = !code; index++; continue
            }
            if (!code && c == '[') {
                val match = LINK.matchAt(input, index)
                if (match != null) {
                    flush()
                    spans += Span(match.groupValues[1], link = match.groupValues[2])
                    index += match.value.length
                    continue
                }
            }
            buffer.append(c)
            index++
        }
        flush()
        return spans.filter { it.text.isNotEmpty() }
    }

    /** Removes inline markup, leaving readable plain text. */
    fun plain(input: String): String = parse(input).joinToString("") { it.text }
}

private fun Regex.matchAt(input: String, index: Int): MatchResult? =
    find(input, index)?.takeIf { it.range.first == index }
