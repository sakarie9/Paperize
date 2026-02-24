package com.anthonyla.paperize.feature.wallpaper.wallpaper_service

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anthonyla.paperize.R
import com.anthonyla.paperize.feature.wallpaper.presentation.MainActivity
import com.anthonyla.paperize.feature.wallpaper.wallpaper_alarmmanager.DeferredWallpaperChangeStore
import com.anthonyla.paperize.feature.wallpaper.wallpaper_alarmmanager.DeferredWallpaperTriggerReceiver

class DeferredScreenOffListenerService : Service() {
    companion object {
        private const val TAG = "DeferredScreenOffService"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context) {
            val startIntent = Intent(context, DeferredScreenOffListenerService::class.java)
            try {
                context.startForegroundService(startIntent)
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to start deferred listener foreground service", t)
            }
        }
    }

    private var registered = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            val triggered = DeferredWallpaperTriggerReceiver.processPendingDeferredChange(context)
            Log.d(TAG, "Screen off received. deferredTriggered=$triggered")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createInitialNotification())
        registerScreenOffReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hasPending = DeferredWallpaperChangeStore.hasPending(this)
        if (!hasPending) {
            Log.d(TAG, "No deferred change pending. Stop listener service.")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterScreenOffReceiver()
        super.onDestroy()
    }

    private fun registerScreenOffReceiver() {
        if (registered) return
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenOffReceiver, filter)
        }
        registered = true
    }

    private fun unregisterScreenOffReceiver() {
        if (!registered) return
        runCatching { unregisterReceiver(screenOffReceiver) }
        registered = false
    }

    private fun createInitialNotification(): android.app.Notification {
        val mainActivityIntent = Intent(this, MainActivity::class.java)
        val pendingMainActivityIntent = PendingIntent.getActivity(
            this,
            4,
            mainActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "wallpaper_service_channel").apply {
            setContentTitle(getString(R.string.app_name))
            setContentText(getString(R.string.changing_wallpaper))
            setSmallIcon(R.drawable.notification_icon)
            setContentIntent(pendingMainActivityIntent)
            priority = NotificationCompat.PRIORITY_LOW
            setOnlyAlertOnce(true)
        }.build()
    }
}
