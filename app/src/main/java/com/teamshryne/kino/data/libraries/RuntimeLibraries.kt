package com.teamshryne.kino.data.libraries

import android.content.Context
import android.os.Build
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile

private const val CATALOG_URL = "https://raw.githubusercontent.com/TeamShryne/kino-libraries/main/libraries.json"
private val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

data class LibraryArtifact(val abi: String, val fileName: String, val url: String, val sha256: String, val size: Long)
data class RuntimeLibrary(val id: String, val name: String, val version: String, val description: String, val format: String, val nativeLibrary: String, val classesFile: String?, val artifacts: List<LibraryArtifact>)
data class LibraryState(val library: RuntimeLibrary, val installed: Boolean, val installedVersion: String?, val artifact: LibraryArtifact?) {
    val updateAvailable: Boolean get() = installedVersion != null && installedVersion != library.version && artifact != null
}

class RuntimeLibraries(private val context: Context) {
    companion object {
        @Volatile private var loadedIronpressPath: String? = null
    }

    private val root = File(context.noBackupFilesDir, "runtime-libraries")
    private val prefs = context.getSharedPreferences("runtime-libraries", Context.MODE_PRIVATE)
    private val abi get() = supportedAbis.firstOrNull { it in Build.SUPPORTED_ABIS } ?: Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    suspend fun catalog(): List<RuntimeLibrary> = withContext(Dispatchers.IO) {
        val json = JSONObject(downloadText(CATALOG_URL))
        require(json.optInt("schema_version") == 1) { "Unsupported library catalog." }
        val rows = json.optJSONArray("libraries") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val artifacts = row.getJSONArray("artifacts")
                add(RuntimeLibrary(
                    id = row.getString("id"), name = row.getString("name"), version = row.getString("version"),
                    description = row.getString("description"), format = row.getString("format"), nativeLibrary = row.getString("native_library"),
                    classesFile = row.optString("classes_file").takeIf { it.isNotBlank() },
                    artifacts = buildList { for (j in 0 until artifacts.length()) { val a = artifacts.getJSONObject(j); add(LibraryArtifact(a.getString("abi"), a.getString("file_name"), a.getString("download_url"), a.getString("sha256"), a.getLong("size_bytes"))) } },
                ))
            }
        }
    }

    fun isInstalled(id: String): Boolean = prefs.getString("$id.version", null) != null

    fun state(library: RuntimeLibrary): LibraryState {
        val installedVersion = prefs.getString("${library.id}.version", null)
        val artifact = library.artifacts.firstOrNull { it.abi == abi }
        val installed = installedVersion == library.version && artifact != null && File(destination(library, artifact), library.nativeLibrary).isFile && (library.classesFile == null || File(destination(library, artifact), library.classesFile).isFile)
        return LibraryState(library, installed, installedVersion, artifact)
    }

    suspend fun install(library: RuntimeLibrary, progress: (Long, Long) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        require(library.id in setOf("kino-core", "ironpress-pdf")) { "Unknown runtime library." }
        require(library.nativeLibrary.matches(Regex("[a-zA-Z0-9_.-]{1,100}"))) { "Invalid native library name." }
        require(library.format == if (library.id == "kino-core") "gomobile-dex-runtime-zip-v1" else "jni-runtime-zip-v1") { "Unsupported runtime package format." }
        require(library.artifacts.map { it.abi }.distinct().size == library.artifacts.size) { "Duplicate ABI packages in catalog." }
        val artifact = state(library).artifact ?: error("${library.name} is not available for this device (${abi.ifBlank { "unknown ABI" }}).")
        require(artifact.url.startsWith("https://github.com/TeamShryne/kino-libraries/releases/")) { "Library URL is not trusted." }
        val folder = destination(library, artifact).apply { mkdirs() }
        val archive = File(folder, "${artifact.fileName}.part")
        try {
            val connection = (URL(artifact.url).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 45_000; instanceFollowRedirects = true }
            try {
                connection.connect()
                require(connection.responseCode in 200..299) { "Library download failed (${connection.responseCode})." }
                val expected = connection.contentLengthLong.takeIf { it > 0 } ?: artifact.size
                connection.inputStream.use { input -> archive.outputStream().buffered().use { output -> val buffer = ByteArray(64 * 1024); var count = 0L; while (true) { val n = input.read(buffer); if (n < 0) break; count += n; require(count <= 160L * 1024 * 1024) { "Library archive is too large." }; output.write(buffer, 0, n); progress(count, expected) } } }
            } finally { connection.disconnect() }
            require(archive.length() == artifact.size) { "Downloaded library size does not match the catalog." }
            require(sha256(archive) == artifact.sha256) { "Downloaded library checksum does not match the catalog." }
            var extractedBytes = 0L
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = File(entry.name)
                    require(!name.isAbsolute && name.toPath().nameCount == 1 && name.name != "." && name.name != "..") { "Invalid library archive entry." }
                    if (!entry.isDirectory) {
                        require(name.name in setOf(library.nativeLibrary, library.classesFile, "library.json")) { "Unexpected file in library archive." }
                        val target = File(folder, name.name)
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) { val count = zip.read(buffer); if (count < 0) break; extractedBytes += count; require(extractedBytes <= 240L * 1024 * 1024) { "Expanded library is too large." }; output.write(buffer, 0, count) }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(File(folder, library.nativeLibrary).isFile && (library.classesFile == null || File(folder, library.classesFile).isFile)) { "Library archive is missing required files." }
            if (library.id == "kino-core") ZipFile(File(folder, requireNotNull(library.classesFile))).use { jar ->
                require(jar.getEntry("classes.dex") != null) { "Kino Core package has no Android DEX bytecode." }
            }
            listOf(library.nativeLibrary, library.classesFile).filterNotNull().forEach { File(folder, it).setReadOnly() }
            prefs.edit().putString("${library.id}.version", library.version).apply()
            archive.delete()
        } catch (error: Throwable) { archive.delete(); throw error }
    }

    fun loadIronpress() {
        val version = prefs.getString("ironpress-pdf.version", null) ?: error("Install the PDF renderer from Settings > Libraries before generating PDFs.")
        val file = nativeFile("ironpress-pdf", version, "libironpress_android.so")
        if (loadedIronpressPath != file.absolutePath) synchronized(this) {
            if (loadedIronpressPath != file.absolutePath) { System.load(file.absolutePath); loadedIronpressPath = file.absolutePath }
        }
    }

    fun nativeFile(id: String, version: String, nativeLibrary: String): File = File(File(root, id), version).walkTopDown().firstOrNull { it.name == nativeLibrary && it.isFile } ?: error("Runtime library is not installed.")

    fun coreClassLoader(): ClassLoader {
        val version = prefs.getString("kino-core.version", null) ?: error("Install Kino Core from Settings > Libraries before chatting.")
        val dir = File(File(root, "kino-core"), version).walkTopDown().firstOrNull { it.name == "classes.jar" }?.parentFile ?: error("Kino Core is not installed.")
        val so = nativeFile("kino-core", version, "libgojni.so")
        val optimized = File(context.codeCacheDir, "kino-dex").apply { mkdirs() }
        return DexClassLoader(File(dir, "classes.jar").absolutePath, optimized.absolutePath, so.parent, context.classLoader)
    }

    private fun destination(library: RuntimeLibrary, artifact: LibraryArtifact) = File(File(root, library.id), "${library.version}/${artifact.abi}")
    private fun downloadText(url: String): String {
        require(url.startsWith("https://raw.githubusercontent.com/TeamShryne/kino-libraries/"))
        val refreshedUrl = "$url${if (url.contains('?')) '&' else '?'}kino_refresh=${System.currentTimeMillis()}"
        val connection = URL(refreshedUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("Pragma", "no-cache")
            require(connection.responseCode == 200) { "Could not load the library catalog." }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    private fun sha256(file: File): String { val digest = MessageDigest.getInstance("SHA-256"); file.inputStream().buffered().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }; return digest.digest().joinToString("") { "%02x".format(it) } }
}
