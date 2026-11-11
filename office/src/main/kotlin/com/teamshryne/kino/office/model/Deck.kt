package com.teamshryne.kino.office.model

/** Slide layout families supported by the deck renderer. */
enum class SlideLayout { TITLE, SECTION, CONTENT, TWO_COLUMN, QUOTE, METRICS }

data class SlideSpec(
    val layout: SlideLayout = SlideLayout.CONTENT,
    val title: String? = null,
    val subtitle: String? = null,
    val blocks: List<Block> = emptyList(),
    val columns: List<List<Block>> = emptyList(),
    val notes: String? = null,
)

/** A presentation: a title, theme, and ordered slides. */
data class DeckSpec(
    val title: String = "Presentation",
    val subtitle: String? = null,
    val author: String? = null,
    val theme: String = "corporate",
    val accent: String? = null,
    val slides: List<SlideSpec> = emptyList(),
)
