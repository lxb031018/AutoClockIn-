package me.lxb.autoclockin.backgroundwork

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lxb.autoclockin.MainActivity
import me.lxb.autoclockin.R
import me.lxb.autoclockin.data.SaveReadTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class AlarmReceiver : BroadcastReceiver() {
    /**
     * 接收闹钟广播并执行到点逻辑。
     * 当前逻辑：打印日志，然后继续安排下一次触发。
     *
     * @param context 用于读取保存时间和继续调度
     * @param intent AlarmManager 到点时发送的广播 Intent
     */
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AlarmReceiver", "已收到闹钟广播，开始执行")
        try {
            val (hour, minute) = runBlocking {
                SaveReadTime.readTime(context).first()
            }
            val now = LocalDateTime.now()
            Log.d("AlarmReceiver", "闹钟触发：当前时间=$now，目标时间=$hour:$minute")
            AlarmNotifier.handleTargetTimeTrigger(context, hour, minute)
            AlarmScheduler.scheduleNext(context, hour, minute)
        } catch (e: Exception) {
            Log.e("AlarmReceiver", "闹钟触发后执行失败", e)
        }
    }
}

class ClockInActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmNotifier.ACTION_CLOCK_IN_NOW -> {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(launchIntent)
                runBlocking { SaveReadTime.clearPendingUnlockNotification(context) }
                AlarmNotifier.cancelUnlockFallbackCheck(context)
                AlarmNotifier.cancelLockScreenNotification(context)
                NotificationManagerCompat.from(context).cancel(AlarmNotifier.NOTIFICATION_ID)
            }
            AlarmNotifier.ACTION_CLOCK_IN_LATER -> {
                runBlocking { SaveReadTime.clearPendingUnlockNotification(context) }
                AlarmNotifier.cancelUnlockFallbackCheck(context)
                AlarmNotifier.cancelLockScreenNotification(context)
                AlarmNotifier.scheduleSnooze(context, 10)
                NotificationManagerCompat.from(context).cancel(AlarmNotifier.NOTIFICATION_ID)
            }
        }
    }
}

class UnlockNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return
        AlarmNotifier.tryNotifyPendingWhenUnlocked(context, false, "UnlockNotificationReceiver")
    }
}

class UnlockFallbackCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmNotifier.tryNotifyPendingWhenUnlocked(context, true, "UnlockFallbackCheckReceiver")
    }
}

class SnoozeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val (hour, minute) = runBlocking {
            SaveReadTime.readTime(context).first()
        }
        AlarmNotifier.showAtTargetTime(context, hour, minute)
    }
}

object AlarmScheduler {
    private const val REQUEST_CODE = 1001
    private const val TAG = "AlarmScheduler"

    /**
     * 按目标时间安排下一次精确闹钟。
     * 使用 setExactAndAllowWhileIdle，尽量在 Doze 下也按时触发。
     *
     * @param context 用于获取 AlarmManager 和创建 PendingIntent
     * @param hour 目标小时
     * @param minute 目标分钟
     */
    fun scheduleNext(context: Context, hour: Int, minute: Int) {
        val triggerAtMillis = nextTriggerAtMillis(hour, minute)

        val alarmManager = context
            .getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "未授予精确闹钟权限，跳过本次调度")
            return
        }

        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerLocal = Instant.ofEpochMilli(triggerAtMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        val now = LocalDateTime.now()

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Log.d(
                TAG,
                "已完成调度：当前时间=$now，下一次触发=$triggerLocal，目标时间=$hour:$minute"
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "精确闹钟调度失败", e)
        }
    }

    /**
     * 计算“下一次触发”的时间戳。
     * 如果今天目标时间已过，则自动安排到明天同一时间。
     *
     * @param hour 目标小时
     * @param minute 目标分钟
     * @return 下一次触发时间（毫秒时间戳）
     */
    private fun nextTriggerAtMillis(hour: Int, minute: Int): Long {
        val now = LocalDateTime.now()
        val todayTarget = now.toLocalDate().atTime(hour, minute)

        val next = if (todayTarget.isAfter(now)) todayTarget
                   else todayTarget.plusDays(1)

        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

object AlarmNotifier {
    const val CHANNEL_ID = "clock_in_alarm_channel_heads_up_v2"
    const val NOTIFICATION_ID = 3001
    const val ACTION_CLOCK_IN_NOW = "me.lxb.autoclockin.action.CLOCK_IN_NOW"
    const val ACTION_CLOCK_IN_LATER = "me.lxb.autoclockin.action.CLOCK_IN_LATER"
    private const val SNOOZE_REQUEST_CODE = 3002
    private const val UNLOCK_FALLBACK_REQUEST_CODE = 3007
    private const val UNLOCK_FALLBACK_INTERVAL_MILLIS = 30_000L
    private const val CHANNEL_NAME = "到点提醒"
    private const val CHANNEL_DESC = "用于显示到点锁屏提醒"
    private val LEGACY_CHANNEL_IDS = listOf("clock_in_alarm_channel")

    fun showAtTargetTime(context: Context, hour: Int, minute: Int) {
        showNotification(context, hour, minute, NOTIFICATION_ID)
    }

    fun showOnLockScreen(context: Context, hour: Int, minute: Int) {
        showNotification(context, hour, minute, NOTIFICATION_ID)
    }

    fun handleTargetTimeTrigger(context: Context, hour: Int, minute: Int) {
        if (isDeviceLocked(context)) {
            runBlocking { SaveReadTime.markPendingUnlockNotification(context, hour, minute) }
            showOnLockScreen(context, hour, minute)
            scheduleUnlockFallbackCheck(context)
            Log.d("AlarmNotifier", "设备锁屏，延迟到解锁后再弹出提醒")
            return
        }
        runBlocking { SaveReadTime.clearPendingUnlockNotification(context) }
        cancelUnlockFallbackCheck(context)
        cancelLockScreenNotification(context)
        showAtTargetTime(context, hour, minute)
    }

    fun tryNotifyPendingWhenUnlocked(
        context: Context,
        rescheduleIfLocked: Boolean,
        logTag: String
    ) {
        runBlocking {
            val pending = SaveReadTime.readPendingUnlockNotification(context).first()
            if (!pending.pending) return@runBlocking
            if (isDeviceLocked(context)) {
                if (rescheduleIfLocked) {
                    scheduleUnlockFallbackCheck(context)
                }
                return@runBlocking
            }
            cancelLockScreenNotification(context)
            showAtTargetTime(context, pending.hour, pending.minute)
            SaveReadTime.clearPendingUnlockNotification(context)
            cancelUnlockFallbackCheck(context)
            Log.d(logTag, "检测到解锁，已弹出到点提醒")
        }
    }

    private fun showNotification(context: Context, hour: Int, minute: Int, notificationId: Int) {
        ensureChannel(context)
        if (!hasNotificationPermission(context)) {
            Log.w("AlarmNotifier", "通知权限未授予，跳过到点通知")
            return
        }

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            2001,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nowActionIntent = Intent(context, ClockInActionReceiver::class.java).apply {
            action = ACTION_CLOCK_IN_NOW
        }
        val nowActionPendingIntent = PendingIntent.getBroadcast(
            context,
            2003,
            nowActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val laterActionIntent = Intent(context, ClockInActionReceiver::class.java).apply {
            action = ACTION_CLOCK_IN_LATER
        }
        val laterActionPendingIntent = PendingIntent.getBroadcast(
            context,
            2004,
            laterActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentText("已到$hour:$minute，请执行打卡")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "立即打卡", nowActionPendingIntent)
            .addAction(0, "稍后打卡", laterActionPendingIntent)
        val notification = builder.build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.e("AlarmNotifier", "到点通知发送失败", e)
        }
    }

    fun ensureChannelReady(context: Context) {
        ensureChannel(context)
    }

    fun scheduleSnooze(context: Context, minutes: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!alarmManager.canScheduleExactAlarms()) return
        val triggerAtMillis = System.currentTimeMillis() + minutes * 60_000L
        val intent = Intent(context, SnoozeAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            SNOOZE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Log.d("AlarmNotifier", "稍后提醒已设置，$minutes 分钟后再次提醒")
        } catch (e: SecurityException) {
            Log.e("AlarmNotifier", "稍后提醒设置失败", e)
        }
    }

    fun scheduleUnlockFallbackCheck(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!alarmManager.canScheduleExactAlarms()) return
        val triggerAtMillis = System.currentTimeMillis() + UNLOCK_FALLBACK_INTERVAL_MILLIS
        val intent = Intent(context, UnlockFallbackCheckReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            UNLOCK_FALLBACK_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } catch (e: SecurityException) {
            Log.e("AlarmNotifier", "解锁兜底调度失败", e)
        }
    }

    fun cancelUnlockFallbackCheck(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, UnlockFallbackCheckReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            UNLOCK_FALLBACK_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun cancelLockScreenNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        cleanupLegacyChannels(manager)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESC
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 150, 250)
        }
        manager.createNotificationChannel(channel)
    }

    private fun cleanupLegacyChannels(manager: NotificationManager) {
        LEGACY_CHANNEL_IDS.forEach { legacyId ->
            manager.getNotificationChannel(legacyId) ?: return@forEach
            manager.deleteNotificationChannel(legacyId)
        }
    }

    private fun isDeviceLocked(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguardManager.isDeviceLocked
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
