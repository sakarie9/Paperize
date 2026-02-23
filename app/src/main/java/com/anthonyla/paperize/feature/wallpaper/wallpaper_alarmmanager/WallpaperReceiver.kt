package com.anthonyla.paperize.feature.wallpaper.wallpaper_alarmmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.anthonyla.paperize.core.SettingsConstants
import com.anthonyla.paperize.core.SettingsConstants.WALLPAPER_CHANGE_INTERVAL_DEFAULT
import com.anthonyla.paperize.core.Type
import com.anthonyla.paperize.data.settings.SettingsDataStore
import com.anthonyla.paperize.feature.wallpaper.wallpaper_service.HomeWallpaperService
import com.anthonyla.paperize.feature.wallpaper.wallpaper_service.LockWallpaperService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@AndroidEntryPoint
class WallpaperReceiver : BroadcastReceiver() {
    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    companion object {
        private const val TAG = "WallpaperReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val refresh = intent.getBooleanExtra("refresh", false)
                if (refresh) {
                    val serviceIntent = Intent(context, HomeWallpaperService::class.java).apply {
                        action = HomeWallpaperService.Actions.REFRESH.toString()
                    }
                    context.startForegroundService(serviceIntent)
                    return@launch
                }

                val triggerSource = if (intent.getBooleanExtra("deferredTrigger", false)) "deferred" else "regular"

                val homeInterval = intent.getIntExtra("homeInterval", WALLPAPER_CHANGE_INTERVAL_DEFAULT)
                val lockInterval = intent.getIntExtra("lockInterval", WALLPAPER_CHANGE_INTERVAL_DEFAULT)
                val scheduleSeparately = intent.getBooleanExtra("scheduleSeparately", false)
                val type = intent.getIntExtra("type", Type.SINGLE.ordinal)
                val setHome = intent.getBooleanExtra("setHome", false)
                val setLock = intent.getBooleanExtra("setLock", false)
                val changeStartTime = intent.getBooleanExtra("changeStartTime", false)
                val startTime = intent.getIntArrayExtra("startTime") ?: intArrayOf(0, 0)
                val deferredTrigger = intent.getBooleanExtra("deferredTrigger", false)
                Log.d(TAG, "Alarm received source=$triggerSource type=$type")

                val onlyNonInteractive = settingsDataStore.getBoolean(SettingsConstants.ONLY_NON_INTERACTIVE) ?: false
                if (onlyNonInteractive && !deferredTrigger) {
                    Log.d(TAG, "Only-non-interactive active: skip service start and defer latest request")
                    deferLatestRequest(
                        context = context,
                        type = type,
                        setHome = setHome,
                        setLock = setLock,
                        homeInterval = homeInterval,
                        lockInterval = lockInterval,
                        scheduleSeparately = scheduleSeparately
                    )
                } else {
                    when (type) {
                        Type.SINGLE.ordinal -> {
                            if (setLock) startService(context, LockWallpaperService::class.java, LockWallpaperService.Actions.START.toString(), homeInterval, lockInterval, scheduleSeparately, type, deferredTrigger)
                            if (setHome) startService(context, HomeWallpaperService::class.java, HomeWallpaperService.Actions.START.toString(), homeInterval, lockInterval, scheduleSeparately, type, deferredTrigger)
                        }
                        Type.HOME.ordinal -> {
                            startService(context, HomeWallpaperService::class.java, HomeWallpaperService.Actions.START.toString(), homeInterval, lockInterval, scheduleSeparately, type, deferredTrigger)
                        }
                        Type.LOCK.ordinal -> {
                            startService(context, LockWallpaperService::class.java, LockWallpaperService.Actions.START.toString(), homeInterval, lockInterval, scheduleSeparately, type, deferredTrigger)
                        }
                    }
                }

                val origin = intent.getIntExtra("origin", -1).takeIf { it != -1 }
                if (origin != null) {
                    Log.d(TAG, "Rescheduling next alarm from origin=$origin")
                }

                val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                val currentTime = LocalDateTime.now()
                settingsDataStore.putString(SettingsConstants.LAST_SET_TIME, currentTime.format(formatter))

                val homeNext = settingsDataStore.getString(SettingsConstants.HOME_NEXT_SET_TIME)
                val lockNext = settingsDataStore.getString(SettingsConstants.LOCK_NEXT_SET_TIME)

                val alarmItem = WallpaperAlarmItem(
                    homeInterval = homeInterval,
                    lockInterval = lockInterval,
                    scheduleSeparately = scheduleSeparately,
                    setHome = setHome,
                    setLock = setLock,
                    changeStartTime = changeStartTime,
                    startTime = Pair(startTime[0], startTime[1]),
                )

                val scheduler = WallpaperAlarmSchedulerImpl(context, settingsDataStore)
                scheduler.scheduleWallpaperAlarm(
                    wallpaperAlarmItem = alarmItem,
                    origin = origin,
                    changeImmediate = false,
                    cancelImmediate = false,
                    setAlarm = true,
                    firstLaunch = false,
                    homeNextTime = homeNext,
                    lockNextTime = lockNext,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun deferLatestRequest(
        context: Context,
        type: Int,
        setHome: Boolean,
        setLock: Boolean,
        homeInterval: Int,
        lockInterval: Int,
        scheduleSeparately: Boolean,
    ) {
        when (type) {
            Type.HOME.ordinal -> {
                DeferredWallpaperChangeStore.save(
                    context,
                    DeferredWallpaperChange(
                        target = DeferredWallpaperChangeStore.TARGET_HOME,
                        homeInterval = homeInterval,
                        lockInterval = lockInterval,
                        scheduleSeparately = scheduleSeparately,
                        type = type
                    )
                )
            }

            Type.LOCK.ordinal -> {
                DeferredWallpaperChangeStore.save(
                    context,
                    DeferredWallpaperChange(
                        target = DeferredWallpaperChangeStore.TARGET_LOCK,
                        homeInterval = homeInterval,
                        lockInterval = lockInterval,
                        scheduleSeparately = scheduleSeparately,
                        type = type
                    )
                )
            }

            else -> {
                if (setLock) {
                    DeferredWallpaperChangeStore.save(
                        context,
                        DeferredWallpaperChange(
                            target = DeferredWallpaperChangeStore.TARGET_LOCK,
                            homeInterval = homeInterval,
                            lockInterval = lockInterval,
                            scheduleSeparately = scheduleSeparately,
                            type = type
                        )
                    )
                }
                if (setHome) {
                    DeferredWallpaperChangeStore.save(
                        context,
                        DeferredWallpaperChange(
                            target = DeferredWallpaperChangeStore.TARGET_HOME,
                            homeInterval = homeInterval,
                            lockInterval = lockInterval,
                            scheduleSeparately = scheduleSeparately,
                            type = type
                        )
                    )
                }
            }
        }
    }

    private fun startService(context: Context, serviceClass: Class<*>, action: String, homeInterval: Int?, lockInterval: Int?, scheduleSeparately: Boolean?, type: Int?, deferredTrigger: Boolean = false) {
        val serviceIntent = Intent(context, serviceClass).apply {
            this.action = action
            homeInterval?.let { putExtra("homeInterval", it) }
            lockInterval?.let { putExtra("lockInterval", it) }
            scheduleSeparately?.let { putExtra("scheduleSeparately", it) }
            type?.let { putExtra("type", it) }
            putExtra("deferredTrigger", deferredTrigger)
        }
        context.startForegroundService(serviceIntent)
    }
}