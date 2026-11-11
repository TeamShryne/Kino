package com.teamshryne.kino.office.render

import com.teamshryne.kino.office.model.Orientation
import com.teamshryne.kino.office.model.PageSize
import kotlin.math.roundToInt

/**
 * Page geometry shared by the renderers and the Android PDF writer.
 *
 * Both sides use CSS pixels at 96 dpi so the HTML that the renderer draws and
 * the slices the app prints always agree on where a page starts and ends.
 */
class PageGeometry(val size: PageSize = PageSize.A4, val orientation: Orientation = Orientation.PORTRAIT) {

    val widthPx: Int
    val heightPx: Int
    val widthPt: Double
    val heightPt: Double

    init {
        val landscape = orientation == Orientation.LANDSCAPE
        val w = if (landscape) size.heightPt else size.widthPt
        val h = if (landscape) size.widthPt else size.heightPt
        widthPt = w
        heightPt = h
        widthPx = (w * PX_PER_PT).roundToInt()
        heightPx = (h * PX_PER_PT).roundToInt()
    }

    val marginTopPx: Int = px(20.0)
    val marginBottomPx: Int = px(18.0)
    val marginLeftPx: Int = px(18.0)
    val marginRightPx: Int = px(18.0)

    val contentWidthPx: Int = widthPx - marginLeftPx - marginRightPx
    val contentHeightPx: Int = heightPx - marginTopPx - marginBottomPx

    private fun px(mm: Double): Int = (mm * MM_TO_PX).roundToInt()

    companion object {
        const val PX_PER_PT = 96.0 / 72.0
        const val MM_TO_PX = 96.0 / 25.4

        /** A4 portrait in CSS pixels, matching the Android WebView print pipeline. */
        val A4 = PageGeometry(PageSize.A4, Orientation.PORTRAIT)
    }
}
