package com.teamshryne.kino.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.teamshryne.kino.MainActivity

/** Keeps the process alive while chat generations are running outside the visible chat screen. */
class KinoGenerationService : Service() {
    companion object {
        private const val CHANNEL_ID = "kino_generations"
        private const val NOTIFICATION_ID = 4101
        private const val EXTRA_ACTIVE = "generation_active"
        @Volatile private var running: KinoGenerationService? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun start(context: Context) {
            if (running == null) context.applicationContext.startService(Intent(context, KinoGenerationService::class.java))
        }

        fun update(context: Context, active: Boolean) {
            val service = running
            if (service != null) {
                mainHandler.post { service.setGenerationActive(active) }
            } else if (active) {
                val intent = Intent(context, KinoGenerationService::class.java).putExtra(EXTRA_ACTIVE, true)
                runCatching { context.applicationContext.startService(intent) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Kino responses", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shows when Kino is working on chat responses."
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        if ((application as KinoApplication).generations.values.any { it.job?.isActive == true }) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            (application as KinoApplication).serviceForeground.set(true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = application as KinoApplication
        val active = if (intent?.hasExtra(EXTRA_ACTIVE) == true) intent.getBooleanExtra(EXTRA_ACTIVE, false)
            else runtime.generations.values.any { it.job?.isActive == true }
        setGenerationActive(active)
        return START_STICKY
    }

    private fun setGenerationActive(active: Boolean) {
        if (running !== this) return
        val runtime = application as KinoApplication
        if (active) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            runtime.serviceForeground.set(true)
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            runtime.serviceForeground.set(false)
        }
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val activeCount = (application as KinoApplication).generations.values.count { it.job?.isActive == true }
        val body = if (activeCount == 1) "A response is continuing in the background." else "$activeCount responses are continuing in the background."
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Kino is working")
            .setContentText(body)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as KinoApplication).generations.values.forEach { generation ->
            generation.cancelled.set(true)
            generation.job?.cancel()
        }
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (running === this) running = null
        (application as KinoApplication).serviceForeground.set(false)
        super.onDestroy()
    }
}
