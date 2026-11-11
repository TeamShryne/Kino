package com.teamshryne.kino.ui.components

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.teamshryne.kino.R
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.latex.JLatexMathTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
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
    val colorInt = color.toArgb()
    val markwon = rememberMarkwon(context, MaterialTheme.colorScheme)
    val segments = remember(markdown) { MarkdownParser.segments(markdown) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            segments.forEachIndexed { index, segment ->
                key(index, segment::class) {
                    when (segment) {
                        is MarkdownSegment.Prose -> AndroidMarkdownView(
                            markdown = segment.markdown,
                            markwon = markwon,
                            color = colorInt,
                            modifier = Modifier.fillMaxWidth(),
                            animateNewContent = animateNewContent,
                        )
                        is MarkdownSegment.Math -> AndroidMarkdownView(
                            markdown = "$$\n${segment.latex}\n$$",
                            markwon = markwon,
                            color = colorInt,
                            modifier = Modifier.fillMaxWidth(),
                            animateNewContent = animateNewContent,
                        )
                        is MarkdownSegment.Code -> CodeBlockCard(
                            language = segment.language,
                            code = segment.code,
                        )
                        is MarkdownSegment.Table -> {
                            val tableWidth = maxOf(availableWidth, (tableCellMinWidthDp.coerceIn(64, 240) * segment.columnCount).dp)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                AndroidMarkdownView(
                                    markdown = segment.markdown,
                                    markwon = markwon,
                                    color = colorInt,
                                    modifier = Modifier.width(tableWidth),
                                    animateNewContent = animateNewContent,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Builds the Markwon instance used for every message.
 *
 * The instance is rebuilt whenever the color scheme changes so that code, quotes, links and
 * formulas always match the light or dark palette.
 */
@Composable
private fun rememberMarkwon(context: Context, scheme: ColorScheme): Markwon {
    val metrics = context.resources.displayMetrics
    val inlineTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, metrics)
    val blockTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 19f, metrics)
    val textColor = scheme.onSurface.toArgb()
    val codeBackground = scheme.surfaceVariant.toArgb()
    val linkColor = scheme.primary.toArgb()
    val mutedColor = scheme.onSurfaceVariant.toArgb()
    val ruleColor = scheme.outlineVariant.toArgb()
    return remember(context, textColor, codeBackground, linkColor, mutedColor, ruleColor) {
        Markwon.builder(context)
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(ImagesPlugin.create())
            .usePlugin(MarkdownThemePlugin(codeBackground, textColor, linkColor, mutedColor, ruleColor))
            .usePlugin(latexPlugin(inlineTextSize, blockTextSize, textColor))
            .build()
    }
}

/** Keeps the markdown colors in sync with the app theme. */
private class MarkdownThemePlugin(
    private val codeBackground: Int,
    private val codeText: Int,
    private val linkColor: Int,
    private val mutedColor: Int,
    private val ruleColor: Int,
) : AbstractMarkwonPlugin() {
    override fun configureTheme(builder: MarkwonTheme.Builder) {
        builder
            .codeBackgroundColor(codeBackground)
            .codeTextColor(codeText)
            .codeBlockBackgroundColor(codeBackground)
            .codeBlockTextColor(codeText)
            .linkColor(linkColor)
            .blockQuoteColor(mutedColor)
            .thematicBreakColor(ruleColor)
            .headingBreakColor(ruleColor)
    }
}

/** Renders inline (`$x$`) and display (`$$ ... $$`) formulas with the full LaTeX engine. */
private fun latexPlugin(inlineTextSize: Float, blockTextSize: Float, color: Int): JLatexMathPlugin =
    JLatexMathPlugin.create(inlineTextSize, blockTextSize) { builder ->
        builder.inlinesEnabled(true)
        builder.blocksEnabled(true)
        builder.theme()
            .textColor(color)
            .inlineTextColor(color)
            .blockTextColor(color)
            .blockPadding(JLatexMathTheme.Padding.symmetric(12, 0))
        builder.errorHandler { latex, _ ->
            RawLatexDrawable(latex, inlineTextSize, color)
        }
    }

/** Draws the raw source of a formula that could not be typeset. */
private class RawLatexDrawable(
    private val source: String,
    private val textSize: Float,
    private val color: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        this.color = color
        typeface = Typeface.MONOSPACE
    }
    private val inset = (textSize * 0.35f).toInt().coerceAtLeast(2)

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val baseline = bounds.height() - inset - paint.fontMetrics.descent
        canvas.drawText(source, inset.toFloat(), baseline, paint)
    }

    override fun getIntrinsicWidth(): Int = paint.measureText(source).toInt() + inset * 2

    override fun getIntrinsicHeight(): Int =
        (paint.fontMetrics.descent - paint.fontMetrics.ascent).toInt() + inset * 2

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
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
            val sourceChanged = textView.getTag(R.id.markdown_rendered_source) != markdown
            // Formulas are baked into the rendered text, so a theme change has to re-render as well.
            val colorChanged = textView.getTag(R.id.markdown_rendered_color) != color
            if (sourceChanged || colorChanged) {
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
                textView.setTag(R.id.markdown_rendered_color, color)
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

private fun commonPrefixLength(previous: String, current: CharSequence): Int {
    val limit = minOf(previous.length, current.length)
    var index = 0
    while (index < limit && previous[index] == current[index]) index++
    return index
}
