package com.teamshryne.kino.data.tools

import android.content.Context
import com.teamshryne.kino.data.libraries.RuntimeLibraries
import org.json.JSONObject

class KinoOfficeClient(private val context: Context) {
    private val libraries = RuntimeLibraries(context)

    fun isInstalled(): Boolean = libraries.isInstalled("kino-office")

    suspend fun render(arguments: JSONObject): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!isInstalled()) error("Install Kino Office from Settings > Libraries before creating Office files.")
        try {
            val loader = libraries.officeClassLoader()
            val officeClass = loader.loadClass("com.teamshryne.kinooffice.OfficeLibrary")
            val method = officeClass.getMethod("render", java.io.File::class.java, JSONObject::class.java)
            val outputDir = java.io.File(context.filesDir, "office-exports").apply { mkdirs() }
            method.invoke(null, outputDir, arguments) as? String ?: error("Kino Office did not return a filename.")
        } catch (error: Throwable) {
            val cause = if (error is java.lang.reflect.InvocationTargetException) error.targetException ?: error.cause ?: error else error.cause ?: error
            val detail = buildString {
                append(cause::class.qualifiedName ?: cause::class.simpleName)
                append(": ")
                append(cause.message ?: cause.toString())
                cause.stackTrace.take(8).forEach { append("\n\tat ").append(it) }
            }
            throw IllegalStateException("Kino Office render failed: $detail", cause)
        }
    }
}
