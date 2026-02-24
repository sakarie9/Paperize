package com.anthonyla.paperize.feature.wallpaper.wallpaper_alarmmanager

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.SettingsConstants
import com.anthonyla.paperize.core.Type
import com.anthonyla.paperize.data.settings.SettingsDataStore
import com.anthonyla.paperize.feature.wallpaper.presentation.MainActivity
import com.anthonyla.paperize.feature.wallpaper.wallpaper_service.HomeWallpaperService
import com.anthonyla.paperize.feature.wallpaper.wallpaper_service.LockWallpaperService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

data class ServiceConfig(
    val homeInterval: Int? = null,
    val lockInterval: Int? = null,
    val scheduleSeparately: Boolean? = null,
    val type: Int? = null
)

sealed class WallpaperAction {
    data object START : WallpaperAction()
    data object UPDATE : WallpaperAction()
}

class WallpaperAlarmSchedulerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore
): WallpaperAlarmScheduler {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        private const val TAG = "WallpaperAlarmScheduler"

        /**
         * Schedules a refresh alarm every 24hrs
         * This is a static method that doesn't require an instance of WallpaperAlarmSchedulerImpl
         */
        fun scheduleRefresh(context: Context, refresh: Boolean = true) {
            Log.d(TAG, "scheduleRefresh called with refresh: $refresh")
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // Cancel any existing refresh alarm
            val cancelIntent = Intent(context, WallpaperReceiver::class.java)
            val cancelPendingIntent = PendingIntent.getBroadcast(
                context,
                Type.REFRESH.ordinal,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            alarmManager.cancel(cancelPendingIntent)

            if (refresh) {
                val intent = Intent(context, WallpaperReceiver::class.java).apply {
                    putExtra("refresh", true)
                }
                val nextMidnight = LocalDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
                Log.d(TAG, "Scheduling inexact repeating refresh alarm for next midnight: $nextMidnight")
                alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    nextMidnight.atZone(ZoneId.systemDefault()).toEpochSecond() * 1000,
                    AlarmManager.INTERVAL_DAY,
                    PendingIntent.getBroadcast(
                        context,
                        Type.REFRESH.ordinal,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                )
            }
        }
    }

    @RequiresPermission(Manifest.permission.SCHEDULE_EXACT_ALARM)
    override suspend fun scheduleWallpaperAlarm(
        wallpaperAlarmItem: WallpaperAlarmItem,
        origin: Int?,
        changeImmediate: Boolean,
        cancelImmediate: Boolean,
        setAlarm: Boolean,
        firstLaunch: Boolean,
        homeNextTime: String?,
        lockNextTime: String?,
    ) {
        Log.d(
            TAG,
            "scheduleWallpaperAlarm type=${if (wallpaperAlarmItem.scheduleSeparately) "SEPARATE" else "SINGLE"}, immediate=$changeImmediate, setAlarm=$setAlarm, firstLaunch=$firstLaunch, origin=$origin"
        )

        if (cancelImmediate) {
            cancelWallpaperAlarm()
        } else {
            when (origin) {
                Type.HOME.ordinal -> cancelAlarm(Type.HOME)
                Type.LOCK.ordinal -> cancelAlarm(Type.LOCK)
                null -> {
                    cancelAlarm(Type.HOME)
                    cancelAlarm(Type.LOCK)
                    cancelAlarm(Type.SINGLE)
                }
            }
        }

        if (changeImmediate) {
            if (wallpaperAlarmItem.scheduleSeparately) {
                changeWallpaperImmediate(wallpaperAlarmItem, Type.LOCK)
                changeWallpaperImmediate(wallpaperAlarmItem, Type.HOME)
            } else {
                changeWallpaperImmediate(wallpaperAlarmItem, Type.SINGLE)
            }
        }

        if (setAlarm) {
            if (wallpaperAlarmItem.scheduleSeparately) {
                val scheduleLock = when (origin) {
                    Type.HOME.ordinal -> false
                    Type.LOCK.ordinal -> true
                    else -> wallpaperAlarmItem.setLock
                }
                val scheduleHome = when (origin) {
                    Type.LOCK.ordinal -> false
                    Type.HOME.ordinal -> true
                    else -> wallpaperAlarmItem.setHome
                }

                if (scheduleLock) {
                    scheduleWallpaper(wallpaperAlarmItem, Type.LOCK, Type.LOCK.ordinal, firstLaunch, homeNextTime, lockNextTime)
                }
                if (scheduleHome) {
                    scheduleWallpaper(wallpaperAlarmItem, Type.HOME, Type.HOME.ordinal, firstLaunch, homeNextTime, lockNextTime)
                }
            } else {
                if (wallpaperAlarmItem.setHome || wallpaperAlarmItem.setLock) {
                    scheduleWallpaper(wallpaperAlarmItem, Type.SINGLE, null, firstLaunch, homeNextTime, lockNextTime)
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.SCHEDULE_EXACT_ALARM)
    private suspend fun scheduleWallpaper(
        wallpaperAlarmItem: WallpaperAlarmItem,
        type: Type,
        origin: Int? = null,
        firstLaunch: Boolean = false,
        homeNextTime: String? = null,
        lockNextTime: String? = null
    ) {
        try {
            val nextTime = calculateNextAlarmTime(wallpaperAlarmItem, type, firstLaunch, homeNextTime ?: "", lockNextTime ?: "")

            val intent = createWallpaperIntent(wallpaperAlarmItem, type, origin)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Log.w(TAG, "Cannot schedule exact alarms. Permission may be denied.")
                cancelWallpaperAlarm()
                return
            }

            scheduleExactAlarm(nextTime, intent)

            val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            when (type) {
                Type.HOME -> settingsDataStore.putString(SettingsConstants.HOME_NEXT_SET_TIME, nextTime.toString())
                Type.LOCK -> settingsDataStore.putString(SettingsConstants.LOCK_NEXT_SET_TIME, nextTime.toString())
                Type.SINGLE -> {
                    settingsDataStore.putString(SettingsConstants.HOME_NEXT_SET_TIME, nextTime.toString())
                    settingsDataStore.putString(SettingsConstants.LOCK_NEXT_SET_TIME, nextTime.toString())
                }
                else -> {}
            }

            val currentHomeNext = settingsDataStore.getString(SettingsConstants.HOME_NEXT_SET_TIME)
            val currentLockNext = settingsDataStore.getString(SettingsConstants.LOCK_NEXT_SET_TIME)
            val nextHomeDateTime = runCatching { LocalDateTime.parse(currentHomeNext) }.getOrNull()
            val nextLockDateTime = runCatching { LocalDateTime.parse(currentLockNext) }.getOrNull()

            val nextSetTimeForNotification = listOfNotNull(nextHomeDateTime, nextLockDateTime)
                .filter { it.isAfter(LocalDateTime.now()) }
                .minOrNull() ?: nextTime

            settingsDataStore.putString(SettingsConstants.NEXT_SET_TIME, nextSetTimeForNotification.format(formatter))
            postNotification(nextSetTimeForNotification)

        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling wallpaper alarm for type '$type'", e)
            cancelWallpaperAlarm()
        }
    }

    private suspend fun calculateNextAlarmTime(
        wallpaperAlarmItem: WallpaperAlarmItem,
        type: Type,
        firstLaunch: Boolean,
        homeNextTime: String,
        lockNextTime: String
    ): LocalDateTime {
        val now = LocalDateTime.now()
        val intervalMinutes = when {
            type == Type.LOCK && wallpaperAlarmItem.scheduleSeparately -> wallpaperAlarmItem.lockInterval.toLong()
            else -> wallpaperAlarmItem.homeInterval.toLong()
        }

        val nextTime = if (wallpaperAlarmItem.changeStartTime) {
            var anchorTime = now
                .withHour(wallpaperAlarmItem.startTime.first)
                .withMinute(wallpaperAlarmItem.startTime.second)
                .withSecond(0).withNano(0)
            while (!anchorTime.isAfter(now)) {
                anchorTime = anchorTime.plusMinutes(intervalMinutes)
            }
            anchorTime
        } else {
            if (firstLaunch) {
                now.plusMinutes(intervalMinutes)
            } else {
                val baseTimeFromLastSet = when (type) {
                    Type.LOCK -> {
                        val raw = settingsDataStore.getString(SettingsConstants.LOCK_LAST_SET_TIME_RAW)
                        runCatching { LocalDateTime.parse(raw) }.getOrNull()
                    }
                    Type.HOME -> {
                        val raw = settingsDataStore.getString(SettingsConstants.HOME_LAST_SET_TIME_RAW)
                        runCatching { LocalDateTime.parse(raw) }.getOrNull()
                    }
                    Type.SINGLE -> {
                        val homeRaw = settingsDataStore.getString(SettingsConstants.HOME_LAST_SET_TIME_RAW)
                        val lockRaw = settingsDataStore.getString(SettingsConstants.LOCK_LAST_SET_TIME_RAW)
                        val homeLast = runCatching { LocalDateTime.parse(homeRaw) }.getOrNull()
                        val lockLast = runCatching { LocalDateTime.parse(lockRaw) }.getOrNull()
                        listOfNotNull(homeLast, lockLast).maxOrNull()
                    }
                    else -> null
                }

                val legacyBaseTime = when (type) {
                    Type.LOCK -> runCatching { LocalDateTime.parse(lockNextTime) }.getOrNull()
                    else -> runCatching { LocalDateTime.parse(homeNextTime) }.getOrNull()
                }

                val baseTime = baseTimeFromLastSet ?: legacyBaseTime ?: now
                var futureTime = baseTime.plusMinutes(intervalMinutes)
                while (!futureTime.isAfter(now)) {
                    futureTime = futureTime.plusMinutes(intervalMinutes)
                }
                futureTime
            }
        }

        val normalized = if (type == Type.HOME && wallpaperAlarmItem.scheduleSeparately) {
            nextTime.withSecond(10).withNano(0)
        } else {
            nextTime.withSecond(0).withNano(0)
        }

        return if (normalized.isAfter(now)) {
            normalized
        } else {
            normalized.plusMinutes(1)
        }
    }

    private fun changeWallpaperImmediate(wallpaperAlarmItem: WallpaperAlarmItem, type: Type) {
        val serviceConfig = ServiceConfig(
            homeInterval = wallpaperAlarmItem.homeInterval,
            lockInterval = wallpaperAlarmItem.lockInterval,
            scheduleSeparately = wallpaperAlarmItem.scheduleSeparately
        )

        when (type.ordinal) {
            Type.SINGLE.ordinal -> {
                if (wallpaperAlarmItem.setLock) startService(context, LockWallpaperService::class.java, WallpaperAction.START, serviceConfig.copy(type = Type.SINGLE.ordinal))
                if (wallpaperAlarmItem.setHome) startService(context, HomeWallpaperService::class.java, WallpaperAction.START, serviceConfig.copy(type = Type.SINGLE.ordinal))
            }
            Type.LOCK.ordinal -> startService(context, LockWallpaperService::class.java, WallpaperAction.START, serviceConfig.copy(type = Type.LOCK.ordinal))
            Type.HOME.ordinal -> startService(context, HomeWallpaperService::class.java, WallpaperAction.START, serviceConfig.copy(type = Type.HOME.ordinal))
        }
    }

    private fun startService(context: Context, serviceClass: Class<*>, action: WallpaperAction, config: ServiceConfig?) {
        val serviceIntent = Intent(context, serviceClass).apply {
            this.action = action.javaClass.simpleName
            config?.let {
                it.homeInterval?.let { interval -> putExtra("homeInterval", interval) }
                it.lockInterval?.let { interval -> putExtra("lockInterval", interval) }
                it.scheduleSeparately?.let { separate -> putExtra("scheduleSeparately", separate) }
                it.type?.let { type -> putExtra("type", type) }
            }
        }
        context.startForegroundService(serviceIntent)
    }

    private fun postNotification(nextSetTime: LocalDateTime) {
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        val formattedNextSetTime = nextSetTime.format(formatter)
        val mainActivityIntent = Intent(context, MainActivity::class.java)
        val pendingMainActivityIntent = PendingIntent.getActivity(
            context, 3, mainActivityIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, "wallpaper_service_channel").apply {
            setContentTitle(context.getString(R.string.app_name))
            setContentText(context.getString(R.string.next_wallpaper_change, formattedNextSetTime))
            setSmallIcon(R.drawable.notification_icon)
            setContentIntent(pendingMainActivityIntent)
            priority = NotificationCompat.PRIORITY_DEFAULT
            setOnlyAlertOnce(true)
        }.build()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, notification)
    }

    private fun createWallpaperIntent(wallpaperAlarmItem: WallpaperAlarmItem, type: Type, origin: Int?): Intent {
        return Intent(context, WallpaperReceiver::class.java).apply {
            putExtra("homeInterval", wallpaperAlarmItem.homeInterval)
            putExtra("lockInterval", wallpaperAlarmItem.lockInterval)
            putExtra("setHome", wallpaperAlarmItem.setHome)
            putExtra("setLock", wallpaperAlarmItem.setLock)
            putExtra("scheduleSeparately", wallpaperAlarmItem.scheduleSeparately)
            putExtra("origin", origin)
            putExtra("type", type.ordinal)
            putExtra("changeStartTime", wallpaperAlarmItem.changeStartTime)
            putExtra("startTime", intArrayOf(wallpaperAlarmItem.startTime.first, wallpaperAlarmItem.startTime.second))
        }
    }

    @RequiresPermission(Manifest.permission.SCHEDULE_EXACT_ALARM)
    private fun scheduleExactAlarm(nextTime: LocalDateTime, intent: Intent) {
        val requestCode = intent.getIntExtra("type", 0)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val triggerAtMillis = nextTime.atZone(ZoneId.systemDefault()).toEpochSecond() * 1000
        Log.d(TAG, "Exact alarm scheduled type=$requestCode at=$nextTime")
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent
        )
    }

    private fun cancelAlarm(type: Type) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            type.ordinal,
            Intent(context, WallpaperReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    override fun updateWallpaperAlarm(wallpaperAlarmItem: WallpaperAlarmItem, firstLaunch: Boolean) {}

    override fun updateWallpaper(scheduleSeparately: Boolean, setHome: Boolean, setLock: Boolean) {
        if (scheduleSeparately) {
            updateWallpaper(Type.LOCK, setHome, setLock)
            updateWallpaper(Type.HOME, setHome, setLock)
        } else {
            updateWallpaper(Type.SINGLE, setHome, setLock)
        }
    }

    private fun updateWallpaper(type: Type, setHome: Boolean, setLock: Boolean) {
        when (type.ordinal) {
            Type.SINGLE.ordinal -> {
                if (setLock) startService(context, LockWallpaperService::class.java, WallpaperAction.UPDATE, null)
                if (setHome) startService(context, HomeWallpaperService::class.java, WallpaperAction.UPDATE, null)
            }
            Type.LOCK.ordinal -> startService(context, LockWallpaperService::class.java, WallpaperAction.UPDATE, null)
            Type.HOME.ordinal -> startService(context, HomeWallpaperService::class.java, WallpaperAction.UPDATE, null)
        }
    }

    override fun cancelWallpaperAlarm(cancelLock: Boolean, cancelHome: Boolean) {
        if (cancelLock) cancelAlarm(Type.LOCK)
        if (cancelHome) cancelAlarm(Type.HOME)
        if (cancelLock && cancelHome) {
            cancelAlarm(Type.SINGLE)
            cancelAlarm(Type.REFRESH)
        }
    }
}