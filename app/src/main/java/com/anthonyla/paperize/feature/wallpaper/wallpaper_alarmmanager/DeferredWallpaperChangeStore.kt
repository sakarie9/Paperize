package com.anthonyla.paperize.feature.wallpaper.wallpaper_alarmmanager

import android.content.Context
import android.util.Log
import androidx.core.content.edit

data class DeferredWallpaperChange(
    val target: String,
    val homeInterval: Int,
    val lockInterval: Int,
    val scheduleSeparately: Boolean,
    val type: Int,
    val createdAt: Long = System.currentTimeMillis()
)

object DeferredWallpaperChangeStore {
    private const val TAG = "DeferredWallpaperStore"
    private const val PREFS_NAME = "deferred_wallpaper_change"
    private const val KEY_TARGET = "target"
    private const val KEY_HOME_INTERVAL = "home_interval"
    private const val KEY_LOCK_INTERVAL = "lock_interval"
    private const val KEY_SCHEDULE_SEPARATELY = "schedule_separately"
    private const val KEY_TYPE = "type"
    private const val KEY_CREATED_AT = "created_at"

    const val TARGET_HOME = "home"
    const val TARGET_LOCK = "lock"

    fun save(context: Context, change: DeferredWallpaperChange) {
        Log.d(TAG, "Deferred request saved: target=${change.target}, type=${change.type}")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_TARGET, change.target)
            putInt(KEY_HOME_INTERVAL, change.homeInterval)
            putInt(KEY_LOCK_INTERVAL, change.lockInterval)
            putBoolean(KEY_SCHEDULE_SEPARATELY, change.scheduleSeparately)
            putInt(KEY_TYPE, change.type)
            putLong(KEY_CREATED_AT, change.createdAt)
        }
    }

    fun consume(context: Context): DeferredWallpaperChange? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val target = prefs.getString(KEY_TARGET, null) ?: return null
        val change = DeferredWallpaperChange(
            target = target,
            homeInterval = prefs.getInt(KEY_HOME_INTERVAL, 15),
            lockInterval = prefs.getInt(KEY_LOCK_INTERVAL, 15),
            scheduleSeparately = prefs.getBoolean(KEY_SCHEDULE_SEPARATELY, false),
            type = prefs.getInt(KEY_TYPE, 0),
            createdAt = prefs.getLong(KEY_CREATED_AT, System.currentTimeMillis())
        )
        Log.d(TAG, "Deferred request consumed: target=${change.target}, type=${change.type}")
        clear(context)
        return change
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
    }
}
