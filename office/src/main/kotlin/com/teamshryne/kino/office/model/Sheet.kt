package com.teamshryne.kino.office.model

/** A single spreadsheet sheet: typed columns, row values, and light sheet metadata. */
data class SheetSpec(
    val name: String,
    val columns: List<SheetColumn>,
    val rows: List<List<SheetCell>>,
    val freeze: String? = null,
    val autoFilter: Boolean = false,
    val notes: Map<String, String> = emptyMap(),
    val tabColor: String? = null,
    val totalRow: Boolean = false,
)

data class SheetColumn(
    val header: String,
    val width: Double? = null,
    val type: ColumnType = ColumnType.TEXT,
    val format: String? = null,
)

/** A cell value with optional presentation overrides. */
sealed interface SheetCell {
    val format: String?
    val bold: Boolean
    val align: ColumnAlignment?

    data class Text(val value: String, override val format: String? = null, override val bold: Boolean = false, override val align: ColumnAlignment? = null) : SheetCell

    data class Number(val value: Double, override val format: String? = null, override val bold: Boolean = false, override val align: ColumnAlignment? = null) : SheetCell

    data class Bool(val value: Boolean, override val format: String? = null, override val bold: Boolean = false, override val align: ColumnAlignment? = null) : SheetCell

    /** A formula such as `=SUM(B2:B9)`; the leading `=` is optional. */
    data class Formula(val expression: String, override val format: String? = null, override val bold: Boolean = false, override val align: ColumnAlignment? = null) : SheetCell

    data object Blank : SheetCell {
        override val format: String? get() = null
        override val bold: Boolean get() = false
        override val align: ColumnAlignment? get() = null
    }
}

/** A workbook: an ordered list of sheets plus workbook-level metadata. */
data class WorkbookSpec(
    val title: String = "Workbook",
    val theme: String = "corporate",
    val sheets: List<SheetSpec> = emptyList(),
)
