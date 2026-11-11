package com.teamshryne.kino.office.xml

/** XML helpers shared by the OOXML and OpenDocument writers. */
object Xml {

    fun escape(value: String): String = buildString(value.length + 16) {
        var index = 0
        while (index < value.length) {
            when (val c = value[index]) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> {
                    // Strip characters XML 1.0 cannot represent, otherwise Word
                    // and LibreOffice reject the whole package.
                    if (c.code in 0x00..0x08 || c.code == 0x0B || c.code in 0x0C..0x1F) {
                        append(' ')
                    } else {
                        append(c)
                    }
                }
            }
            index++
        }
    }

    fun attribute(value: String): String = escape(value)

    /** Strips characters that are illegal in XML 1.0 documents. */
    fun sanitize(value: String): String {
        val builder = StringBuilder(value.length)
        value.forEach { c ->
            if (c.code in 0x00..0x08 || c.code == 0x0B || c.code in 0x0C..0x1F) builder.append(' ') else builder.append(c)
        }
        return builder.toString()
    }
}
