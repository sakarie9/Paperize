package com.anthonyla.paperize.feature.wallpaper.wallpaper_alarmmanager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.anthonyla.paperize.core.Type

class DeferredWallpaperTriggerReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DeferredWallpaperReceiver"
        private const val REQUEST_CODE_DEFERRED_HOME = 10001
        private const val REQUEST_CODE_DEFERRED_LOCK = 10002
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return

            val pending = DeferredWallpaperChangeStore.consume(context) ?: return

            // Android 14+/targetSdk 34+ may block startForegroundService from this receiver context.
            // Route through an immediate exact alarm to existing WallpaperReceiver, which already
            // handles service dispatch and alarm lifecycle.
            val triggerIntent = Intent(context, WallpaperReceiver::class.java).apply {
                putExtra("homeInterval", pending.homeInterval)
                putExtra("lockInterval", pending.lockInterval)
                putExtra("scheduleSeparately", pending.scheduleSeparately)
                putExtra("type", pending.type)
                putExtra("setHome", pending.target == DeferredWallpaperChangeStore.TARGET_HOME)
                putExtra("setLock", pending.target == DeferredWallpaperChangeStore.TARGET_LOCK)
                putExtra("deferredTrigger", true)
                putExtra("changeStartTime", false)
                putExtra("startTime", intArrayOf(0, 0))
            }

            val requestCode = when (pending.target) {
                DeferredWallpaperChangeStore.TARGET_HOME -> REQUEST_CODE_DEFERRED_HOME
                DeferredWallpaperChangeStore.TARGET_LOCK -> REQUEST_CODE_DEFERRED_LOCK
                else -> return
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                triggerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAtMillis = System.currentTimeMillis() + 300L
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)

            Log.d(
                TAG,
                "Deferred change rerouted: target=${pending.target}, type=${pending.type}, requestCode=$requestCode"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to process deferred wallpaper trigger", t)
            // Keep app alive; deferred change can be retried by next trigger.
        }
    }
}
