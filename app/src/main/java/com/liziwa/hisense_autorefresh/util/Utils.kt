package com.liziwa.hisense_autorefresh.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
import android.content.ComponentName
import android.content.Context
import android.content.Context.ACCESSIBILITY_SERVICE
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import com.elvishew.xlog.XLog
import com.liziwa.hisense_autorefresh.EInkAccessibilityService
import java.lang.reflect.InvocationTargetException

/**
 * 通用工具类：无障碍服务状态检测、墨水屏强制刷新、已安装应用枚举。
 */
object Utils {

    /**
     * 判断本应用的无障碍服务是否已在系统设置中开启。
     *
     * 说明：不能只依赖 AccessibilityManager.getEnabledAccessibilityServiceList()。
     * 在部分定制 ROM（如海信 A5Pro）上，开机后系统尚未真正 bind 服务时该列表返回空，
     * 导致 App 误判为"未开启"，必须去设置里重新开关一次才恢复。
     * 因此改为三重判定，任一命中即视为已开启：
     *   1. 服务自身运行标志（onServiceConnected 置位，最可靠但需系统已 bind）
     *   2. Settings.Secure 的 enabled_accessibility_services（直接反映开关状态，开机即生效）
     *   3. AccessibilityManager 已启用服务列表（原逻辑，作为兜底）
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val component = ComponentName(context, EInkAccessibilityService::class.java)
        val shortId = component.flattenToShortString()  // 形如 pkg/.EInkAccessibilityService
        val fullId = component.flattenToString()        // 形如 pkg/com.xxx.EInkAccessibilityService

        val bySelfFlag = EInkAccessibilityService.isRunning
        val bySecure = isEnabledInSecureSettings(context, shortId, fullId)
        val byManager = isEnabledInAccessibilityManager(context, shortId, fullId)

        val enabled = bySelfFlag || bySecure || byManager
        XLog.d(
            "isAccessibilityServiceEnabled: 结果=$enabled " +
                    "(服务标志=$bySelfFlag, 设置项=$bySecure, 管理器=$byManager), shortId=$shortId"
        )
        return enabled
    }

    /**
     * 从 Settings.Secure 读取已启用的无障碍服务列表并匹配本服务。
     * 该值就是「设置 → 无障碍」里那个开关的真实落盘值，开机后立即可读，不受 bind 时机影响。
     */
    private fun isEnabledInSecureSettings(context: Context, vararg ids: String): Boolean {
        return try {
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (raw.isNullOrEmpty()) {
                XLog.d("isEnabledInSecureSettings: enabled_accessibility_services 为空")
                return false
            }
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(raw)
            var hit = false
            while (splitter.hasNext()) {
                val item = splitter.next().trim()
                if (item.isEmpty()) continue
                if (ids.any { it.equals(item, ignoreCase = true) }) {
                    hit = true
                    break
                }
            }
            XLog.d("isEnabledInSecureSettings: raw=$raw, 命中=$hit")
            hit
        } catch (e: Exception) {
            XLog.e("isEnabledInSecureSettings: 读取设置失败", e)
            false
        }
    }

    /** 通过 AccessibilityManager 的已启用服务列表判断（原逻辑，兜底用） */
    private fun isEnabledInAccessibilityManager(
        context: Context,
        vararg ids: String
    ): Boolean {
        return try {
            val am = context.getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val list = am?.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)
            val hit = list?.any { info ->
                ids.any { it.equals(info.id, ignoreCase = true) }
            } ?: false
            XLog.d("isEnabledInAccessibilityManager: 列表=${list?.map { it.id }}, 命中=$hit")
            hit
        } catch (e: Exception) {
            XLog.e("isEnabledInAccessibilityManager: 查询失败", e)
            false
        }
    }

    /**
     * 强制全屏刷新墨水屏。海信设备通过反射调用厂商 EPD 接口 com.hmct.epd.EpdManager.forceClear()。
     * 不同异常代表不同失败原因：找不到类/方法（error1）、方法调用抛异常（error2）。
     */
    fun refreshScreen(context: Context) {
        XLog.i("refreshScreen: 请求强制刷新墨水屏")
        try {
            Class.forName("com.hmct.epd.EpdManager")
                .getMethod("forceClear")
                .invoke(context.getSystemService("epd"))
            XLog.i("refreshScreen: 刷新调用成功")
        } catch (ex: ReflectiveOperationException) {
            // 未找到 EPD 类或方法，可能非海信设备/系统版本不同
            XLog.e("refreshScreen: 未找到 EPD 接口（error1）", ex)
        } catch (ex: InvocationTargetException) {
            // 找到了方法但执行抛异常
            XLog.e("refreshScreen: 刷新执行异常（error2）", ex)
        }
    }

    /**
     * 获取所有有界面的应用（排除纯服务应用）。
     * 处理 Android 11+ 包可见性限制：优先 MATCH_ALL，异常时回退到 <queries> 声明的范围。
     */
    fun getLauncherApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        return when {
            // Android 13+ 需要特殊处理包可见性
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                getLauncherAppsApi33(context, mainIntent)
            }

            else -> {
                getLauncherAppsLegacy(pm, mainIntent)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getLauncherAppsLegacy(
        pm: PackageManager,
        intent: Intent
    ): List<AppInfo> {
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { resolveToAppInfo(pm, it) }
            .distinctBy { it.packageName } // 去重
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun getLauncherAppsApi33(
        context: Context,
        intent: Intent
    ): List<AppInfo> {
        val pm = context.packageManager
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())

        return try {
            pm.queryIntentActivities(intent, flags)
                .mapNotNull { resolveToAppInfo(pm, it) }
                .distinctBy { it.packageName }
        } catch (e: SecurityException) {
            // 处理包可见性限制
            if (hasQueryAllPackagesPermission(context)) {
                // 如果有权限但仍有异常，回退到旧方法
                getLauncherAppsLegacy(pm, intent)
            } else {
                // 无权限时尝试使用<queries>声明
                getLauncherAppsViaQueries(pm, intent)
            }
        }
    }

    private fun getLauncherAppsViaQueries(
        pm: PackageManager,
        intent: Intent
    ): List<AppInfo> {
        return try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { resolveToAppInfo(pm, it) }
                .distinctBy { it.packageName }
        } catch (e: Exception) {
            emptyList() // 安全回退
        }
    }

    private fun resolveToAppInfo(
        pm: PackageManager,
        resolveInfo: ResolveInfo
    ): AppInfo? {
        return try {
            val appInfo = resolveInfo.activityInfo.applicationInfo
            AppInfo(
                packageName = appInfo.packageName,
                name = resolveInfo.loadLabel(pm).toString(),
                icon = resolveInfo.loadIcon(pm),
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        } catch (e: Exception) {
            null // 忽略无效条目
        }
    }

    private fun hasQueryAllPackagesPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true // 低版本不需要此权限
        }
    }

    /**
     * 判断指定包名是否为系统应用（预装）。
     * 通过 ApplicationInfo.FLAG_SYSTEM 标志识别，系统应用默认不参与读屏判定。
     */
    fun isSystemApp(context: Context, packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            // 查不到包信息（如已卸载），保守视为非系统应用
            XLog.w("isSystemApp: 未找到包名 $packageName，按非系统处理", e)
            false
        }
    }

    data class AppInfo(
        val packageName: String,
        val name: String,
        val icon: Drawable,
        val isSystem: Boolean = false
    )
}