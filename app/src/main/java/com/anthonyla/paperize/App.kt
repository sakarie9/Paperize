package com.anthonyla.paperize

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.IntentFilter
import android.database.CursorWindow
import androidx.core.content.ContextCompat
import com.anthonyla.paperize.feature.wallpaper.wallpaper_alarmmanager.DeferredWallpaperTriggerReceiver
import dagger.hilt.android.HiltAndroidApp
import java.lang.reflect.Field


/**
 * Application class for Hilt
 */
@HiltAndroidApp
class App: Application() {
    private val deferredWallpaperTriggerReceiver = DeferredWallpaperTriggerReceiver()

    override fun onCreate() {
        super.onCreate()

        // Increase CursorWindow size to handle large datasets
        // Default is usually 2MB, increasing to 10MB
        try {
            val field: Field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            field.set(null, 10 * 1024 * 1024) // 10MB
        } catch (e: Exception) {
            // Fallback for newer Android versions where field might be different
            try {
                val field: Field = CursorWindow::class.java.getDeclaredField("CURSOR_WINDOW_SIZE")
                field.isAccessible = true
                field.set(null, 10 * 1024 * 1024) // 10MB
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }

        val channel = NotificationChannel("wallpaper_service_channel", "Paperize", NotificationManager.IMPORTANCE_LOW)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        ContextCompat.registerReceiver(
            this,
            deferredWallpaperTriggerReceiver,
            IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}