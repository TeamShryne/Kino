package com.teamshryne.kino.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object KinoCrashHandler {
    private const val CRASH_DIR = "crash-logs"
    private const val TAG = "KinoCrashHandler"

    fun install(application: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val logFile = writeCrashLog(application, thread, throwable)
            try {
                val intent = Intent(application, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(CrashActivity.EXTRA_LOG_NAME, logFile.name)
                }
                application.startActivity(intent)
            } catch (error: Throwable) {
                Log.e(TAG, "Could not start crash activity", error)
            }
            previous?.uncaughtException(thread, throwable)
            Process.killProcess(Process.myPid())
            kotlin.system.exitProcess(1)
        }
    }

    fun crashDirectory(context: Context): File =
        File(context.filesDir, CRASH_DIR).apply { mkdirs() }

    private fun writeCrashLog(application: Application, thread: Thread, throwable: Throwable): File {
        val dir = crashDirectory(application)
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
        val file = File(dir, "crash-$timestamp.log")
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        file.writeText(buildString {
            appendLine("Kino crash report")
            appendLine("Time: ${Date()}")
            appendLine("Thread: ${thread.name}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("App version: ${application.packageManager.getPackageInfo(application.packageName, 0).versionName}")
            appendLine()
            appendLine("Stack trace:")
            appendLine(writer.toString())
        })
        return file
    }
}
