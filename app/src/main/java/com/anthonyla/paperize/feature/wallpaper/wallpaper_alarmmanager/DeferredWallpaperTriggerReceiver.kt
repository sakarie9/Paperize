package com.anthonyla.paperize.feature.wallpaper.wallpaper_alarmmanager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.anthonyla.paperize.core.Type

class DeferredWallpaperTriggerReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DeferredWallpaperReceiver"
        private const val ACTION_DEFERRED_CHECK = "com.anthonyla.paperize.action.DEFERRED_WALLPAPER_CHECK"
        private const val REQUEST_CODE_DEFERRED_HOME = 10001
        private const val REQUEST_CODE_DEFERRED_LOCK = 10002
        private const val REQUEST_CODE_DEFERRED_CHECK = 10003
        private const val DEFERRED_CHECK_DELAY_MILLIS = 5 * 60_000L

        fun scheduleDeferredCheck(context: Context, delayMillis: Long = DEFERRED_CHECK_DELAY_MILLIS) {
            val intent = Intent(context, DeferredWallpaperTriggerReceiver::class.java).apply {
                action = ACTION_DEFERRED_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_DEFERRED_CHECK,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAtMillis = System.currentTimeMillis() + delayMillis
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
            Log.d(TAG, "Deferred check scheduled in ${delayMillis}ms")
        }

        private fun cancelDeferredCheck(context: Context) {
            val intent = Intent(context, DeferredWallpaperTriggerReceiver::class.java).apply {
                action = ACTION_DEFERRED_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_DEFERRED_CHECK,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE
            ) ?: return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val action = intent?.action ?: return
            if (action != Intent.ACTION_SCREEN_OFF && action != ACTION_DEFERRED_CHECK && action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_USER_PRESENT) return

            if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT) {
                DeferredWallpaperChangeStore.resetNonInteractiveSession(context)
                cancelDeferredCheck(context)
                Log.d(TAG, "Interactive state resumed, reset non-interactive session")
                return
            }

            if (action == Intent.ACTION_SCREEN_OFF) {
                DeferredWallpaperChangeStore.resetNonInteractiveSession(context)
            }

            if (action == ACTION_DEFERRED_CHECK) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (powerManager.isInteractive) {
                    Log.d(TAG, "Deferred check fired while interactive; rescheduling")
                    scheduleDeferredCheck(context)
                    return
                }
            }

            val pending = DeferredWallpaperChangeStore.consume(context) ?: return
            cancelDeferredCheck(context)

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
