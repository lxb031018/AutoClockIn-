package me.lxb.autoclockin.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

val Context.clockInTimeDataStore by preferencesDataStore(name = "save_time")

object SaveReadTime {
    private val KEY_HOUR = intPreferencesKey("time_hour")
    private val KEY_MINUTE = intPreferencesKey("time_minute")
    private val KEY_PENDING_NOTIFY = booleanPreferencesKey("pending_notify_after_unlock")
    private val KEY_PENDING_HOUR = intPreferencesKey("pending_notify_hour")
    private val KEY_PENDING_MINUTE = intPreferencesKey("pending_notify_minute")
    private val KEY_PENDING_TRIGGER_TIME = longPreferencesKey("pending_notify_trigger_time")
    private const val DEFAULT_HOUR = 0
    private const val DEFAULT_MINUTE = 0

    /**
     * 保存用户选择的打卡时间到 DataStore。
     *
     * @param context 用于访问 DataStore
     * @param hour 用户选择的小时（24 小时制）
     * @param minute 用户选择的分钟
     */
    suspend fun saveTime(context: Context, hour: Int, minute: Int) {
        context.clockInTimeDataStore.edit { preferences ->
            preferences[KEY_HOUR] = hour
            preferences[KEY_MINUTE] = minute
        }
    }

    /**
     * 读取已保存时间，并以 Flow 持续输出最新值。
     * 当本地没有历史值时，返回默认值 0:0。
     *
     * @param context 用于访问 DataStore
     * @return Pair(hour, minute) 的数据流
     */
    fun readTime(context: Context): Flow<Pair<Int, Int>> {
        return context.clockInTimeDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { preferences ->
                val hour = preferences[KEY_HOUR] ?: DEFAULT_HOUR
                val minute = preferences[KEY_MINUTE] ?: DEFAULT_MINUTE
            hour to minute
        }
    }

    suspend fun markPendingUnlockNotification(context: Context, hour: Int, minute: Int) {
        context.clockInTimeDataStore.edit { preferences ->
            preferences[KEY_PENDING_NOTIFY] = true
            preferences[KEY_PENDING_HOUR] = hour
            preferences[KEY_PENDING_MINUTE] = minute
            preferences[KEY_PENDING_TRIGGER_TIME] = System.currentTimeMillis()
        }
    }

    suspend fun clearPendingUnlockNotification(context: Context) {
        context.clockInTimeDataStore.edit { preferences ->
            preferences[KEY_PENDING_NOTIFY] = false
            preferences[KEY_PENDING_HOUR] = DEFAULT_HOUR
            preferences[KEY_PENDING_MINUTE] = DEFAULT_MINUTE
            preferences[KEY_PENDING_TRIGGER_TIME] = 0L
        }
    }

    fun readPendingUnlockNotification(context: Context): Flow<PendingUnlockNotification> {
        return context.clockInTimeDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { preferences ->
                PendingUnlockNotification(
                    pending = preferences[KEY_PENDING_NOTIFY] ?: false,
                    hour = preferences[KEY_PENDING_HOUR] ?: DEFAULT_HOUR,
                    minute = preferences[KEY_PENDING_MINUTE] ?: DEFAULT_MINUTE,
                    triggerAtMillis = preferences[KEY_PENDING_TRIGGER_TIME] ?: 0L
                )
            }
    }
}

data class PendingUnlockNotification(
    val pending: Boolean,
    val hour: Int,
    val minute: Int,
    val triggerAtMillis: Long
)
