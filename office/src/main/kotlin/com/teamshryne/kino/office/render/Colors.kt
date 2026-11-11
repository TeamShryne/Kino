package com.teamshryne.kino.office.render

/** Small colour helpers so every renderer derives shades the same way. */
object Colors {

    fun rgb(hex: String): Triple<Int, Int, Int> {
        val clean = hex.removePrefix("#").trim()
        val value = clean.toLongOrNull(16) ?: 0L
        return when (clean.length) {
            3 -> Triple(
                (((value shr 8) and 0xF) * 17).toInt(),
                (((value shr 4) and 0xF) * 17).toInt(),
                ((value and 0xF) * 17).toInt(),
            )

            else -> Triple(
                ((value shr 16) and 0xFF).toInt(),
                ((value shr 8) and 0xFF).toInt(),
                (value and 0xFF).toInt(),
            )
        }
    }

    fun hex(r: Int, g: Int, b: Int): String =
        "#%02X%02X%02X".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

    /** Mixes [a] and [b]; [ratio] 0 keeps [a], 1 keeps [b]. */
    fun mix(a: String, b: String, ratio: Double): String {
        val (ar, ag, ab) = rgb(a)
        val (br, bg, bb) = rgb(b)
        val t = ratio.coerceIn(0.0, 1.0)
        return hex(
            (ar + (br - ar) * t).toInt(),
            (ag + (bg - ag) * t).toInt(),
            (ab + (bb - ab) * t).toInt(),
        )
    }

    fun lighten(hex: String, amount: Double): String = mix(hex, "#FFFFFF", amount)

    fun darken(hex: String, amount: Double): String = mix(hex, "#000000", amount)

    fun withAlpha(hex: String, alpha: Double): String {
        val (r, g, b) = rgb(hex)
        return "rgba($r,$g,$b,${"%.3f".format(alpha.coerceIn(0.0, 1.0))})"
    }

    /** True when the colour is dark enough that light text reads better on top. */
    fun isDark(hex: String): Boolean {
        val (r, g, b) = rgb(hex)
        return (0.299 * r + 0.587 * g + 0.114 * b) < 140
    }

    /** A readable text colour for a coloured background. */
    fun onColor(hex: String): String = if (isDark(hex)) "#FFFFFF" else "#1A1A1A"
}
