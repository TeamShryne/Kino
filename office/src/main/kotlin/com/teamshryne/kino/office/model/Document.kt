package com.teamshryne.kino.office.model

/** Paper sizes in PostScript points (72 pt = 1 inch). */
enum class PageSize(val id: String, val label: String, val widthPt: Double, val heightPt: Double) {
    A4("a4", "A4", 595.28, 841.89),
    LETTER("letter", "US Letter", 612.0, 792.0),
    LEGAL("legal", "US Legal", 612.0, 1008.0),
    A3("a3", "A3", 841.89, 1190.55),
    ;

    val isWide: Boolean get() = widthPt > heightPt
}

enum class Orientation { PORTRAIT, LANDSCAPE }

/** The kinds of content block a document can contain. */
sealed interface Block {
    /** Extra guidance for renderers that support it (for example a caption style). */
    val caption: String? get() = null
}

data class HeadingBlock(
    val level: Int,
    val text: String,
    val numbered: Boolean = false,
    override val caption: String? = null,
) : Block

data class ParagraphBlock(val text: String) : Block

data class ListBlock(
    val items: List<String>,
    val ordered: Boolean = false,
    val startAt: Int = 1,
) : Block

data class TableBlock(
    val columns: List<TableColumn>,
    val rows: List<List<String>>,
    val caption: String? = null,
    val zebra: Boolean = true,
    val alignments: List<ColumnAlignment> = emptyList(),
) : Block {
    val effectiveAlignments: List<ColumnAlignment>
        get() = columns.indices.map { index ->
            alignments.getOrNull(index) ?: columns[index].alignment
        }
}

data class TableColumn(
    val header: String,
    val width: Double? = null,
    val alignment: ColumnAlignment = ColumnAlignment.LEFT,
    val type: ColumnType = ColumnType.TEXT,
)

enum class ColumnAlignment { LEFT, CENTER, RIGHT }

enum class ColumnType { TEXT, NUMBER, CURRENCY, PERCENT, DATE, BOOL }

data class KeyValueBlock(val items: List<KeyValueItem>, val columns: Int = 2) : Block

data class KeyValueItem(val label: String, val value: String, val hint: String? = null)

data class CalloutBlock(
    val kind: CalloutKind,
    val title: String?,
    val text: String,
) : Block

enum class CalloutKind { INFO, NOTE, WARNING, SUCCESS, DANGER }

data class QuoteBlock(val text: String, val attribution: String?) : Block

data class CodeBlock(val text: String, val language: String?) : Block

data class ImageBlock(
    val source: String,
    val caption: String? = null,
    val alt: String? = null,
    val maxWidthPercent: Int = 100,
) : Block

data class StatBlock(val items: List<StatItem>) : Block

data class StatItem(val label: String, val value: String, val delta: String? = null, val positive: Boolean? = null)

data class ChartBlock(
    val kind: ChartKind,
    val categories: List<String>,
    val series: List<ChartSeries>,
    val title: String? = null,
    val xLabel: String? = null,
    val yLabel: String? = null,
    val stacked: Boolean = false,
) : Block

enum class ChartKind { BAR, COLUMN, LINE, AREA, PIE }

data class ChartSeries(val name: String, val values: List<Double>)

data object DividerBlock : Block

data object PageBreakBlock : Block

data class SpacerBlock(val size: Int = 1) : Block

data object TocBlock : Block

/** A complete flow document: title page material plus an ordered block list. */
data class DocumentSpec(
    val title: String = "Document",
    val subtitle: String? = null,
    val author: String? = null,
    val date: String? = null,
    val theme: String = "corporate",
    val accent: String? = null,
    val cover: Boolean = true,
    val toc: Boolean = false,
    val header: String? = null,
    val footer: String? = null,
    val pageNumbers: Boolean = true,
    val pageSize: PageSize = PageSize.A4,
    val orientation: Orientation = Orientation.PORTRAIT,
    val blocks: List<Block> = emptyList(),
)
