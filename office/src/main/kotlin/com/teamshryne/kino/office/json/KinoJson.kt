package com.teamshryne.kino.office.json

/**
 * A dependency-free JSON reader for the office document pipeline.
 *
 * The Android platform ships `org.json`, but the office module is a plain JVM
 * library so it can be unit-tested off-device. Pulling in a third-party JSON
 * artifact would collide with the platform classes at dex time, so the parser
 * lives here instead. It is small on purpose: it reads the structures the
 * document pipeline needs and reports errors with a path so the model can be
 * told exactly what to fix.
 */
sealed class JsonValue {
    object Null : JsonValue()

    data class Bool(val value: Boolean) : JsonValue()

    data class Num(val value: Double) : JsonValue()

    data class Str(val value: String) : JsonValue()

    data class Arr(val items: List<JsonValue>) : JsonValue()

    class Obj(val entries: Map<String, JsonValue>) : JsonValue() {
        operator fun get(key: String): JsonValue? = entries[key]
        fun has(key: String): Boolean = entries.containsKey(key)
        override fun toString(): String = "JsonValue.Obj(${entries.keys.joinToString()})"
    }
}

/** Thrown when the model's document payload is not valid JSON. */
class JsonException(message: String) : Exception(message)

object KinoJson {

    /** Parses [text] into a [JsonValue], throwing [JsonException] with a byte offset. */
    fun parse(text: String): JsonValue {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) reader.fail("Unexpected trailing content")
        return value
    }

    /** Parses [text] and returns it as an object, or null when it is not one. */
    fun parseObject(text: String): JsonValue.Obj? = when (val value = parse(text)) {
        is JsonValue.Obj -> value
        else -> null
    }

    private class Reader(private val source: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= source.length

        fun fail(message: String): Nothing =
            throw JsonException("$message (at offset $index)")

        fun skipWhitespace() {
            while (index < source.length) {
                when (source[index]) {
                    ' ', '\t', '\n', '\r' -> index++
                    else -> return
                }
            }
        }

        fun readValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) fail("Unexpected end of input")
            return when (val c = source[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> readLiteral("true", JsonValue.Bool(true))
                'f' -> readLiteral("false", JsonValue.Bool(false))
                'n' -> readLiteral("null", JsonValue.Null)
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("Unexpected character '$c'")
            }
        }

        private fun readLiteral(text: String, value: JsonValue): JsonValue {
            if (!source.startsWith(text, index)) fail("Invalid literal")
            index += text.length
            return value
        }

        private fun readObject(): JsonValue.Obj {
            index++ // '{'
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd() && source[index] == '}') {
                index++
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || source[index] != '"') fail("Expected an object key")
                val key = readString()
                skipWhitespace()
                if (atEnd() || source[index] != ':') fail("Expected ':' after key '$key'")
                index++
                val value = readValue()
                entries[key] = value
                skipWhitespace()
                if (atEnd()) fail("Unexpected end of object")
                when (source[index]) {
                    ',' -> index++
                    '}' -> { index++; return JsonValue.Obj(entries) }
                    else -> fail("Expected ',' or '}' in object")
                }
            }
        }

        private fun readArray(): JsonValue.Arr {
            index++ // '['
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (!atEnd() && source[index] == ']') {
                index++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += readValue()
                skipWhitespace()
                if (atEnd()) fail("Unexpected end of array")
                when (source[index]) {
                    ',' -> index++
                    ']' -> { index++; return JsonValue.Arr(items) }
                    else -> fail("Expected ',' or ']' in array")
                }
            }
        }

        private fun readString(): String {
            index++ // opening quote
            val builder = StringBuilder()
            while (true) {
                if (atEnd()) fail("Unterminated string")
                when (val c = source[index]) {
                    '"' -> { index++; return builder.toString() }
                    '\\' -> {
                        index++
                        if (atEnd()) fail("Unterminated escape sequence")
                        when (val escape = source[index]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 >= source.length) fail("Invalid unicode escape")
                                val hex = source.substring(index + 1, index + 5)
                                val code = hex.toIntOrNull(16) ?: fail("Invalid unicode escape")
                                builder.append(code.toChar())
                                index += 4
                            }
                            else -> fail("Invalid escape '\\$escape'")
                        }
                        index++
                    }
                    else -> {
                        if (c.code < 0x20) fail("Unescaped control character in string")
                        builder.append(c)
                        index++
                    }
                }
            }
        }

        private fun readNumber(): JsonValue.Num {
            val start = index
            if (!atEnd() && source[index] == '-') index++
            while (!atEnd() && source[index].isDigit()) index++
            if (!atEnd() && source[index] == '.') {
                index++
                while (!atEnd() && source[index].isDigit()) index++
            }
            if (!atEnd() && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (!atEnd() && (source[index] == '+' || source[index] == '-')) index++
                while (!atEnd() && source[index].isDigit()) index++
            }
            val raw = source.substring(start, index)
            val value = raw.toDoubleOrNull() ?: fail("Invalid number '$raw'")
            return JsonValue.Num(value)
        }
    }
}

/* ---------- typed accessors used by the document parsers ---------- */

fun JsonValue.asStringOrNull(): String? = when (this) {
    is JsonValue.Str -> value
    is JsonValue.Num -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    is JsonValue.Bool -> value.toString()
    else -> null
}

fun JsonValue.asDoubleOrNull(): Double? = when (this) {
    is JsonValue.Num -> value
    is JsonValue.Str -> value.trim().toDoubleOrNull()
    is JsonValue.Bool -> if (value) 1.0 else 0.0
    else -> null
}

fun JsonValue.asIntOrNull(): Int? = asDoubleOrNull()?.let { if (it.isFinite()) it.toInt() else null }

fun JsonValue.asBooleanOrNull(): Boolean? = when (this) {
    is JsonValue.Bool -> value
    is JsonValue.Str -> when (value.trim().lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> null
    }
    is JsonValue.Num -> value != 0.0
    else -> null
}

fun JsonValue.asObjectOrNull(): JsonValue.Obj? = this as? JsonValue.Obj

fun JsonValue.asArrayOrNull(): List<JsonValue>? = (this as? JsonValue.Arr)?.items
