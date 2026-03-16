package me.lxb.autoclockin.backgroundwork

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.lxb.autoclockin.data.SaveReadTime

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        try {
            val (hour, minute) = runBlocking {
                SaveReadTime.readTime(context).first()
            }
            AlarmScheduler.scheduleNext(context, hour, minute)
            Log.d("BootReceiver", "开机恢复调度成功：目标时间=$hour:$minute")
        } catch (e: Exception) {
            Log.e("BootReceiver", "开机恢复调度失败", e)
        }
    }
}
