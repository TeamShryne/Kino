package com.teamshryne.kino.data.tools

import android.content.Context
import com.teamshryne.kino.data.libraries.RuntimeLibraries
import org.json.JSONObject

class KinoOfficeClient(private val context: Context) {
    private val libraries = RuntimeLibraries(context)

    fun isInstalled(): Boolean = libraries.isInstalled("kino-office")

    suspend fun render(arguments: JSONObject): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!isInstalled()) error("Install Kino Office from Settings > Libraries before creating Office files.")
        val loader = libraries.officeClassLoader()
        val officeClass = loader.loadClass("com.teamshryne.kinooffice.OfficeLibrary")
        val method = officeClass.getMethod("render", Context::class.java, JSONObject::class.java)
        method.invoke(null, context, arguments) as? String ?: error("Kino Office did not return a filename.")
    }
}
