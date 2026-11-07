package com.teamshryne.kino.ui.components

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.key
import com.teamshryne.kino.R
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.ImagesPlugin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.FlowPreview
import kotlin.math.roundToInt

/** Renders completed stream lines in measured, device-width-sized batches. */
@Composable
@OptIn(FlowPreview::class)
fun StreamingMarkdownText(markdown: String, tableCellMinWidthDp: Int = 112, modifier: Modifier = Modifier) {
    var visibleMarkdown by remember { mutableStateOf("") }
    var consumedCharacters by remember { mutableIntStateOf(0) }
    val latestMarkdown by rememberUpdatedState(markdown)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val widthPx = with(density) { (maxWidth - 24.dp).coerceAtLeast(120.dp).toPx() }
        val context = LocalContext.current
        val textPaint = remember(context, widthPx) {
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics)
            }
        }

        LaunchedEffect(widthPx) {
            snapshotFlow { latestMarkdown }.distinctUntilChanged().collect { current ->
                if (!current.startsWith(visibleMarkdown)) {
                    visibleMarkdown = ""
                    consumedCharacters = 0
                }
                var cursor = consumedCharacters
                while (cursor < current.length) {
                    val newline = current.indexOf('\n', cursor)
                    val contentEnd = if (newline >= 0) newline else current.length
                    if (cursor == contentEnd) {
                        if (newline >= 0) {
                            cursor = newline + 1
                            consumedCharacters = cursor
                            visibleMarkdown = current.substring(0, cursor)
                            delay(32)
                            continue
                        }
                        break
                    }

                    val available = contentEnd - cursor
                    val fit = textPaint.breakText(current, cursor, contentEnd, true, widthPx, null).coerceIn(1, available)
                    val overflowsWidth = fit < available
                    // Hold a short partial line until it wraps, ends with a newline, or pauses.
                    if (!overflowsWidth && newline < 0) break
                    var lineEnd = cursor + fit
                    if (overflowsWidth) {
                        val whitespace = current.lastIndexOfAny(charArrayOf(' ', '\t'), lineEnd - 1)
                        if (whitespace > cursor) lineEnd = whitespace + 1
                    } else if (newline >= 0) {
                        lineEnd = newline + 1
                    }
                    cursor = lineEnd
                    consumedCharacters = cursor
                    visibleMarkdown = current.substring(0, cursor)
                    delay(32)
                }
            }
        }
        LaunchedEffect(widthPx) {
            snapshotFlow { latestMarkdown }.distinctUntilChanged().debounce(260).collect { quietText ->
                if (!quietText.startsWith(visibleMarkdown) || consumedCharacters >= quietText.length) return@collect
                val newline = quietText.indexOf('\n', consumedCharacters)
                if (newline >= 0) return@collect
                val remaining = quietText.length - consumedCharacters
                val fit = textPaint.breakText(quietText, consumedCharacters, quietText.length, true, widthPx, null)
                if (fit >= remaining) {
                    visibleMarkdown = quietText
                    consumedCharacters = quietText.length
                }
            }
        }

        MarkdownText(
            markdown = visibleMarkdown,
            modifier = Modifier.fillMaxWidth(),
            tableCellMinWidthDp = tableCellMinWidthDp,
            animateNewContent = true,
        )
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    tableCellMinWidthDp: Int = 112,
    animateNewContent: Boolean = false,
) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onSurface
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(ImagesPlugin.create())
            .build()
    }
    val colorInt = color.toArgb()
    val blocks = remember(markdown) { splitMarkdownTables(markdown) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        Column(Modifier.fillMaxWidth()) {
            blocks.forEachIndexed { index, block ->
                key(index, block.isTable) {
                    if (block.isTable) Spacer(Modifier.height(12.dp))
                    val blockModifier = if (block.isTable) {
                        Modifier.fillMaxWidth()
                    } else Modifier.fillMaxWidth()
                    if (block.isTable) {
                        val tableWidth = maxOf(availableWidth, (tableCellMinWidthDp.coerceIn(64, 240) * block.columnCount).dp)
                        Row(blockModifier.horizontalScroll(rememberScrollState())) {
                            AndroidMarkdownView(
                                markdown = block.markdown,
                                markwon = markwon,
                                color = colorInt,
                                modifier = Modifier.width(tableWidth),
                                animateNewContent = animateNewContent,
                            )
                        }
                    } else {
                        AndroidMarkdownView(
                            markdown = block.markdown,
                            markwon = markwon,
                            color = colorInt,
                            modifier = blockModifier,
                            animateNewContent = animateNewContent,
                        )
                    }
                    if (block.isTable) Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
private fun AndroidMarkdownView(
    markdown: String,
    markwon: Markwon,
    color: Int,
    modifier: Modifier,
    animateNewContent: Boolean,
) {
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            TextView(viewContext).apply {
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setLineSpacing(0f, 1.25f)
                includeFontPadding = false
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { textView ->
            textView.setTextColor(color)
            if (textView.getTag(R.id.markdown_rendered_source) != markdown) {
                val previous = textView.text as? Spanned
                val previousText = previous?.toString().orEmpty()
                val rendered = SpannableStringBuilder(markwon.toMarkdown(markdown))
                val commonPrefix = commonPrefixLength(previousText, rendered)
                if (animateNewContent && previous != null) {
                    previous.getSpans(0, previous.length, FadeInSpan::class.java).forEach { span ->
                        val start = previous.getSpanStart(span)
                        val end = previous.getSpanEnd(span)
                        if (start >= 0 && end in (start + 1)..commonPrefix && span.animator?.isRunning == true) {
                            rendered.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        } else span.animator?.cancel()
                    }
                } else if (previous != null) {
                    previous.getSpans(0, previous.length, FadeInSpan::class.java).forEach { it.animator?.cancel() }
                }

                val newStart = commonPrefix.coerceAtMost(rendered.length)
                val newSpan = if (animateNewContent && rendered.length > newStart) FadeInSpan().also {
                    rendered.setSpan(it, newStart, rendered.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else null
                markwon.setParsedMarkdown(textView, rendered)
                textView.setTag(R.id.markdown_rendered_source, markdown)
                newSpan?.let { span ->
                    span.animator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 170L
                        addUpdateListener { animation ->
                            span.alpha = animation.animatedValue as Float
                            textView.invalidate()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                val current = textView.text as? Spannable
                                if (current != null && current.getSpanStart(span) >= 0) {
                                    current.removeSpan(span)
                                    textView.invalidate()
                                }
                            }
                        })
                        start()
                    }
                }
            }
        },
    )
}

private class FadeInSpan : android.text.style.CharacterStyle(), android.text.style.UpdateAppearance {
    var alpha: Float = 0f
    var animator: ValueAnimator? = null

    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.alpha = (textPaint.alpha * alpha).roundToInt().coerceIn(0, 255)
    }
}

private data class MarkdownBlock(val markdown: String, val isTable: Boolean, val columnCount: Int = 1)

private fun splitMarkdownTables(markdown: String): List<MarkdownBlock> {
    if (markdown.isBlank()) return emptyList()
    val lines = markdown.split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    val plain = StringBuilder()
    var fenced = false
    var fenceMarker = ""
    var index = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            blocks += MarkdownBlock(plain.toString(), isTable = false)
            plain.clear()
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()
        val marker = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> ""
        }
        if (marker.isNotEmpty()) {
            if (!fenced) {
                fenced = true
                fenceMarker = marker
            } else if (marker == fenceMarker) {
                fenced = false
                fenceMarker = ""
            }
        }

        if (!fenced && isTableHeader(line) && index + 1 < lines.size && isTableDivider(lines[index + 1])) {
            flushPlain()
            val table = StringBuilder().append(line).append('\n').append(lines[index + 1])
            val columns = cellCount(line)
            index += 2
            while (index < lines.size && lines[index].isNotBlank() && lines[index].contains('|')) {
                table.append('\n').append(lines[index])
                index++
            }
            blocks += MarkdownBlock(table.toString(), isTable = true, columnCount = columns)
            continue
        }

        plain.append(line)
        if (index < lines.lastIndex) plain.append('\n')
        index++
    }
    flushPlain()
    return blocks
}

private fun isTableHeader(line: String): Boolean = line.contains('|') && cellCount(line) >= 2

private fun isTableDivider(line: String): Boolean {
    val cells = line.trim().trim('|').split('|').map(String::trim)
    return cells.size >= 2 && cells.all { it.matches(Regex(":?-{3,}:?")) }
}

private fun cellCount(line: String): Int = line.trim().trim('|').split('|').size

private fun commonPrefixLength(previous: String, current: CharSequence): Int {
    val limit = minOf(previous.length, current.length)
    var index = 0
    while (index < limit && previous[index] == current[index]) index++
    return index
}
