package com.teamshryne.kino.office.render

import com.teamshryne.kino.office.model.ChartBlock
import com.teamshryne.kino.office.model.ChartKind
import com.teamshryne.kino.office.theme.OfficeTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws charts as inline SVG.
 *
 * SVG keeps charts crisp in PDF, self-contained in HTML and EPUB, and free of
 * extra dependencies. The office formats that cannot embed SVG (Word, Excel,
 * PowerPoint) get a data table or a native shape treatment instead.
 */
object SvgChart {

    private const val WIDTH = 700.0
    private const val HEIGHT = 320.0

    fun render(chart: ChartBlock, theme: OfficeTheme): String {
        val title = chart.title
        val top = if (title != null) 38.0 else 14.0
        return when (chart.kind) {
            ChartKind.PIE -> pie(chart, theme, top)
            ChartKind.LINE, ChartKind.AREA -> line(chart, theme, top)
            ChartKind.BAR -> horizontalBars(chart, theme, top)
            ChartKind.COLUMN -> columns(chart, theme, top)
        }
    }

    private fun palette(theme: OfficeTheme, count: Int): List<String> {
        val anchors = listOf(
            theme.accent,
            Colors.lighten(theme.accent, 0.55),
            Colors.darken(theme.accent, 0.28),
            Colors.mix(theme.accent, "#E17055", 0.55),
            Colors.mix(theme.accent, "#4E7CD9", 0.5),
            Colors.lighten(Colors.darken(theme.accent, 0.12), 0.4),
        )
        return (0 until max(1, count)).map { anchors[it % anchors.size] }
    }

    private fun frame(theme: OfficeTheme, title: String?, body: String, viewHeight: Double = HEIGHT): String = buildString {
        append("<svg class=\"kino-chart\" viewBox=\"0 0 $WIDTH $viewHeight\" role=\"img\" ")
        append("preserveAspectRatio=\"xMidYMid meet\" xmlns=\"http://www.w3.org/2000/svg\">")
        if (title != null) {
            append("<text x=\"0\" y=\"18\" font-family=\"${theme.sansStack}\" font-size=\"13\" font-weight=\"600\" fill=\"${theme.ink}\">")
            append(escapeXml(title))
            append("</text>")
        }
        append(body)
        append("</svg>")
    }

    private fun columns(chart: ChartBlock, theme: OfficeTheme, top: Double): String {
        val categories = chart.categories
        val series = chart.series
        if (categories.isEmpty() || series.isEmpty()) return frame(theme, chart.title, "", 80.0)
        val colors = palette(theme, series.size)
        val left = 54.0
        val right = WIDTH - 14.0
        val bottom = HEIGHT - 44.0
        val plotHeight = bottom - top
        val values = series.flatMap { it.values }
        val maxValue = values.maxOrNull() ?: 1.0
        val minValue = min(0.0, values.minOrNull() ?: 0.0)
        val span = (maxValue - minValue).takeIf { abs(it) > 1e-9 } ?: 1.0
        val groupWidth = (right - left) / categories.size
        val barCount = series.size
        val barGap = 3.0
        val barWidth = max(4.0, (groupWidth * 0.72 - barGap * (barCount - 1)) / barCount)

        val body = StringBuilder()
        body.append(gridlines(theme, left, right, top, bottom, minValue, maxValue))
        categories.forEachIndexed { index, category ->
            val groupStart = left + groupWidth * index + (groupWidth - (barWidth * barCount + barGap * (barCount - 1))) / 2
            series.forEachIndexed { seriesIndex, seriesData ->
                val value = seriesData.values.getOrNull(index) ?: 0.0
                val barHeight = ((value - minValue) / span) * plotHeight
                val x = groupStart + seriesIndex * (barWidth + barGap)
                val y = bottom - barHeight
                body.append(
                    "<rect x=\"${round(x)}\" y=\"${round(y)}\" width=\"${round(barWidth)}\" height=\"${round(max(barHeight, 0.0))}\" rx=\"2\" fill=\"${colors[seriesIndex]}\">" +
                        "<title>${escapeXml(seriesData.name)} · ${escapeXml(category)}: ${format(value)}</title></rect>",
                )
            }
            body.append(
                "<text x=\"${round(left + groupWidth * index + groupWidth / 2)}\" y=\"${round(bottom + 18)}\" " +
                    "text-anchor=\"middle\" font-family=\"${theme.sansStack}\" font-size=\"11\" fill=\"${theme.muted}\">${escapeXml(category)}</text>",
            )
        }
        body.append(axis(theme, left, right, top, bottom))
        body.append(legend(theme, series.map { it.name }, colors, bottom + 34.0))
        return frame(theme, chart.title, body.toString())
    }

    private fun horizontalBars(chart: ChartBlock, theme: OfficeTheme, top: Double): String {
        val categories = chart.categories
        val series = chart.series
        if (categories.isEmpty() || series.isEmpty()) return frame(theme, chart.title, "", 80.0)
        val colors = palette(theme, series.size)
        val labelWidth = 120.0
        val right = WIDTH - 16.0
        val bottom = HEIGHT - (if (series.size > 1) 44.0 else 20.0)
        val plotHeight = bottom - top
        val groupHeight = plotHeight / categories.size
        val maxValue = series.flatMap { it.values }.maxOrNull()?.takeIf { abs(it) > 1e-9 } ?: 1.0
        val barHeight = max(8.0, (groupHeight * 0.66 - 3 * (series.size - 1)) / series.size)

        val body = StringBuilder()
        categories.forEachIndexed { index, category ->
            val groupTop = top + groupHeight * index
            body.append(
                "<text x=\"0\" y=\"${round(groupTop + groupHeight / 2 + 4)}\" font-family=\"${theme.sansStack}\" font-size=\"11\" fill=\"${theme.inkSoft}\">" +
                    "${escapeXml(truncate(category, 20))}</text>",
            )
            series.forEachIndexed { seriesIndex, seriesData ->
                val value = seriesData.values.getOrNull(index) ?: 0.0
                val width = (abs(value) / maxValue) * (right - labelWidth - 46)
                val y = groupTop + (groupHeight - (barHeight * series.size + 3 * (series.size - 1))) / 2 + seriesIndex * (barHeight + 3)
                body.append(
                    "<rect x=\"${round(labelWidth)}\" y=\"${round(y)}\" width=\"${round(max(width, 1.0))}\" height=\"${round(barHeight)}\" rx=\"2\" fill=\"${colors[seriesIndex]}\">" +
                        "<title>${escapeXml(seriesData.name)} · ${escapeXml(category)}: ${format(value)}</title></rect>",
                )
                body.append(
                    "<text x=\"${round(labelWidth + max(width, 1.0) + 6)}\" y=\"${round(y + barHeight - 3)}\" " +
                        "font-family=\"${theme.sansStack}\" font-size=\"10.5\" fill=\"${theme.muted}\">${escapeXml(format(value))}</text>",
                )
            }
        }
        body.append(legend(theme, series.map { it.name }, colors, bottom + 16.0))
        return frame(theme, chart.title, body.toString())
    }

    private fun line(chart: ChartBlock, theme: OfficeTheme, top: Double): String {
        val categories = chart.categories
        val series = chart.series
        if (categories.isEmpty() || series.isEmpty()) return frame(theme, chart.title, "", 80.0)
        val colors = palette(theme, series.size)
        val left = 54.0
        val right = WIDTH - 14.0
        val bottom = HEIGHT - 44.0
        val plotHeight = bottom - top
        val values = series.flatMap { it.values }
        val maxValue = values.maxOrNull() ?: 1.0
        val minValue = min(0.0, values.minOrNull() ?: 0.0)
        val span = (maxValue - minValue).takeIf { abs(it) > 1e-9 } ?: 1.0

        val body = StringBuilder()
        body.append(gridlines(theme, left, right, top, bottom, minValue, maxValue))
        val step = if (categories.size > 1) (right - left) / (categories.size - 1) else 0.0
        series.forEachIndexed { seriesIndex, seriesData ->
            val points = seriesData.values.mapIndexed { index, value ->
                val x = left + step * index
                val y = bottom - ((value - minValue) / span) * plotHeight
                "${round(x)},${round(y)}"
            }
            if (chart.kind == ChartKind.AREA && points.isNotEmpty()) {
                val areaPoints = (listOf("${round(left)},${round(bottom)}") + points + "${round(left + step * (points.size - 1))},${round(bottom)}")
                body.append("<polygon points=\"${areaPoints.joinToString(" ")}\" fill=\"${Colors.withAlpha(colors[seriesIndex], 0.18)}\" />")
            }
            body.append(
                "<polyline points=\"${points.joinToString(" ")}\" fill=\"none\" stroke=\"${colors[seriesIndex]}\" stroke-width=\"2.2\" stroke-linejoin=\"round\" stroke-linecap=\"round\" />",
            )
            points.forEachIndexed { index, point ->
                val (x, y) = point.split(",").map { it.toDouble() }
                val label = seriesData.values.getOrNull(index) ?: 0.0
                body.append(
                    "<circle cx=\"${round(x)}\" cy=\"${round(y)}\" r=\"3\" fill=\"${colors[seriesIndex]}\" stroke=\"${theme.background}\" stroke-width=\"1.2\">" +
                        "<title>${escapeXml(seriesData.name)} · ${escapeXml(categories.getOrElse(index) { "" })}: ${format(label)}</title></circle>",
                )
            }
        }
        categories.forEachIndexed { index, category ->
            val x = if (categories.size > 1) left + step * index else (left + right) / 2
            body.append(
                "<text x=\"${round(x)}\" y=\"${round(bottom + 18)}\" text-anchor=\"middle\" font-family=\"${theme.sansStack}\" font-size=\"11\" fill=\"${theme.muted}\">" +
                    "${escapeXml(category)}</text>",
            )
        }
        body.append(axis(theme, left, right, top, bottom))
        body.append(legend(theme, series.map { it.name }, colors, bottom + 34.0))
        return frame(theme, chart.title, body.toString())
    }

    private fun pie(chart: ChartBlock, theme: OfficeTheme, top: Double): String {
        val series = chart.series.firstOrNull() ?: return frame(theme, chart.title, "", 80.0)
        val values = series.values
        if (values.isEmpty() || values.sum() <= 0.0) return frame(theme, chart.title, "", 80.0)
        val colors = palette(theme, values.size)
        val centerX = WIDTH * 0.32
        val centerY = top + (HEIGHT - top - 24) / 2
        val radius = min(centerY - top - 8, 110.0)
        var angle = -Math.PI / 2
        val total = values.sum()
        val body = StringBuilder()
        values.forEachIndexed { index, value ->
            val sweep = (value / total) * Math.PI * 2
            val end = angle + sweep
            val x1 = centerX + radius * kotlin.math.cos(angle)
            val y1 = centerY + radius * kotlin.math.sin(angle)
            val x2 = centerX + radius * kotlin.math.cos(end)
            val y2 = centerY + radius * kotlin.math.sin(end)
            val large = if (sweep > Math.PI) 1 else 0
            body.append(
                "<path d=\"M ${round(centerX)} ${round(centerY)} L ${round(x1)} ${round(y1)} " +
                    "A ${round(radius)} ${round(radius)} 0 $large 1 ${round(x2)} ${round(y2)} Z\" " +
                    "fill=\"${colors[index]}\" stroke=\"${theme.background}\" stroke-width=\"1.4\">" +
                    "<title>${escapeXml(chart.categories.getOrElse(index) { "Slice ${index + 1}" })}: ${format(value)}</title></path>",
            )
            angle = end
        }
        val legendX = WIDTH * 0.62
        var legendY = top + 22.0
        chart.categories.forEachIndexed { index, category ->
            if (index < values.size) {
                body.append("<rect x=\"${round(legendX)}\" y=\"${round(legendY - 9)}\" width=\"10\" height=\"10\" rx=\"2\" fill=\"${colors[index]}\" />")
                body.append(
                    "<text x=\"${round(legendX + 16)}\" y=\"${round(legendY)}\" font-family=\"${theme.sansStack}\" font-size=\"11.5\" fill=\"${theme.inkSoft}\">" +
                        "${escapeXml(truncate(category, 26))}</text>",
                )
                body.append(
                    "<text x=\"${round(legendX + 16)}\" y=\"${round(legendY + 14)}\" font-family=\"${theme.sansStack}\" font-size=\"10.5\" fill=\"${theme.muted}\">" +
                        "${escapeXml(format(values[index]))} · ${"%.1f".format(values[index] / total * 100)}%</text>",
                )
                legendY += 34.0
            }
        }
        return frame(theme, chart.title, body.toString())
    }

    private fun gridlines(theme: OfficeTheme, left: Double, right: Double, top: Double, bottom: Double, minValue: Double, maxValue: Double): String {
        val builder = StringBuilder()
        val steps = 4
        for (step in 0..steps) {
            val value = minValue + (maxValue - minValue) * step / steps
            val y = bottom - (bottom - top) * step / steps
            builder.append(
                "<line x1=\"${round(left)}\" y1=\"${round(y)}\" x2=\"${round(right)}\" y2=\"${round(y)}\" stroke=\"${theme.border}\" stroke-width=\"0.8\" " +
                    "stroke-dasharray=\"${if (step == 0) "0" else "2 3"}\" />",
            )
            builder.append(
                "<text x=\"${round(left - 8)}\" y=\"${round(y + 3.5)}\" text-anchor=\"end\" font-family=\"${theme.sansStack}\" font-size=\"10\" fill=\"${theme.muted}\">" +
                    "${escapeXml(format(value))}</text>",
            )
        }
        return builder.toString()
    }

    private fun axis(theme: OfficeTheme, left: Double, right: Double, top: Double, bottom: Double): String =
        "<line x1=\"${round(left)}\" y1=\"${round(bottom)}\" x2=\"${round(right)}\" y2=\"${round(bottom)}\" stroke=\"${theme.border}\" stroke-width=\"1\" />" +
            "<line x1=\"${round(left)}\" y1=\"${round(top)}\" x2=\"${round(left)}\" y2=\"${round(bottom)}\" stroke=\"${theme.border}\" stroke-width=\"1\" />"

    private fun legend(theme: OfficeTheme, names: List<String>, colors: List<String>, y: Double): String {
        if (names.size <= 1) return ""
        var x = 0.0
        val builder = StringBuilder()
        names.forEachIndexed { index, name ->
            builder.append("<rect x=\"${round(x)}\" y=\"${round(y - 8)}\" width=\"10\" height=\"10\" rx=\"2\" fill=\"${colors[index % colors.size]}\" />")
            builder.append(
                "<text x=\"${round(x + 15)}\" y=\"${round(y)}\" font-family=\"${theme.sansStack}\" font-size=\"11\" fill=\"${theme.inkSoft}\">" +
                    "${escapeXml(truncate(name, 22))}</text>",
            )
            x += 22 + truncate(name, 22).length * 6.1
        }
        return builder.toString()
    }

    private fun format(value: Double): String = when {
        abs(value) >= 1_000_000 -> "%.1fM".format(value / 1_000_000)
        abs(value) >= 1_000 -> "%.1fK".format(value / 1_000)
        abs(value) >= 100 -> "%.0f".format(value)
        abs(value) >= 1 -> "%.1f".format(value)
        else -> "%.2f".format(value)
    }

    private fun truncate(value: String, limit: Int): String = if (value.length <= limit) value else value.take(limit - 1) + "…"

    private fun round(value: Double): String = "%.2f".format(value).trimEnd('0').trimEnd('.')

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
