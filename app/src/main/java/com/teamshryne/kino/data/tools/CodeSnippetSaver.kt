package com.teamshryne.kino.data.tools

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes a code snippet to the shared Downloads folder.
 *
 * Android 10 and newer allow apps to add files to the Downloads collection without any permission,
 * older versions need the user to pick a location, which the caller handles with the system
 * document picker.
 */
object CodeSnippetSaver {

    /** Whether a snippet can be stored without asking the user first. */
    fun canSaveWithoutPrompt(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Stores [content] as [fileName] in the Downloads folder. */
    suspend fun saveToDownloads(context: Context, fileName: String, content: String): Boolean {
        if (!canSaveWithoutPrompt(context)) return false
        return withContext(Dispatchers.IO) { Api29.save(context, fileName, content) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private object Api29 {
        fun save(context: Context, fileName: String, content: String): Boolean {
            val resolver = context.contentResolver
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                resolver.openOutputStream(uri)?.use { output -> output.write(content.toByteArray()) }
                    ?: return false
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (error: Throwable) {
                false
            }
        }
    }
}
