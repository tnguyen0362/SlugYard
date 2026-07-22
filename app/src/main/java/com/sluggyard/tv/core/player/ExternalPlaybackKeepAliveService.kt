package com.sluggyard.tv.core.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sluggyard.tv.R

/**
 * Foreground service that keeps SlugYard's process alive while an external video
 * player is in the foreground. Some boxes (e.g. NVIDIA Shield) otherwise kill the
 * app, dropping external-playback tracking state.
 *
 * [startForeground] is invoked synchronously from [onStartCommand] to satisfy the
 * Android 8+ ForegroundServiceDidNotStartInTime contract. A safety timeout
 * auto-stops the service after 8 hours in case [stop] is never called.
 */
class ExternalPlaybackKeepAliveService : Service() {

    companion object {
        private const val TAG = "ExtPlaybackKeepAlive"
        private const val CHANNEL_ID = "external_playback_channel"
        private const val NOTIFICATION_ID = 9529 // Zidoo port number as a nod :)
        private const val MAX_ALIVE_MS = 8L * 60 * 60 * 1000 // 8 hours safety limit

        fun start(context: Context) {
            val intent = Intent(context, ExternalPlaybackKeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "Service start requested")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start keep-alive service", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ExternalPlaybackKeepAliveService::class.java))
                Log.d(TAG, "Service stop requested")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop keep-alive service", e)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val safetyTimeout = Runnable {
        Log.d(TAG, "Safety timeout reached, stopping service")
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        // Reset the safety timeout on every (re)start.
        mainHandler.removeCallbacks(safetyTimeout)
        mainHandler.postDelayed(safetyTimeout, MAX_ALIVE_MS)
        Log.d(TAG, "Foreground service started")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed, keeping service alive")
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(safetyTimeout)
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.external_playback_channel_description)
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.external_playback_notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
}