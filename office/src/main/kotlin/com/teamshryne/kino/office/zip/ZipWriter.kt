package com.teamshryne.kino.office.zip

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the ZIP containers used by OOXML (docx/xlsx/pptx) and OpenDocument
 * (odt/ods/odp). Everything is written in memory so a file can be produced
 * without touching disk first.
 */
object ZipWriter {

    data class Entry(val name: String, val bytes: ByteArray, val stored: Boolean = false)

    fun build(entries: List<Entry>): ByteArray {
        val output = ByteArrayOutputStream(64 * 1024)
        ZipOutputStream(output).use { zip ->
            zip.setLevel(9)
            entries.forEach { entry ->
                val zipEntry = ZipEntry(entry.name)
                zipEntry.time = 0L
                if (entry.stored) {
                    // EPUB requires the mimetype entry to be stored uncompressed.
                    zipEntry.method = ZipEntry.STORED
                    zipEntry.size = entry.bytes.size.toLong()
                    zipEntry.compressedSize = entry.bytes.size.toLong()
                    zipEntry.crc = java.util.zip.CRC32().apply { update(entry.bytes) }.value
                }
                zip.putNextEntry(zipEntry)
                zip.write(entry.bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    fun text(entries: List<Pair<String, String>>): ByteArray =
        build(entries.map { Entry(it.first, it.second.toByteArray(Charsets.UTF_8)) })
}
