package com.liziwa.hisense_autorefresh.util

import android.app.AppOpsManager
import android.content.Context
import android.content.Context.APP_OPS_SERVICE
import android.content.Context.POWER_SERVICE
import android.content.Intent
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import com.elvishew.xlog.XLog
import com.liziwa.hisense_autorefresh.R

/**
 * 运行时权限辅助类：忽略电池优化、使用情况统计两类权限的检测与申请引导。
 * （悬浮窗权限已移除：触摸计数改由无障碍事件完成，不再创建透明悬浮窗）
 */
object PermissionHelper {

    /** 跳转系统设置申请忽略电池优化权限 */
    fun requestIgnoreBatteryOptimizationsPermission(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = "package:${context.packageName}".toUri()
        context.startActivity(intent)
    }

    fun hasIgnoringBatteryOptimizationsPermission(context: Context): Boolean {
        val powerManager = context.getSystemService(POWER_SERVICE) as PowerManager
        val enable = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        XLog.d( "hasIgnoringBatteryOptimizationsPermission: $enable")
        return enable
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        // 检查使用情况统计权限
        val appOps = context.getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), context.packageName
        )
        val enable = mode == AppOpsManager.MODE_ALLOWED
        XLog.d( "hasUsageStatsPermission: $enable")
        return enable
    }

    fun requestUsageStatsPermission(context: Context): AlertDialog {
        XLog.d( "requestUsageStatsPermission: ")
        // 跳转前提示用户
        return AlertDialog.Builder(context)
            .setTitle(R.string.request_permission_usage_title)
            .setMessage(R.string.request_permission_usage_message)
            .setPositiveButton(R.string.btn_to_settings) { dialog, which ->
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                context.startActivity(intent)
            }
            .setNegativeButton(R.string.btn_cancel) { dialog, which ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
}