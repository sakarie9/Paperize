package com.anthonyla.paperize.feature.wallpaper.wallpaper_alarmmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DeferredWallpaperTriggerReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DeferredWallpaperReceiver"

        fun processPendingDeferredChange(context: Context): Boolean {
            val pending = DeferredWallpaperChangeStore.consume(context) ?: return false

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

            context.sendBroadcast(triggerIntent)
            Log.d(TAG, "Deferred change dispatched: target=${pending.target}, type=${pending.type}")
            return true
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            processPendingDeferredChange(context)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to process deferred wallpaper trigger", t)
            // Keep app alive; deferred change can be retried by next trigger.
        }
    }
}
