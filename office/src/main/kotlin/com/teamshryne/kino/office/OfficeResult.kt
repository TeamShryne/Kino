package com.teamshryne.kino.office

/** A problem found while parsing or rendering a payload. */
data class ReportIssue(
    val severity: Severity,
    val message: String,
    val path: String? = null,
    val fixable: Boolean = false,
) {
    enum class Severity { WARNING, ERROR }

    fun describe(): String = buildString {
        if (path != null) append(path).append(": ")
        append(message)
    }
}

/** Raised when a payload cannot be understood well enough to build a file. */
class SpecException(message: String, val path: String? = null) : Exception(message)

/** The outcome of a successful office build. */
data class OfficeResult(
    val format: OfficeFormat,
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
    val title: String,
    val summary: String,
    val issues: List<ReportIssue> = emptyList(),
    val sizeBytes: Int = bytes.size,
) {
    val warnings: List<ReportIssue> get() = issues.filter { it.severity == ReportIssue.Severity.WARNING }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
