package com.liziwa.hisense_autorefresh

import com.liziwa.hisense_autorefresh.AppPreferences
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.elvishew.xlog.XLog
import com.liziwa.hisense_autorefresh.util.NotificationUtils
import com.liziwa.hisense_autorefresh.util.Utils

/**
 * 核心无障碍服务：监听前台界面切换与用户操作（触摸/按键），
 * 累计操作次数达到阈值后强制刷新墨水屏。
 *
 * 关键能力：
 * - 自动识别阅读界面：界面切换后读屏统计文字，>阈值才计次（加密 App 走白名单跳过读屏）
 * - 监控范围：全局或指定应用（targetPackageName）/ 阅读白名单（readingWhitelist）
 * - 配置通过 ACTION_CONFIG_CHANGE 广播热更新（见 updateConfig）
 */
class EInkAccessibilityService : AccessibilityService(), View.OnTouchListener {

    private lateinit var prefs: AppPreferences

    private var clickCount = 0
    private var lastClickTime: Long = 0
    private var interval = 10
    private var ignoreTime = 0

    private var delayTime = 0
    private var monitorTouch = true
    private var monitorKey = true
    private var monitorGlobal = true
    private var autoDetectReading = true
    private var readingWhitelist: List<String> = mutableListOf()
    private var currentPackage: String? = null
    private var currentActivity: String? = null
    private var isTarget = false
    private var isReading = false
    private var serviceSwitch = false
    private var choiceApps: List<String> = mutableListOf()
    private var appConfigs: Map<String, Pair<Int, Int>> = emptyMap() // 各应用独立刷新配置（包名 -> 触发次数/延迟）

    private var periodRefresh = 0 // 周期刷新间隔（秒），0 表示关闭
    private var lastRefreshTime = 0L // 上次真正刷新时间（elapsedRealtime），用于周期刷新去重
    private var isScreenLocked = false // 是否处于锁屏/息屏状态
    private var nonReadingOpCount = 0 // 非阅读界面累计操作次数，用于触发重新读屏识别

    private var ignoreApps = arrayOf<String>("com.android.systemui")

    private var addTouchView = false;
    private var serviceConn = false;
    private lateinit var touchView: View

    private val notificationUtils = NotificationUtils.getInstance(this)

    companion object {
        const val ACTION_CONFIG_CHANGE = "com.liziwa.hisense_autorefresh.ACTION_CONFIG_CHANGE"

        /**
         * 服务是否已被系统绑定（onServiceConnected/onUnbind 维护）。
         * 这是判断"无障碍是否真的在工作"最直接的证据：只要系统 bind 过我们，就一定是设置里开了的。
         * 无障碍服务与 App 同进程，故静态标志可直接读取。
         */
        @Volatile
        @JvmStatic
        var isRunning = false

        private const val MSG_REFRESH_DISPLAY = 101
        private const val MSG_DETECT_READING = 102
        private const val MSG_PERIODIC_REFRESH = 103
        private const val DETECT_DELAY_MS = 1000L
        private const val READ_RATIO_THRESHOLD = 10 // 文字数/节点数 阈值，过滤文字多但节点也多的非阅读界面
        private const val READ_TEXT_THRESHOLD = 150 // 判定阅读界面的屏幕文字阈值
        private const val NON_READING_REDETECT_COUNT = 5 // 非阅读界面累计操作达该次数后重新读屏识别
        private const val PERIOD_REFRESH_DUPLICATE_MS = 30000L // 距上次刷新低于该值时跳过周期刷新
    }

    fun updateConfig() {
        serviceSwitch = prefs.serviceSwitch
        interval = prefs.interval
        ignoreTime = prefs.ignoreTime
        delayTime = prefs.delayTime
        monitorTouch = prefs.monitorTouch
        monitorKey = prefs.monitorKey
        monitorGlobal = prefs.monitorGlobal
        autoDetectReading = prefs.autoDetectReading
        readingWhitelist =
            prefs.readingWhitelist?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
        choiceApps = prefs.targetPackageName?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
        // 解析各应用独立刷新配置：格式 pkg:interval:delay,...
        appConfigs = prefs.appRefreshConfigs?.split(",")?.filter { it.isNotEmpty() }?.mapNotNull { seg ->
            val parts = seg.split(":")
            if (parts.size == 3) {
                val iv = parts[1].toIntOrNull() ?: AppPreferences.DEFAULT_APP_INTERVAL
                val dv = parts[2].toIntOrNull() ?: AppPreferences.DEFAULT_APP_DELAY
                parts[0] to (iv to dv)
            } else null
        }?.toMap() ?: emptyMap()
        periodRefresh = prefs.periodRefresh
        clickCount = 0
        isTarget = monitorGlobal || choiceApps.isEmpty()
        isReading = !autoDetectReading
        XLog.d(
            "updateConfig: " +
                    "serviceSwitch=$serviceSwitch, " +
                    "interval=$interval, " +
                    "ignoreTime=$ignoreTime, " +
                    "delayTime=$delayTime, " +
                    "monitorTouch=$monitorTouch, " +
                    "monitorKey=$monitorKey, " +
                    "monitorGlobal=$monitorGlobal, " +
                    "autoDetectReading=$autoDetectReading, " +
                    "readingWhitelist=${prefs.readingWhitelist}, " +
                    "choiceApps=${prefs.targetPackageName}, " +
                    "periodRefresh=$periodRefresh, " +
                    "isReading(初始)=$isReading"
        )
        if (prefs.permissionOverlay == 1 && serviceSwitch) {
            createTouchCapture()
        } else {
            deleteTouchCapture()
        }
        if (serviceConn) {
            notificationUtils.showNotification(
                if (serviceSwitch) getString(R.string.notification_text) else getString(R.string.notification_text_stop),
                true
            )
        }
        // 配置变更后重新安排周期刷新（会依据 serviceSwitch / periodRefresh / 锁屏状态决定是否启动）
        reschedulePeriodicRefresh()
    }

    override fun onCreate() {
        super.onCreate()
        XLog.d("无障碍服务创建")
        val filter = IntentFilter(ACTION_CONFIG_CHANGE)
        // 监听屏幕息屏/亮屏，用于锁屏时重置计数
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        prefs = AppPreferences.getInstance(applicationContext)
        ContextCompat.registerReceiver(
            this,
            myReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateConfig()
    }

    val myReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CONFIG_CHANGE -> updateConfig()
                // 进入锁屏/息屏：停止周期刷新计时、重置操作计数，避免锁屏期间的残留
                Intent.ACTION_SCREEN_OFF -> {
                    XLog.d("屏幕息屏（锁屏），停止周期刷新并重置操作计数")
                    isScreenLocked = true
                    stopPeriodicRefresh()
                    clickCount = 0
                    setReadingState(false)
                }
                // 亮屏解锁：重置锁屏态并重新安排周期刷新计时
                Intent.ACTION_SCREEN_ON -> {
                    XLog.d("屏幕亮屏（解锁），重置阅读态并重启周期刷新计时")
                    isScreenLocked = false
                    setReadingState(false)
                    reschedulePeriodicRefresh()
                }
            }
        }
    }

    var myHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            if (msg.what == MSG_REFRESH_DISPLAY) {
                Utils.refreshScreen(applicationContext)
            } else if (msg.what == MSG_DETECT_READING) {
                detectReadingScreen()
            } else if (msg.what == MSG_PERIODIC_REFRESH) {
                doPeriodicRefresh()
            }
        }
    }

    private fun createTouchCapture() {
        if (addTouchView) return
        XLog.d("createTouchCapture: ")
        if (!this::touchView.isInitialized) {
            touchView = View(applicationContext)
            touchView.setOnTouchListener(this)
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        val lp = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSPARENT
        )

        // 添加到窗口管理器
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.addView(touchView, lp)
        addTouchView = true
    }

    private fun deleteTouchCapture() {
        if (!addTouchView) return
        XLog.d("deleteTouchCapture: ")
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.removeView(touchView)
        addTouchView = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        XLog.d("无障碍服务已连接")
        // 配置服务：监听窗口切换与点击事件；FLAG_REQUEST_FILTER_KEY_EVENTS 用于接收按键
        val info = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }

        this.serviceInfo = info
        startForegroundNotification()
        serviceConn = true
        isRunning = true
        XLog.i("无障碍服务已连接，isRunning=true")
    }

    /**
     * 按键事件回调：过滤系统导航键后，对目标应用内的按下事件累计一次操作。
     * 返回 false 表示不拦截按键（仅作监听统计）。
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (!serviceSwitch) return false
        if (event == null) return false
        // 系统导航键不计入刷新操作
        if (
            event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_HOME ||
            event.keyCode == KeyEvent.KEYCODE_APP_SWITCH ||
            event.keyCode == KeyEvent.KEYCODE_MENU
        ) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            XLog.d("onKeyEvent: monitorKey=$monitorKey, isTarget=$isTarget, keyCode=${event.keyCode}")
            if (monitorKey && isTarget) {
                userOperating()
            }

        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        if (event == null) return false
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            XLog.d("onTouchEvent: monitorTouch=$monitorTouch, isTarget=$isTarget")
            // 只处理目标应用内的点击
            if (monitorTouch && isTarget) {
                userOperating()
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!serviceSwitch) return

        try {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                handleWindowStateChanged(event)
            }
        } catch (e: Exception) {
            XLog.e("处理无障碍事件时出错", e)
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val newPackage = event.packageName?.toString()
        if (newPackage == null) return
        if (ignoreApps.contains(newPackage)) return

        val newActivity = event.className?.toString()
        val packageChanged = newPackage != currentPackage
        val activityChanged = newActivity != null && newActivity != currentActivity

        // 仅在包名或 activity 发生切换时处理
        if (!packageChanged && !activityChanged) return

        XLog.d("界面切换: package $currentPackage -> $newPackage, activity $currentActivity -> $newActivity")

        currentPackage = newPackage
        if (newActivity != null) {
            currentActivity = newActivity
        }

        // 更新目标应用判定
        isTarget = monitorGlobal || choiceApps.isEmpty() || choiceApps.contains(currentPackage)

        // 切换界面，取消未执行的读屏判定
        myHandler.removeMessages(MSG_DETECT_READING)

        if (!serviceSwitch || !isTarget) {
            clickCount = 0
            notificationUtils.showNotification(getString(R.string.notification_text), true)
            return
        }

        if (autoDetectReading) {
            // 白名单优先级最高：命中即视为阅读界面，系统应用也适用，不读屏
            if (isInReadingWhitelist(newPackage)) {
                XLog.d("包名命中阅读白名单: $newPackage，跳过读屏，直接判定为阅读界面")
                setReadingState(true)
            }
            // 本应用自身界面无需读屏（也无法识别为阅读界面）
            else if (newPackage == applicationContext.packageName) {
                XLog.d("当前为本应用界面: $newPackage，跳过读屏，判定为非阅读界面")
                setReadingState(false)
            }
            // 系统应用默认不是阅读界面，跳过读屏直接判定为非阅读
            else if (Utils.isSystemApp(applicationContext, newPackage)) {
                XLog.d("系统应用: $newPackage，跳过读屏，默认判定为非阅读界面")
                setReadingState(false)
            } else {
                // 需读屏判定：先清除阅读态（会重置计数），延迟 1s 后重新判定
                setReadingState(false)
                myHandler.sendEmptyMessageDelayed(MSG_DETECT_READING, DETECT_DELAY_MS)
            }
        } else {
            setReadingState(true)
        }
    }

    /**
     * 当前包名是否包含白名单中的任一关键字
     */
    private fun isInReadingWhitelist(pkg: String): Boolean {
        return readingWhitelist.any { keyword -> pkg.contains(keyword, ignoreCase = true) }
    }

    /**
     * 延迟读取屏幕内容，统计文字数量与节点数，综合判定是否为阅读界面。
     * 阅读界面特征：文字量大（>阈值）且节点稀疏（文字/节点比值高），
     * 以此过滤「文字多但节点也多」的非阅读界面（如密集列表、菜单）。
     */
    private fun detectReadingScreen() {
        if (!serviceSwitch || !isTarget) return
        val result = countScreenText()
        val textEnough = result.textCount > READ_TEXT_THRESHOLD
        // 文字与节点比值：阅读界面文字密集、节点少，比值应明显高于普通界面
        val ratioEnough = result.nodeCount > 0 && result.textCount / result.nodeCount > READ_RATIO_THRESHOLD
        val reading = textEnough && ratioEnough
        XLog.d(
            "读屏识别: 文字数量=${result.textCount}, 节点数=${result.nodeCount}, " +
                    "比值=${if (result.nodeCount > 0) result.textCount / result.nodeCount else 0}, " +
                    "阈值(文字)=$READ_TEXT_THRESHOLD, 阈值(比值)=$READ_RATIO_THRESHOLD, " +
                    "判定阅读界面=$reading"
        )
        setReadingState(reading)
        if (!reading) {
            clickCount = 0
            notificationUtils.showNotification(getString(R.string.notification_text), true)
        }
    }

    /** 屏幕文字统计结果：可见正文总字数 + 遍历到的节点数 */
    private data class ScreenTextResult(val textCount: Int, val nodeCount: Int)

    /**
     * 遍历当前窗口节点，统计可见正文文本的字数及节点总数。
     * 每个节点只取一次文本（优先 text，避免与 contentDescription 重复），
     * 并跳过其子节点已包含文本的非叶子节点，防止父/子嵌套重复累加。
     * 仅统计「自身及所有祖先均对用户可见」的文本，剔除被父容器隐藏的节点。
     */
    private fun countScreenText(): ScreenTextResult {
        val root = rootInActiveWindow
        if (root == null) {
            XLog.e("统计屏幕文字失败: rootInActiveWindow 为 null（未开启 canRetrieveWindowContent 或无活动窗口）")
            return ScreenTextResult(0, 0)
        }
        var total = 0
        var nodeCount = 0
        try {
            val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) } // 遍历窗口节点统计文字
            while (stack.isNotEmpty()) {
                val node = stack.removeFirst()
                nodeCount++
                if (!node.isVisibleToUser) continue
                // 编辑框内容（如搜索框）不计入阅读正文，避免误判
                val isEditable = node.isEditable
                // 仅当自身携带文本、且子节点中没有文本时，才累加自身文本（避免嵌套重复）
                val childHasText = (0 until node.childCount).any { i ->
                    node.getChild(i)?.let { child ->
                        (child.text?.isNotBlank() == true) ||
                                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && child.stateDescription?.isNotBlank() == true)
                    } ?: false
                }
                if (!childHasText && !isEditable) {
                    val text = node.text
                    if (text != null && text.isNotBlank()) {
                        // 仅累加「自身及整条祖先链均可见」的文本，避免统计被隐藏的节点
                        if (isTrulyVisible(node)) {
                            total += text.length
                        }
                    } else {
                        // text 为空时再用 contentDescription 兜底（二者不重复累加）
                        val desc = node.contentDescription
                        if (desc != null && desc.isNotBlank() && isTrulyVisible(node)) {
                            total += desc.length
                        }
                    }
                }
                repeat(node.childCount) { index ->
                    node.getChild(index)?.let { stack.add(it) }
                }
            }
            XLog.d("统计屏幕文字: 遍历节点数=$nodeCount, 文字数量=$total, 当前包名=$currentPackage")
        } catch (e: Exception) {
            XLog.e("统计屏幕文字时出错", e)
        }
        return ScreenTextResult(total, nodeCount)
    }

    /**
     * 判断节点是否「真正可见」：自身可见，且其所有祖先节点均对用户可见。
     * 用于剔除自身标记可见、但被上层隐藏容器遮挡的节点。
     */
    private fun isTrulyVisible(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (!current.isVisibleToUser) return false
            current = current.parent
        }
        return true
    }

    /**
     * 更新阅读界面状态，切换状态时重置操作计数
     */
    private fun setReadingState(reading: Boolean) {
        if (isReading != reading) {
            XLog.d("阅读界面状态切换: $isReading -> $reading, 重置计数")
            isReading = reading
            clickCount = 0
            nonReadingOpCount = 0 // 界面状态变化，重置非阅读重识别计数，进入新一轮
        }
    }

    /**
     * 重新安排周期刷新计时：
     * - 服务已开启、未锁屏、且周期间隔 > 0 时启动循环计时；
     * - 否则清除周期消息（如关闭周期刷新 / 锁屏 / 服务停用）。
     */
    private fun reschedulePeriodicRefresh() {
        stopPeriodicRefresh()
        if (serviceSwitch && !isScreenLocked && periodRefresh > 0) {
            XLog.d("启动周期刷新: 间隔=${periodRefresh}s")
            myHandler.sendEmptyMessageDelayed(MSG_PERIODIC_REFRESH, periodRefresh * 1000L)
        } else {
            XLog.d("未启动周期刷新: serviceSwitch=$serviceSwitch, isScreenLocked=$isScreenLocked, periodRefresh=$periodRefresh")
        }
    }

    /** 停止周期刷新计时（移除待执行的周期消息） */
    private fun stopPeriodicRefresh() {
        myHandler.removeMessages(MSG_PERIODIC_REFRESH)
    }

    /**
     * 执行一次周期刷新：
     * - 若距上次真正刷新不足 30s 则跳过（避免与操作触发刷新重复）；
     * - 刷新成功后记录刷新时间、清零操作计数，并重新安排下一轮周期。
     */
    private fun doPeriodicRefresh() {
        // 重新安排下一轮（无论本次是否刷新，都保持周期循环）
        myHandler.sendEmptyMessageDelayed(MSG_PERIODIC_REFRESH, periodRefresh * 1000L)

        if (!serviceSwitch || isScreenLocked) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefreshTime < PERIOD_REFRESH_DUPLICATE_MS) {
            XLog.d("周期刷新跳过: 距上次刷新 ${(now - lastRefreshTime)}ms < ${PERIOD_REFRESH_DUPLICATE_MS}ms")
            return
        }
        XLog.d("周期刷新触发，执行全局刷新")
        Utils.refreshScreen(applicationContext)
        lastRefreshTime = SystemClock.elapsedRealtime()
        clickCount = 0 // 周期刷新成功后清零操作统计计数
    }

    fun userOperating() {
        if (packageName.equals(currentPackage)) return
        // 开启自动识别且当前不在阅读界面时，跳过刷新统计，但累计操作次数：
        // 同一 activity 内多个 fragment 切换时 TYPE_WINDOW_STATE_CHANGED 不会触发，
        // 故累计达到一定次数后重新读屏识别，避免漏判真正的阅读界面。
        if (autoDetectReading && !isReading) {
            nonReadingOpCount++
            XLog.d("userOperating: 非阅读界面，跳过统计（非阅读操作计数 nonReadingOpCount=$nonReadingOpCount）")
            if (nonReadingOpCount >= NON_READING_REDETECT_COUNT) { // 达到阈值触发重新识别
                nonReadingOpCount = 0
                XLog.d("非阅读界面操作达 $NON_READING_REDETECT_COUNT 次，重新读屏识别界面")
                myHandler.removeMessages(MSG_DETECT_READING)
                myHandler.sendEmptyMessageDelayed(MSG_DETECT_READING, DETECT_DELAY_MS)
            }
            return
        }
        XLog.d("userOperating: ")
        // 计算有效触发阈值与延迟：监控所有应用时用全局配置，否则取该应用独立配置（缺省用默认值）
        val effInterval: Int
        val effDelay: Int
        if (monitorGlobal) {
            effInterval = interval
            effDelay = delayTime
        } else {
            val cfg = appConfigs[currentPackage]
            effInterval = cfg?.first ?: AppPreferences.DEFAULT_APP_INTERVAL
            effDelay = cfg?.second ?: AppPreferences.DEFAULT_APP_DELAY
        }
        val currentTime = SystemClock.elapsedRealtime()
        val timeDiff = currentTime - lastClickTime

        // 过滤连续点击
        if (timeDiff > ignoreTime) {
            clickCount++

            XLog.d("操作计数: $clickCount/$effInterval, 包名: $currentPackage")

            if (clickCount >= effInterval) {
                // 触发全局刷新
                myHandler.removeMessages(MSG_REFRESH_DISPLAY)
                myHandler.sendEmptyMessageDelayed(MSG_REFRESH_DISPLAY, effDelay.toLong())
                // 记录刷新时间，供周期刷新 30s 去重判断；并清零计数
                lastRefreshTime = SystemClock.elapsedRealtime()
                clickCount = 0
                XLog.d("触发全局刷新")
            }

            notificationUtils.showNotification(
                getString(
                    R.string.notification_text_detailed,
                    effInterval - clickCount
                ), true
            )
        }
        lastClickTime = currentTime
    }

    private fun startForegroundNotification() {
        // 创建点击通知时打开的 Intent（通常是应用主界面）
        notificationUtils.createNotificationChannel()
        val notification = notificationUtils.showNotification(
            if (serviceSwitch) getString(R.string.notification_text) else getString(R.string.notification_text_stop),
            false
        )
        // 将服务设置为前台服务并显示通知
        startForeground(notificationUtils.NOTIFICATION_ID, notification)
    }

    override fun onInterrupt() {
        XLog.d("无障碍服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        XLog.d("无障碍服务断开连接")
        serviceConn = false
        isRunning = false
        myHandler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        XLog.d("无障碍服务被销毁")
        isRunning = false
        myHandler.removeCallbacksAndMessages(null)
        deleteTouchCapture()
        unregisterReceiver(myReceiver)
    }
}