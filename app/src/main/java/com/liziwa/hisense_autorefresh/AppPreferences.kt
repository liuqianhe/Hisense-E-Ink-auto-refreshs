package com.liziwa.hisense_autorefresh

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.liziwa.hisense_autorefresh.util.SingletonHolder

/**
 * 全局配置存储（SharedPreferences 单例）。
 * 所有开关/阈值均持久化在 "ink_refresh_prefs" 文件中，供 MainActivity 写入、
 * EInkAccessibilityService 通过 ACTION_CONFIG_CHANGE 广播读取。
 */
class AppPreferences private constructor(context: Context) {

    companion object : SingletonHolder<AppPreferences, Context>(::AppPreferences) {
        /** 应用独立刷新配置默认值：触发次数 / 延迟毫秒 */
        const val DEFAULT_APP_INTERVAL = 10
        const val DEFAULT_APP_DELAY = 500
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ink_refresh_prefs", Context.MODE_PRIVATE)


    /** 服务是否曾开启（用于开机自启判断） */
    var serviceState: Boolean
        get() = prefs.getBoolean("service_state", false)
        set(value) = prefs.edit { putBoolean("service_state", value).apply() }

    /** 手动选择的监控应用包名列表，逗号分隔；为空表示仅依赖 monitorGlobal */
    var targetPackageName: String?
        get() = prefs.getString("target_package_name", "")
        set(value) = prefs.edit { putString("target_package_name", value) }


    /** 主开关：是否真正开始监听与刷新 */
    var serviceSwitch: Boolean
        get() = prefs.getBoolean("service_switch", false)
        set(value) = prefs.edit { putBoolean("service_switch", value).apply() }

    /** 触发刷新的操作次数阈值（累计点击/按键达到该值则全屏刷新） */
    var interval: Int
        get() = prefs.getInt("interval", DEFAULT_APP_INTERVAL)
        set(value) = prefs.edit { putInt("interval", value).apply() }

    /**
     * 周期刷新间隔（秒）。0 表示关闭周期刷新；默认 300（5 分钟）。
     * 仅在非锁屏状态下计时，锁屏停止、解锁重置。
     */
    var periodRefresh: Int
        get() = prefs.getInt("period_refresh", 300)
        set(value) = prefs.edit { putInt("period_refresh", value).apply() }

    /**
     * 调试模式开关，默认关闭。
     * 开启后：XLog 输出线程信息/调用栈/边框，并将日志写入文件；
     * 关闭后：仅输出 Logcat，不写文件。
     */
    var debugMode: Boolean
        get() = prefs.getBoolean("debug_mode", false)
        set(value) = prefs.edit { putBoolean("debug_mode", value).apply() }

    /** 达到阈值后延迟刷新时间（毫秒） */
    var delayTime: Int
        get() = prefs.getInt("delay_time", DEFAULT_APP_DELAY)
        set(value) = prefs.edit { putInt("delay_time", value).apply() }

    /** 两次操作的最小间隔（毫秒），小于该间隔视为连点被过滤 */
    var ignoreTime: Int
        get() = prefs.getInt("ignore_time", 2000)
        set(value) = prefs.edit { putInt("ignore_time", value).apply() }

    /** 是否监听触摸操作 */
    var monitorTouch: Boolean
        get() = prefs.getBoolean("monitor_touch", true)
        set(value) = prefs.edit { putBoolean("monitor_touch", value).apply() }

    /** 是否监听按键操作 */
    var monitorKey: Boolean
        get() = prefs.getBoolean("monitor_key", true)
        set(value) = prefs.edit { putBoolean("monitor_key", value).apply() }

    /** 是否监控所有应用（true 时忽略 targetPackageName，对所有前台应用生效） */
    var monitorGlobal: Boolean
        get() = prefs.getBoolean("monitor_global", true)
        set(value) = prefs.edit { putBoolean("monitor_global", value).apply() }

    /** 是否开启“自动识别阅读界面”（读屏统计文字 > 阈值才计次，否则跳过） */
    var autoDetectReading: Boolean
        get() = prefs.getBoolean("auto_detect_reading", true)
        set(value) = prefs.edit { putBoolean("auto_detect_reading", value).apply() }

    /** 阅读白名单：逗号分隔的关键字，包名命中则跳过读屏、直接视为阅读界面（加密小说 App 用） */
    var readingWhitelist: String?
        get() = prefs.getString("reading_whitelist", "qidian")
        set(value) = prefs.edit { putString("reading_whitelist", value) }

    /** 忽略电池优化权限状态：1=已授权, 0=未授权/待申请, -1=初始未判断 */
    var permissionIgnoringBatteryOptimizations: Int
        get() = prefs.getInt("permission_ignoring_battery_optimizations", -1)
        set(value) = prefs.edit { putInt("permission_ignoring_battery_optimizations", value).apply() }

    /**
     * 授权方案：auto=自动（Root 优先，回落 Shizuku）/ root=只用 Root / shizuku=只用 Shizuku。
     * 决定「一键开启无障碍」「保活加白」走哪条提权通道。
     */
    var privilegeScheme: String
        get() = prefs.getString("privilege_scheme", null) ?: "auto"
        set(value) = prefs.edit { putString("privilege_scheme", value).apply() }

    /** 悬浮窗权限状态：1=已授权, 0=未授权/待申请, -1=初始未判断 */
    var permissionOverlay: Int
        get() = prefs.getInt("permission_overlay", -1)
        set(value) = prefs.edit { putInt("permission_overlay", value).apply() }

    /** 使用情况统计权限状态：1=已授权, 0=未授权/待申请, -1=初始未判断 */
    var permissionUsageStats: Int
        get() = prefs.getInt("permission_usage_stats", -1)
        set(value) = prefs.edit { putInt("permission_usage_stats", value).apply() }

    /** 是否在最近任务列表中隐藏本应用 */
    var hideBackgroundTask: Boolean
        get() = prefs.getBoolean("hide_background_task", true)
        set(value) = prefs.edit { putBoolean("hide_background_task", value).apply() }

    /**
     * 各应用独立的刷新配置（触发次数 / 延迟毫秒），格式：pkg:interval:delay,...
     * 仅在「监控所有应用」关闭（按应用列表监控）时生效。
     */
    var appRefreshConfigs: String?
        get() = prefs.getString("app_refresh_configs", null)
        set(value) = prefs.edit { putString("app_refresh_configs", value) }

    /**
     * 获取指定包名的独立刷新配置；未配置或解析失败时返回默认值（10 次 / 2000ms）。
     * @return Pair(触发次数, 延迟毫秒)
     */
    fun getAppRefreshConfig(pkg: String): Pair<Int, Int> {
        val raw = appRefreshConfigs ?: return DEFAULT_APP_INTERVAL to DEFAULT_APP_DELAY
        raw.split(",").forEach { seg ->
            val parts = seg.split(":")
            if (parts.size == 3 && parts[0] == pkg) {
                val iv = parts[1].toIntOrNull() ?: DEFAULT_APP_INTERVAL
                val dv = parts[2].toIntOrNull() ?: DEFAULT_APP_DELAY
                return iv to dv
            }
        }
        return DEFAULT_APP_INTERVAL to DEFAULT_APP_DELAY
    }

}