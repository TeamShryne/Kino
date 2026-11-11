package com.teamshryne.kino.office.image

import java.util.Base64

/** Binary image content that can be embedded in a generated office file. */
class ImageData(
    val bytes: ByteArray,
    val mimeType: String,
    val extension: String,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Resolves image sources referenced by a document.
 *
 * The office module stays offline and deterministic: it decodes `data:` URIs
 * itself and accepts whatever else the host application hands it. The Android
 * app adds a network resolver for `https:` sources.
 */
object Images {

    private val EXTENSION_BY_MIME = mapOf(
        "image/png" to "png",
        "image/jpeg" to "jpeg",
        "image/jpg" to "jpeg",
        "image/gif" to "gif",
        "image/bmp" to "bmp",
        "image/webp" to "webp",
        "image/svg+xml" to "svg",
    )

    fun isRemote(source: String): Boolean =
        source.startsWith("https://", ignoreCase = true) || source.startsWith("http://", ignoreCase = true)

    fun isDataUri(source: String): Boolean = source.startsWith("data:", ignoreCase = true)

    /** Decodes a `data:` URI. Returns null when the payload is not a usable image. */
    fun decodeDataUri(source: String): ImageData? {
        if (!isDataUri(source)) return null
        val comma = source.indexOf(',')
        if (comma < 0) return null
        val header = source.substring(5, comma)
        val payload = source.substring(comma + 1)
        val parts = header.split(';')
        val mimeType = parts.firstOrNull()?.trim()?.lowercase() ?: return null
        val isBase64 = parts.any { it.trim().equals("base64", ignoreCase = true) }
        val bytes = try {
            if (isBase64) {
                val cleaned = payload.filterNot { it.isWhitespace() }
                Base64.getDecoder().decode(cleaned)
            } else {
                java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
            }
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.isEmpty()) return null
        val extension = EXTENSION_BY_MIME[mimeType] ?: when {
            bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpeg"
            bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "gif"
            else -> return null
        }
        return ImageData(bytes, mimeType, extension)
    }

    fun extensionFor(mimeType: String): String? = EXTENSION_BY_MIME[mimeType.lowercase()]
}
