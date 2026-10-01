package com.liziwa.hisense_autorefresh.activity

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import com.elvishew.xlog.XLog
import com.liziwa.hisense_autorefresh.AppPreferences
import com.liziwa.hisense_autorefresh.EInkAccessibilityService
import com.liziwa.hisense_autorefresh.MyApp
import com.liziwa.hisense_autorefresh.util.PermissionHelper
import com.liziwa.hisense_autorefresh.R
import com.liziwa.hisense_autorefresh.util.PrivilegedShell
import com.liziwa.hisense_autorefresh.util.Utils
import com.liziwa.hisense_autorefresh.databinding.ActivityMainBinding
import com.liziwa.hisense_autorefresh.util.NotificationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 主界面：展示/配置监控开关、阈值、监控范围与阅读白名单，并引导权限申请。
 * 配置通过 SharedPreferences 持久化；保存时发送 ACTION_CONFIG_CHANGE 广播通知服务热更新。
 */
class MainActivity : AppCompatActivity(), View.OnClickListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences
    private val REQUEST_SHIZUKU_PERMISSION = 2001
    private var titleClickCount = 0 // 主标题连点计数，达到阈值切换调试模式

    private var dialog: AlertDialog? = null

    /** Shizuku 授权结果回调（Shizuku 运行在独立进程，需异步回调） */
    private val shizukuResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_SHIZUKU_PERMISSION) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                XLog.i("MainActivity: Shizuku 授权结果 granted=$granted")
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (granted) R.string.toast_shizuku_granted else R.string.toast_shizuku_denied,
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshPrivilegeStatus()
                }
            }
        }

    /** Shizuku 服务上线时刷新授权状态 */
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        XLog.d("MainActivity: Shizuku binder 已连接")
        runOnUiThread { refreshPrivilegeStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        prefs = AppPreferences.getInstance(applicationContext)
        // 把持久化的授权方案注入 PrivilegedShell（auto / root / shizuku）
        PrivilegedShell.setScheme(prefs.privilegeScheme)

        binding.btnToAccessibilitySettings.setOnClickListener { this.onClick(it) }
        binding.btnEnableAccessibilityAuto.setOnClickListener { this.onClick(it) }
        binding.btnKeepAlive.setOnClickListener { this.onClick(it) }
        binding.btnRelaxHiddenApi.setOnClickListener { this.onClick(it) }
        binding.btnGrantShizuku.setOnClickListener { this.onClick(it) }
        binding.btnGrantRoot.setOnClickListener { this.onClick(it) }
        binding.btnScheme.setOnClickListener { this.onClick(it) }
        binding.ibPrivilegeHelp.setOnClickListener { this.onClick(it) }
        binding.btnMonitorStatusOn.setOnClickListener { this.onClick(it) }
        binding.btnMonitorStatusOff.setOnClickListener { this.onClick(it) }
        binding.cbMonitorTouch.setOnClickListener { this.onClick(it) }
        binding.cbMonitorKey.setOnClickListener { this.onClick(it) }
        binding.cbMonitorGlobal.setOnClickListener { this.onClick(it) }
        binding.cbAutoDetectReading.setOnClickListener { this.onClick(it) }
        binding.btnMonitorList.setOnClickListener { this.onClick(it) }
        binding.btnReadingWhitelist.setOnClickListener { this.onClick(it) }
        binding.ibReadingWhitelistHelp.setOnClickListener { this.onClick(it) }
        binding.btnSave.setOnClickListener { this.onClick(it) }
        binding.btnTest.setOnClickListener { this.onClick(it) }
        binding.btnExit.setOnClickListener { this.onClick(it) }
        NotificationUtils.getInstance(this).removeErrorNotification()

        // 主标题连点 10 次切换调试模式（默认关闭）：开启后 XLog 写文件并打开详细日志
        binding.tvCustomTitle.setOnClickListener {
            titleClickCount++
            if (titleClickCount >= 10) {
                titleClickCount = 0
                val newMode = !prefs.debugMode
                prefs.debugMode = newMode
                MyApp.reinitXLog(applicationContext)
                XLog.i("MainActivity: 调试模式切换为 $newMode")
                Toast.makeText(
                    this,
                    if (newMode) getString(R.string.toast_debug_on) else getString(R.string.toast_debug_off),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        Shizuku.addRequestPermissionResultListener(shizukuResultListener)
        Shizuku.addBinderReceivedListener(shizukuBinderListener)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuResultListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        super.onDestroy()
    }

    /**
     * 只刷新「无障碍服务状态」相关 UI 并同步 prefs.serviceState（供开机自启判断）。
     * 单独抽出来是为了延迟复查时不覆盖用户正在编辑的输入框内容。
     */
    private fun refreshAccessibilityStatus() {
        val enabled = Utils.isAccessibilityServiceEnabled(applicationContext)
        prefs.serviceState = enabled
        binding.tvAccessibilityStatus.text = if (enabled) {
            getString(R.string.tv_status_active)
        } else {
            getString(R.string.tv_status_stop)
        }
        binding.tvMonitor.isEnabled = enabled
        binding.tvMonitorStatus.isEnabled = enabled
        binding.tvMonitorStatus.text = if (enabled && prefs.serviceSwitch) {
            getString(R.string.tv_status_active)
        } else {
            getString(R.string.tv_status_stop)
        }
        binding.btnMonitorStatusOn.isEnabled = enabled && !prefs.serviceSwitch
        binding.btnMonitorStatusOff.isEnabled = enabled && prefs.serviceSwitch
    }

    /**
     * 刷新「Shizuku / Root」授权状态（遵循已选授权方案）。
     * 只读缓存判断 Root（rootGranted），不会触发 Magisk 弹窗；
     * Root 授权状态由「授权 Root」按钮点击时的 requestRootGrant() 更新。
     */
    private fun refreshPrivilegeStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val mode = PrivilegedShell.currentMode()
            val scheme = PrivilegedShell.getScheme()
            val textId: Int = when (mode) {
                PrivilegedShell.Mode.ROOT -> R.string.tv_privilege_root
                PrivilegedShell.Mode.SHIZUKU -> R.string.tv_privilege_shizuku
                PrivilegedShell.Mode.NONE -> when (scheme) {
                    PrivilegedShell.SCHEME_ROOT ->
                        if (PrivilegedShell.rootBinaryExists()) R.string.tv_privilege_root_need_grant
                        else R.string.tv_privilege_none
                    PrivilegedShell.SCHEME_SHIZUKU ->
                        // Shizuku 已运行、或已安装但没启动（binder 未连）都算“未授权”，引导用户去启动并授权；
                        // 仅当设备确实没装 Shizuku 时才提示“未安装 Shizuku”。
                        if (PrivilegedShell.shizukuBinderAlive() ||
                            PrivilegedShell.shizukuInstalled(applicationContext)
                        ) R.string.tv_privilege_shizuku_need_grant
                        else R.string.tv_privilege_shizuku_missing
                    else ->
                        if (PrivilegedShell.shizukuBinderAlive() || PrivilegedShell.rootBinaryExists())
                            R.string.tv_privilege_shizuku_need_grant
                        else R.string.tv_privilege_none
                }
            }
            // 两个独立授权按钮：仅在「可申请且尚未授权」时显示，避免占空间
            val showShizukuGrant = PrivilegedShell.shizukuBinderAlive() && !PrivilegedShell.shizukuReady()
            val showRootGrant = !PrivilegedShell.hasRoot() && PrivilegedShell.rootBinaryExists()
            val schemeNames = mapOf(
                PrivilegedShell.SCHEME_AUTO to getString(R.string.scheme_auto),
                PrivilegedShell.SCHEME_ROOT to getString(R.string.scheme_root),
                PrivilegedShell.SCHEME_SHIZUKU to getString(R.string.scheme_shizuku)
            )
            withContext(Dispatchers.Main) {
                // 「Shizuku 未授权」状态：仅把其中的 “Shizuku” 一词做成带下划线的可点击链接，
                // 点击跳转 Shizuku 应用；其余状态保持普通文本（避免整段/整页都是按钮）。
                if (textId == R.string.tv_privilege_shizuku_need_grant) {
                    val raw = getString(textId)
                    val spanned = SpannableString(raw)
                    val start = raw.indexOf("Shizuku")
                    if (start >= 0) {
                        spanned.setSpan(
                            object : ClickableSpan() {
                                override fun onClick(widget: View) = openShizukuApp()
                                override fun updateDrawState(ds: TextPaint) {
                                    super.updateDrawState(ds)
                                    ds.isUnderlineText = true
                                    ds.color = resources.getColor(R.color.black, null)
                                }
                            },
                            start, start + "Shizuku".length,
                            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    binding.tvPrivilegeStatus.text = spanned
                } else {
                    binding.tvPrivilegeStatus.setText(textId)
                }
                // 让 ClickableSpan 可响应点击（普通文本设此也无副作用）
                binding.tvPrivilegeStatus.movementMethod = LinkMovementMethod.getInstance()
                binding.btnGrantShizuku.visibility = if (showShizukuGrant) View.VISIBLE else View.GONE
                binding.btnGrantRoot.visibility = if (showRootGrant) View.VISIBLE else View.GONE
                binding.rowGrants.visibility =
                    if (showShizukuGrant || showRootGrant) View.VISIBLE else View.GONE
                binding.btnScheme.text = getString(R.string.btn_scheme_fmt, schemeNames[scheme])
                // 「加入保活白名单」不再置灰禁用：无授权时仍需可点击，才能弹出「需要 Shizuku 或 Root 权限」提示
                // （设成 isEnabled=false 后 onClick 根本不触发，提示弹不出来，与当初一键开启无障碍的坑相同）
                binding.btnRelaxHiddenApi.isEnabled = mode != PrivilegedShell.Mode.NONE
            }
        }
    }

    /**
     * 跳转到 Shizuku 管理器应用，方便用户在「已装未启动/未授权」时快速去启动并授权。
     * 若 Shizuku 确实未安装（启动意图为空），则提示用户。
     */
    private fun openShizukuApp() {
        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.tv_privilege_shizuku_missing, Toast.LENGTH_SHORT).show()
        }
    }

    fun updateUI() {
        refreshAccessibilityStatus()
        refreshPrivilegeStatus()
        binding.etInterval.text =
            Editable.Factory.getInstance().newEditable(prefs.interval.toString())
        binding.etDelay.text =
            Editable.Factory.getInstance().newEditable(prefs.delayTime.toString())
        binding.etIgnore.text =
            Editable.Factory.getInstance().newEditable(prefs.ignoreTime.toString())
        binding.etPeriod.text =
            Editable.Factory.getInstance().newEditable(prefs.periodRefresh.toString())
        binding.cbMonitorTouch.isChecked = prefs.monitorTouch
        binding.cbMonitorKey.isChecked = prefs.monitorKey
        binding.cbAutoDetectReading.isChecked = prefs.autoDetectReading
        val monitorGlobal = binding.cbMonitorGlobal.tag as Boolean? ?: prefs.monitorGlobal
        binding.cbMonitorGlobal.isChecked =
            monitorGlobal || TextUtils.isEmpty(prefs.targetPackageName)
        binding.btnMonitorList.isEnabled =
            !monitorGlobal && !TextUtils.isEmpty(prefs.targetPackageName)
        binding.cbHideBackgroundTask.isChecked = prefs.hideBackgroundTask
        // 监控所有应用关闭时，隐藏全局触发间隔/延迟（改由应用列表单独配置）
        updateIntervalVisibility(monitorGlobal)
    }

    /** 监控所有应用开启时显示全局触发间隔/延迟；关闭时隐藏，改用各应用的独立配置 */
    private fun updateIntervalVisibility(monitorAll: Boolean) {
        val v = if (monitorAll) View.VISIBLE else View.GONE
        binding.tvInterval.visibility = v
        binding.etInterval.visibility = v
        binding.tvDelay.visibility = v
        binding.etDelay.visibility = v
    }


    override fun onClick(p0: View) {
        when (p0) {
            binding.btnToAccessibilitySettings -> {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }

            // 有 Shizuku/Root 时直接写系统设置开启无障碍，免去手动进设置页
            binding.btnEnableAccessibilityAuto -> {
                lifecycleScope.launch {
                    // currentMode() 会探测 su，必须在 IO 线程，不能阻塞 UI
                    val mode = withContext(Dispatchers.IO) { PrivilegedShell.currentMode() }
                    if (mode == PrivilegedShell.Mode.NONE) {
                        // Shizuku 方案下已装未启动：给更明确的提示，引导用户去启动 Shizuku
                        if (PrivilegedShell.getScheme() == PrivilegedShell.SCHEME_SHIZUKU
                            && PrivilegedShell.shizukuInstalled(applicationContext)
                        ) {
                            Toast.makeText(
                                this@MainActivity,
                                R.string.toast_shizuku_not_running,
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this@MainActivity, R.string.toast_no_privilege, Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@launch
                    }
                    val ok = PrivilegedShell.enableAccessibility(applicationContext)
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) R.string.toast_accessibility_enabled_ok
                        else R.string.toast_accessibility_enabled_fail,
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshAccessibilityStatus()
                }
            }

            // 加入 Doze 白名单 + 放行后台运行；依赖前台服务常驻，不创建悬浮窗
            binding.btnKeepAlive -> {
                lifecycleScope.launch {
                    // currentMode() 会探测 su，必须在 IO 线程
                    if (withContext(Dispatchers.IO) { PrivilegedShell.currentMode() }
                        == PrivilegedShell.Mode.NONE
                    ) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.toast_keep_alive_need_privilege,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }
                    val r = PrivilegedShell.grantKeepAlive(applicationContext)
                    Toast.makeText(
                        this@MainActivity,
                        if (r.success) R.string.toast_keep_alive_ok
                        else R.string.toast_keep_alive_fail,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // 解除隐藏 API 限制：等价于手动 `adb shell settings put global hidden_api_policy 0`，
            // 有 Shizuku/Root 时直接写入，免去手动 adb（刷新反射调用需要）
            binding.btnRelaxHiddenApi -> {
                lifecycleScope.launch {
                    if (PrivilegedShell.currentMode() == PrivilegedShell.Mode.NONE) {
                        Toast.makeText(
                            this@MainActivity, R.string.toast_no_privilege, Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }
                    val r = PrivilegedShell.relaxHiddenApiPolicy()
                    Toast.makeText(
                        this@MainActivity,
                        if (r.success) R.string.toast_relax_hidden_api_ok
                        else R.string.toast_relax_hidden_api_fail,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // 单独申请 Shizuku 授权（弹 Shizuku 的授权对话框）
            binding.btnGrantShizuku -> {
                when {
                    PrivilegedShell.shizukuReady() -> refreshPrivilegeStatus()
                    PrivilegedShell.shizukuBinderAlive() -> Shizuku.requestPermission(
                        REQUEST_SHIZUKU_PERMISSION
                    )
                    else -> Toast.makeText(
                        this, R.string.tv_privilege_shizuku_missing, Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // 单独申请 Root 授权：真正起 su 进程，触发 Magisk 授权弹窗（IO 线程，可能阻塞数秒）
            binding.btnGrantRoot -> {
                lifecycleScope.launch {
                    if (!PrivilegedShell.rootBinaryExists()) {
                        Toast.makeText(this@MainActivity, R.string.toast_root_no_binary, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val ok = withContext(Dispatchers.IO) { PrivilegedShell.requestRootGrant() }
                    prefs.rootGranted = ok
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) R.string.toast_root_granted else R.string.toast_root_denied,
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshPrivilegeStatus()
                }
            }

            // 授权方案：自动（Root 优先）/ 只用 Root / 只用 Shizuku
            binding.btnScheme -> {
                val schemes = arrayOf(
                    getString(R.string.scheme_auto),
                    getString(R.string.scheme_root),
                    getString(R.string.scheme_shizuku)
                )
                val values = arrayOf(
                    PrivilegedShell.SCHEME_AUTO,
                    PrivilegedShell.SCHEME_ROOT,
                    PrivilegedShell.SCHEME_SHIZUKU
                )
                val checked = values.indexOf(prefs.privilegeScheme).coerceAtLeast(0)
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_scheme_title)
                    .setSingleChoiceItems(schemes, checked) { d, which ->
                        prefs.privilegeScheme = values[which]
                        PrivilegedShell.setScheme(values[which])
                        d.dismiss()
                        refreshPrivilegeStatus()
                    }
                    .setNegativeButton(R.string.btn_cancel) { d, _ -> d.dismiss() }
                    .show()
            }

            binding.ibPrivilegeHelp -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_privilege_title)
                    .setMessage(R.string.dialog_privilege_message)
                    .setPositiveButton(R.string.btn_confirm) { d, _ -> d.dismiss() }
                    .show()
            }

            binding.btnMonitorStatusOn -> {
                prefs.serviceSwitch = true
                updateUI()
                sendBroadcast(Intent(EInkAccessibilityService.Companion.ACTION_CONFIG_CHANGE))
            }

            binding.btnMonitorStatusOff -> {
                prefs.serviceSwitch = false
                updateUI()
                sendBroadcast(Intent(EInkAccessibilityService.Companion.ACTION_CONFIG_CHANGE))
            }

            binding.cbMonitorGlobal -> {
                binding.btnMonitorList.isEnabled = !binding.cbMonitorGlobal.isChecked
                binding.cbMonitorGlobal.tag = binding.cbMonitorGlobal.isChecked
                // 切换监控范围时同步显示/隐藏全局触发间隔/延迟
                updateIntervalVisibility(binding.cbMonitorGlobal.isChecked)
            }

            binding.cbAutoDetectReading -> {
                // 仅记录勾选状态，保存时统一写入
            }

            binding.btnMonitorList -> {
                startActivity(Intent(this, AppsActivity::class.java))
            }

            binding.btnReadingWhitelist -> {
                startActivity(Intent(this, AppsActivity::class.java).apply {
                    putExtra(AppsActivity.EXTRA_MODE, AppsActivity.MODE_READING_WHITELIST)
                })
            }

            binding.ibReadingWhitelistHelp -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_title_tip)
                    .setMessage(R.string.btn_reading_whitelist_hint)
                    .setPositiveButton(R.string.btn_confirm) { dialog, which ->
                        dialog.dismiss()
                    }.show()
            }

            binding.btnSave -> {
                // 监控所有应用开启时才校验并保存全局触发间隔/延迟；关闭时由应用列表单独配置
                val monitorAll = binding.cbMonitorGlobal.isChecked
                if (
                    (monitorAll && TextUtils.isEmpty(binding.etInterval.text)) ||
                    (monitorAll && TextUtils.isEmpty(binding.etDelay.text)) ||
                    TextUtils.isEmpty(binding.etIgnore.text) ||
                    TextUtils.isEmpty(binding.etPeriod.text)
                ) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.error)
                        .setMessage(R.string.error_empty_config)
                        .setPositiveButton(R.string.btn_confirm) { dialog, which ->
                            dialog.dismiss()
                        }.show()
                    return
                }
                try {
                    if (monitorAll) {
                        prefs.interval = binding.etInterval.text.toString().toInt()
                        prefs.delayTime = binding.etDelay.text.toString().toInt()
                    }
                    prefs.ignoreTime = binding.etIgnore.text.toString().toInt()
                    prefs.periodRefresh = binding.etPeriod.text.toString().toInt()
                    prefs.monitorKey = binding.cbMonitorKey.isChecked
                    prefs.monitorTouch = binding.cbMonitorTouch.isChecked
                    prefs.monitorGlobal = binding.cbMonitorGlobal.isChecked
                    prefs.autoDetectReading = binding.cbAutoDetectReading.isChecked
                    binding.cbMonitorGlobal.tag = null
                    prefs.hideBackgroundTask = binding.cbHideBackgroundTask.isChecked
                    sendBroadcast(Intent(EInkAccessibilityService.Companion.ACTION_CONFIG_CHANGE))
                    Toast.makeText(applicationContext, R.string.toast_save, Toast.LENGTH_SHORT)
                        .show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    AlertDialog.Builder(this)
                        .setTitle(R.string.error)
                        .setMessage(R.string.error_empty_format)
                        .setPositiveButton(R.string.btn_confirm) { dialog, which ->
                            dialog.dismiss()
                        }.show()
                }
            }

            binding.btnTest -> {
                Utils.refreshScreen(applicationContext)
            }

            binding.btnExit -> onBackPressedDispatcher.onBackPressed()
        }
    }


    override fun onResume() {
        super.onResume()
        XLog.d("onResume: ")
        // 请求必要权限
        requestRequiredPermissions()
        updateUI()
        // 后台被杀后 Root 会话会丢失，但 Magisk 已对本应用放行时重新 exec su 不弹窗；
        // 静默重探恢复授权，使“一键开启无障碍”可直接点击（与 Shizuku 行为一致，Issue A）。
        // 同时把当前已生效的授权持久化，兼容“本次更新前已授权但未落盘”的旧安装。
        if (PrivilegedShell.hasRoot()) {
            prefs.rootGranted = true
        } else if (prefs.rootGranted && PrivilegedShell.rootBinaryExists()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = PrivilegedShell.requestRootGrant()
                prefs.rootGranted = ok
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) refreshPrivilegeStatus()
                }
            }
        }
        // 开机后系统 bind 无障碍服务可能慢半拍，延迟再复查两次状态
        // （只刷新状态区，不触碰输入框，避免覆盖用户编辑中的内容）
        binding.root.postDelayed({ if (!isFinishing && !isDestroyed) refreshAccessibilityStatus() }, 800)
        binding.root.postDelayed({ if (!isFinishing && !isDestroyed) refreshAccessibilityStatus() }, 3000)
    }

    override fun onPause() {
        super.onPause()
        XLog.d("onPause: ")
        dialog?.dismiss()
        dialog = null
        toggleRecentsVisibility(prefs.hideBackgroundTask)
    }

    private fun toggleRecentsVisibility(hide: Boolean) {
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
            .appTasks
            .firstOrNull()
            ?.setExcludeFromRecents(hide)
    }

    /**
     * 进入前台时检查必要权限：忽略电池优化。
     * （悬浮窗权限已废弃：触摸计数改由无障碍事件完成，不再创建透明悬浮窗）
     * 用 prefs 中的权限标记位避免每次 onResume 重复弹窗。
     */
    private fun requestRequiredPermissions() {
        XLog.d("requestRequiredPermissions: 电池优化=${prefs.permissionIgnoringBatteryOptimizations}")

        // 检查忽略电池优化权限
        if (!PermissionHelper.hasIgnoringBatteryOptimizationsPermission(this)) {
            if (prefs.permissionIgnoringBatteryOptimizations != 0) {
                XLog.d("requestRequiredPermissions: 申请忽略电池优化权限")
                PermissionHelper.requestIgnoreBatteryOptimizationsPermission(this)
                prefs.permissionIgnoringBatteryOptimizations = 0
                return
            }
        } else {
            prefs.permissionIgnoringBatteryOptimizations = 1
        }
        // 悬浮窗权限已不再需要：触摸计数改由无障碍事件完成，不再创建 1x1 透明悬浮窗
    }

}
