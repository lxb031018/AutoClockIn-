package me.lxb.autoclockin

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import me.lxb.autoclockin.backgroundwork.AlarmNotifier
import me.lxb.autoclockin.backgroundwork.AlarmScheduler
import me.lxb.autoclockin.data.SaveReadTime
import me.lxb.autoclockin.ui.theme.AutoClockInTheme

class MainActivity : ComponentActivity() {
    /**
     * Activity 入口。
     * 作用：启动后加载 Compose 的主界面 MainUi。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoClockInTheme {
                MainUi()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * 主界面函数。
 * 作用：
 * 1) 展示 TimePicker 让用户选择时间
 * 2) 点击确认后保存时间
 * 3) 检查精确闹钟权限，通过后安排下一次闹钟
 * 4) 读取已保存时间并回填到 TimePicker
 */
fun MainUi() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var permissionMenuExpanded by remember { mutableStateOf(false) }
    val state = rememberTimePickerState(
        initialHour = 0,
        initialMinute = 0,
        is24Hour = true
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        TimePicker(
            state = state,
            modifier = Modifier
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ElevatedButton(
                    onClick = { permissionMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("权限")
                }
                DropdownMenu(
                    expanded = permissionMenuExpanded,
                    onDismissRequest = { permissionMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("开启自启动") },
                        onClick = {
                            permissionMenuExpanded = false
                            openAutoStartSettings(context)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("关闭电池优化") },
                        onClick = {
                            permissionMenuExpanded = false
                            openBatteryOptimizationSettings(context)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("悬浮、锁屏通知") },
                        onClick = {
                            permissionMenuExpanded = false
                            openChannelNotificationSettings(context)
                        }
                    )
                }
            }
            ElevatedButton(
                onClick = {}
            ) {
                Text("立即打卡")
            }
            ElevatedButton(
                onClick = {
                    scope.launch {
                        SaveReadTime.saveTime(context, state.hour, state.minute)
                        if (!canScheduleExactAlarms(context)) {
                            requestExactAlarmPermission(context)
                            return@launch
                        }
                        if (!ensureNotificationPermission(context)) {
                            return@launch
                        }
                        AlarmScheduler.scheduleNext(context, state.hour, state.minute)
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("确认")
            }
        }
    }
    LaunchedEffect(Unit) {
        SaveReadTime.readTime(context).collect { time ->
            state.hour = time.first
            state.minute = time.second
            Log.d("SaveTime", "已保存时间：${time.first}:${time.second}")
        }
    }
}

/**
 * 检查当前应用是否具备精确闹钟权限。
 *
 * @param context 用于获取 AlarmManager 系统服务
 * @return true 表示可以调用 setExactAndAllowWhileIdle
 */
private fun canScheduleExactAlarms(context: Context): Boolean {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return alarmManager.canScheduleExactAlarms()
}

/**
 * 打开系统设置页，请求用户授予精确闹钟权限。
 *
 * @param context 用于启动权限设置页面
 */
private fun requestExactAlarmPermission(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun openAutoStartSettings(context: Context) {
    val brand = Build.BRAND.lowercase()
    val candidates = mutableListOf<Intent>()

    when {
        brand.contains("xiaomi") || brand.contains("redmi") -> {
            candidates += componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        }
        brand.contains("huawei") -> {
            candidates += componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            candidates += componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
        }
        brand.contains("honor") -> {
            candidates += componentIntent("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            candidates += componentIntent("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity")
            candidates += componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        }
        brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") -> {
            candidates += componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            candidates += componentIntent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
            candidates += componentIntent("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity")
            candidates += componentIntent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
        }
        brand.contains("vivo") || brand.contains("iqoo") -> {
            candidates += componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            candidates += componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
            candidates += componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
        }
        brand.contains("lenovo") || brand.contains("zuk") || brand.contains("zui") -> {
            candidates += componentIntent("com.zui.safecenter", "com.zui.safecenter.MainActivity")
            candidates += componentIntent("com.zui.safecenter", "com.zui.safecenter.permission.PermissionAppListActivity")
            candidates += componentIntent("com.lenovo.safecenter", "com.lenovo.safecenter.MainTab.LeSafeMainActivity")
        }
    }

    candidates += listOf(
        componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        componentIntent("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        componentIntent("com.zui.safecenter", "com.zui.safecenter.MainActivity"),
        componentIntent("com.lenovo.safecenter", "com.lenovo.safecenter.MainTab.LeSafeMainActivity")
    )

    candidates += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    candidates += Intent(Settings.ACTION_SETTINGS)

    val opened = candidates.any { safeStartActivity(context, it) }
    if (!opened) {
        Toast.makeText(context, "无法直接跳转，请手动开启自启动", Toast.LENGTH_SHORT).show()
    }
}

private fun componentIntent(pkg: String, cls: String): Intent {
    return Intent().setComponent(ComponentName(pkg, cls))
}

private fun openBatteryOptimizationSettings(context: Context) {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
        Toast.makeText(context, "已关闭电池优化", Toast.LENGTH_SHORT).show()
        return
    }

    val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    if (safeStartActivity(context, directIntent)) return

    val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    if (safeStartActivity(context, listIntent)) return

    safeStartActivity(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    )
}

private fun safeStartActivity(context: Context, intent: Intent): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val resolved = intent.resolveActivity(context.packageManager) ?: return false
    return runCatching {
        context.startActivity(intent)
    }.isSuccess
}

private fun ensureNotificationPermission(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        openAppNotificationSettings(context)
        Toast.makeText(context, "请将通知权限设置为“始终允许”后再点击确认", Toast.LENGTH_SHORT).show()
        return false
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return true
    }
    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        return true
    }
    val activity = context as? ComponentActivity
    if (activity == null) {
        openAppNotificationSettings(context)
        Toast.makeText(context, "请将通知权限设置为“始终允许”后再点击确认", Toast.LENGTH_SHORT).show()
        return false
    }
    ActivityCompat.requestPermissions(
        activity,
        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
        1002
    )
    Toast.makeText(context, "请先授予通知权限，再点击确认", Toast.LENGTH_SHORT).show()
    return false
}

private fun ensureLockScreenNotificationContent(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        openChannelNotificationSettings(context)
        Toast.makeText(context, "请先开启通知并允许锁屏显示通知内容", Toast.LENGTH_SHORT).show()
        return false
    }
    AlarmNotifier.ensureChannelReady(context)
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = manager.getNotificationChannel(AlarmNotifier.CHANNEL_ID) ?: return true
    if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
        openChannelNotificationSettings(context)
        Toast.makeText(context, "请开启“到点提醒”通知", Toast.LENGTH_SHORT).show()
        return false
    }
    if (channel.lockscreenVisibility != Notification.VISIBILITY_PUBLIC) {
        openChannelNotificationSettings(context)
        Toast.makeText(context, "请在“到点提醒”中开启锁屏显示通知内容", Toast.LENGTH_SHORT).show()
        return false
    }
    return true
}

private fun openChannelNotificationSettings(context: Context) {
    AlarmNotifier.ensureChannelReady(context)
    val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, AlarmNotifier.CHANNEL_ID)
    }
    if (safeStartActivity(context, channelIntent)) return

    val appIntentForOldRom = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra("app_package", context.packageName)
        putExtra("app_uid", Process.myUid())
        putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
    }
    if (safeStartActivity(context, appIntentForOldRom)) return

    val appIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    if (safeStartActivity(context, appIntent)) return

    val opened = safeStartActivity(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    )
    if (!opened) {
        Toast.makeText(context, "无法自动跳转，请手动打开系统通知设置", Toast.LENGTH_SHORT).show()
    }
}

private fun openAppNotificationSettings(context: Context) {
    val appIntentForOldRom = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra("app_package", context.packageName)
        putExtra("app_uid", Process.myUid())
        putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
    }
    if (safeStartActivity(context, appIntentForOldRom)) return

    val appIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    if (safeStartActivity(context, appIntent)) return

    safeStartActivity(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    )
}
