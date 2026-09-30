package com.liziwa.hisense_autorefresh

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import com.liziwa.hisense_autorefresh.util.NotificationUtils
import com.liziwa.hisense_autorefresh.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 开机广播接收器：设备启动完成后，若用户此前开启过服务（serviceState），
 * 延迟 5 秒后检查无障碍服务状态——已启用则自启服务，否则弹错误通知引导用户开启。
 */
class BootReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        XLog.d("BootReceiver: onReceive 开机完成")
        // 仅当用户上次开启了服务才尝试自启
        if (!AppPreferences.getInstance(context).serviceState) {
            XLog.d("BootReceiver: serviceState=false，跳过自启")
            return
        }
        // goAsync：延长广播生命周期，确保延迟检查能跑完（否则进程可能在 delay 期间被回收）
        val pendingResult = goAsync()
        scope.launch {
            try {
                delay(5000) // 延迟等待系统就绪再检查，避免过早 startForeground 失败
                // 开机后系统无障碍服务可能延迟就绪，最多检查两轮（间隔 5s），避免误报"未开启"
                var enabled = Utils.isAccessibilityServiceEnabled(context)
                if (!enabled) {
                    XLog.d("BootReceiver: 首轮检测未启用，5 秒后重试")
                    delay(5000)
                    enabled = Utils.isAccessibilityServiceEnabled(context)
                }
                checkAccessibilityService(context, enabled)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun checkAccessibilityService(context: Context, enabled: Boolean) {
        XLog.d("BootReceiver: checkAccessibilityService enabled=$enabled, 服务已连接=${EInkAccessibilityService.isRunning}")
        if (!enabled) {
            XLog.w("BootReceiver: 无障碍服务未启用，弹通知提醒")
            showEnableServiceNotification(context)
        } else {
            // 已启用：启动前台服务（保证通知与周期刷新可用；无障碍本身由系统 bind）
            XLog.i("BootReceiver: 无障碍服务已启用，启动前台服务")
            val serviceIntent = Intent(context, EInkAccessibilityService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }

    private fun showEnableServiceNotification(context: Context) {
        NotificationUtils.getInstance(context).createErrorNotificationChannel()
        NotificationUtils.getInstance(context).showServiceFailedNotification()
    }
}