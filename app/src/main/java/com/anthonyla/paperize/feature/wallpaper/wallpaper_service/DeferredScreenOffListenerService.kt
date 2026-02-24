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
import com.anthonyla.paperize.core.SettingsConstants
import com.anthonyla.paperize.core.Type
import com.anthonyla.paperize.data.settings.SettingsDataStore
import com.anthonyla.paperize.feature.wallpaper.presentation.MainActivity
import com.anthonyla.paperize.feature.wallpaper.wallpaper_alarmmanager.DeferredWallpaperChange
import com.anthonyla.paperize.feature.wallpaper.wallpaper_alarmmanager.DeferredWallpaperChangeStore
import com.anthonyla.paperize.feature.wallpaper.wallpaper_alarmmanager.WallpaperAlarmItem
import com.anthonyla.paperize.feature.wallpaper.wallpaper_alarmmanager.WallpaperAlarmSchedulerImpl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
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

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            CoroutineScope(Dispatchers.IO).launch {
                val change = DeferredWallpaperChangeStore.consume(context)
                if (change == null) {
                    Log.d(TAG, "Screen off received but no deferred request pending")
                    stopSelf()
                    return@launch
                }

                val triggered = executeDeferredChange(context, change)
                if (triggered) {
                    scheduleNextAlarm(context, change)
                } else {
                    DeferredWallpaperChangeStore.save(context, change)
                }
                Log.d(TAG, "Screen off received. deferredTriggered=$triggered")
                stopSelf()
            }
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

    private suspend fun scheduleNextAlarm(context: Context, change: DeferredWallpaperChange) {
        val scheduler = WallpaperAlarmSchedulerImpl(context, settingsDataStore)
        val isHome = change.target == DeferredWallpaperChangeStore.TARGET_HOME
        val isLock = change.target == DeferredWallpaperChangeStore.TARGET_LOCK
        val alarmItem = WallpaperAlarmItem(
            homeInterval = change.homeInterval,
            lockInterval = change.lockInterval,
            scheduleSeparately = change.scheduleSeparately,
            setHome = isHome,
            setLock = isLock,
            changeStartTime = false,
            startTime = Pair(0, 0)
        )

        scheduler.scheduleWallpaperAlarm(
            wallpaperAlarmItem = alarmItem,
            origin = change.type,
            changeImmediate = false,
            cancelImmediate = false,
            setAlarm = true,
            firstLaunch = false,
            homeNextTime = settingsDataStore.getString(SettingsConstants.HOME_NEXT_SET_TIME),
            lockNextTime = settingsDataStore.getString(SettingsConstants.LOCK_NEXT_SET_TIME)
        )
    }

    private fun executeDeferredChange(context: Context, change: DeferredWallpaperChange): Boolean {
        val intent = when (change.target) {
            DeferredWallpaperChangeStore.TARGET_HOME -> {
                Intent(context, HomeWallpaperService::class.java).apply {
                    action = HomeWallpaperService.Actions.START.toString()
                }
            }

            DeferredWallpaperChangeStore.TARGET_LOCK -> {
                Intent(context, LockWallpaperService::class.java).apply {
                    action = LockWallpaperService.Actions.START.toString()
                }
            }

            else -> return false
        }

        intent.putExtra("homeInterval", change.homeInterval)
        intent.putExtra("lockInterval", change.lockInterval)
        intent.putExtra("scheduleSeparately", change.scheduleSeparately)
        intent.putExtra("type", change.type)
        intent.putExtra("deferredTrigger", true)

        return runCatching {
            startForegroundService(intent)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to start deferred wallpaper service", it)
            false
        }
    }
}
