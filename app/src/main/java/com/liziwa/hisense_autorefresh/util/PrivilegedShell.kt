package com.liziwa.hisense_autorefresh.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.elvishew.xlog.XLog
import com.liziwa.hisense_autorefresh.EInkAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * 以「Shizuku（shell 身份）」或「Root」执行系统命令。
 *
 * 用途：
 * - enableAccessibility()：直接写 Settings.Secure 开启本应用无障碍服务，
 *   用户无需进入「设置 → 无障碍」手动开关（这是本项目原本唯一必须手动操作的一步）。
 * - grantKeepAlive()：把本应用加入 Doze 白名单并放行后台运行限制，
 *   配合已有的前台服务做保活，全程不创建任何悬浮窗。
 *
 * 优先级由「授权方案」决定：auto=Root 优先、无则回落 Shizuku；也可强制只用 Root 或只用 Shizuku。
 */
object PrivilegedShell {

    /** 授权方案：自动（Root 优先，回落 Shizuku） */
    const val SCHEME_AUTO = "auto"
    /** 授权方案：只用 Root */
    const val SCHEME_ROOT = "root"
    /** 授权方案：只用 Shizuku */
    const val SCHEME_SHIZUKU = "shizuku"

    enum class Mode { NONE, SHIZUKU, ROOT }

    data class Result(val success: Boolean, val exitCode: Int, val out: String, val mode: Mode) {
        companion object {
            fun fail(msg: String) = Result(false, -1, msg, Mode.NONE)
        }
    }

    private const val TIMEOUT_SEC = 15L

    @Volatile
    private var preferredScheme: String = SCHEME_AUTO

    /** 设置授权方案（auto / root / shizuku），由 MainActivity 从偏好读取后注入 */
    fun setScheme(scheme: String) {
        preferredScheme = scheme
    }

    fun getScheme(): String = preferredScheme

    /**
     * su 的常见位置。应用进程的 PATH 通常不含 /sbin，直接 exec("su") 会找不到，
     * 所以逐个候选路径试（Magisk 的 su 是 /sbin/su 软链到 magisk）。
     */
    private val SU_CANDIDATES = listOf(
        "su",
        "/sbin/su",
        "/system/xbin/su",
        "/system/bin/su",
        "/su/bin/su",
        "/system/sbin/su"
    )

    @Volatile
    private var suPath: String? = null

    /** Root 是否已被本应用拿到授权（缓存值，只有 requestRootGrant() 会更新它） */
    @Volatile
    private var rootGranted = false

    /** Shizuku 的 binder 是否已就绪（意味着设备上安装并运行了 Shizuku） */
    fun shizukuBinderAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    /** Shizuku 已运行且本应用已被授权（可以真正执行命令） */
    fun shizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 探测可用的 su。只在「授权 Root」按钮点击时调用（会触发 Magisk 授权弹窗），
     * 平时的状态刷新只读 rootGranted 缓存，避免反复弹窗打扰。
     */
    fun requestRootGrant(): Boolean {
        suPath = null
        for (candidate in SU_CANDIDATES) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf(candidate, "-c", "id"))
                val finished = p.waitFor(8, TimeUnit.SECONDS)
                val out = p.inputStream.bufferedReader().readText()
                val ok = finished && p.exitValue() == 0 && out.contains("uid=0")
                XLog.d("PrivilegedShell: 申请 Root 授权 [$candidate] -> $ok, 输出=${out.trim()}")
                if (ok) {
                    suPath = candidate
                    rootGranted = true
                    return true
                }
            } catch (e: Throwable) {
                XLog.d("PrivilegedShell: 申请 Root 授权 [$candidate] 异常: ${e.message}")
            }
        }
        rootGranted = false
        return false
    }

    /** Root 是否已被本应用授权（只读缓存，不触发 Magisk 弹窗） */
    fun hasRoot(): Boolean = rootGranted

    /**
     * 设备上是否存在可用的 su（用于决定是否显示「授权 Root」按钮）。
     * 注意：部分 ROM（如本机海信）在 app 的挂载命名空间下 stat /sbin/su 会返回不存在
     * （adb shell 能看到，app 内 File.exists 却为 false），导致按钮被误判隐藏；
     * 因此改为真正尝试 exec "su" 来判定（立即销毁进程，不触发 Magisk 授权弹窗）。
     */
    fun rootBinaryExists(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "true"))
        p.destroy() // 立即销毁，避免弹出 Magisk 授权框
        true
    } catch (e: Throwable) {
        false
    }

    /** 当前应使用的提权方式，遵循已设置的授权方案 */
    fun currentMode(): Mode {
        return when (preferredScheme) {
            SCHEME_ROOT -> if (hasRoot()) Mode.ROOT else Mode.NONE
            SCHEME_SHIZUKU -> if (shizukuReady()) Mode.SHIZUKU else Mode.NONE
            else -> when {
                hasRoot() -> Mode.ROOT
                shizukuReady() -> Mode.SHIZUKU
                else -> Mode.NONE
            }
        }
    }

    /** 执行命令；stderr 合并进 stdout 避免读流死锁 */
    suspend fun exec(cmd: String, mode: Mode = currentMode()): Result = withContext(Dispatchers.IO) {
        if (mode == Mode.NONE) return@withContext Result.fail("无可用提权方式（需 Shizuku 或 Root）")
        val wrapped = "$cmd 2>&1"
        XLog.d("PrivilegedShell: 执行[$mode] $cmd")
        val r = if (mode == Mode.ROOT) execRoot(wrapped) else execShizuku(wrapped)
        XLog.d("PrivilegedShell: 结果 exit=${r.exitCode}, out=${r.out.take(300)}")
        r
    }

    private fun execRoot(cmd: String): Result {
        val su = suPath ?: "su"
        return try {
            val p = Runtime.getRuntime().exec(arrayOf(su, "-c", cmd))
            val finished = p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
            val out = p.inputStream.bufferedReader().readText()
            val code = if (finished) p.exitValue() else -1
            if (!finished) p.destroy()
            Result(code == 0, code, out, Mode.ROOT)
        } catch (e: Throwable) {
            Result.fail("Root 执行失败: ${e.message}")
        }
    }

    /**
     * 经 Shizuku 起进程执行命令。
     * Shizuku 13 把 Shizuku.newProcess 设成了私有，故直接走 AIDL：
     * IShizukuService.newProcess() 返回的 IRemoteProcess 提供输入流与退出码。
     */
    private fun execShizuku(cmd: String): Result {
        return try {
            if (!shizukuReady()) return Result.fail("Shizuku 未授权")
            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            val remote = service.newProcess(arrayOf("sh", "-c", cmd), null, null)
            // 命令里已把 stderr 合并到 stdout，只读输入流即可，避免两条流互相阻塞
            val out = remote.inputStream?.let { pfd ->
                FileInputStream(pfd.fileDescriptor).bufferedReader().readText().also { pfd.close() }
            } ?: ""
            val code = remote.waitFor()
            Result(code == 0, code, out, Mode.SHIZUKU)
        } catch (e: Throwable) {
            Result.fail("Shizuku 执行失败: ${e.message}")
        }
    }

    /** 本服务在 Settings.Secure 中的短形式 ID（系统与设置页落盘的就是这个形式） */
    private fun serviceShortId(context: Context): String =
        ComponentName(context, EInkAccessibilityService::class.java).flattenToShortString()

    private fun readEnabledServices(context: Context): String =
        try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
        } catch (e: Throwable) {
            ""
        }

    private fun alreadyEnabled(context: Context): Boolean {
        val id = serviceShortId(context)
        return readEnabledServices(context).split(":").any { it.trim().equals(id, ignoreCase = true) }
    }

    /**
     * 自动开启本应用的无障碍服务：把本服务追加进 enabled_accessibility_services 并打开总开关。
     * 注意必须写「短形式」，完整类名形式会被系统回滚丢掉（海信 ROM 实测）。
     */
    suspend fun enableAccessibility(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (alreadyEnabled(context)) {
            XLog.i("PrivilegedShell: 无障碍已在启用列表中，无需重复写入")
            return@withContext true
        }
        val id = serviceShortId(context)
        val cur = readEnabledServices(context)
        val newList = if (cur.isBlank()) id else "$cur:$id"
        exec("settings put secure enabled_accessibility_services $newList")
        exec("settings put secure accessibility_enabled 1")
        val ok = alreadyEnabled(context)
        XLog.i("PrivilegedShell: 自动开启无障碍 -> $ok, 当前列表=${readEnabledServices(context)}")
        ok
    }

    /**
     * 后台保活加白：加入 Doze 白名单、放行后台运行 appops。
     * 依赖前台服务常驻，不创建任何悬浮窗/悬浮球。
     */
    suspend fun grantKeepAlive(context: Context): Result = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val cmds = listOf(
            "cmd deviceidle whitelist +$pkg",
            "dumpsys deviceidle whitelist +$pkg",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set $pkg RUN_IN_BACKGROUND allow"
        )
        var anyOk = false
        var last = Result.fail("未执行")
        for (c in cmds) {
            last = exec(c)
            if (last.success) anyOk = true
            XLog.d("PrivilegedShell: 保活白名单 [$c] -> ${last.success}")
        }
        last.copy(success = anyOk)
    }

    /** 复查 Doze 白名单是否命中（用于界面展示） */
    suspend fun isInDozeWhitelist(context: Context): Boolean = withContext(Dispatchers.IO) {
        val r = exec("dumpsys deviceidle whitelist")
        r.out.contains(context.packageName)
    }

    /**
     * 解除隐藏 API 限制（等价于手动执行 `adb shell settings put global hidden_api_policy 0`）。
     *
     * 海信等 ROM 对 non-SDK 接口（本项目通过反射调用 com.hmct.epd.EpdManager.forceClear 强制刷新）
     * 有灰名单限制，不设大会导致刷新反射调用被系统拦截。原方案需手动 adb 设置一次，
     * 现在有了 Shizuku（shell 身份，与 adb shell 等价）或 Root，可直接写入，免去手动 adb。
     *
     * 写入后读取校验，确认值确实变为 0 才算成功。
     */
    suspend fun relaxHiddenApiPolicy(): Result = withContext(Dispatchers.IO) {
        if (currentMode() == Mode.NONE) return@withContext Result.fail("无可用提权方式（需 Shizuku 或 Root）")
        val put = exec("settings put global hidden_api_policy 0")
        val get = exec("settings get global hidden_api_policy")
        val current = get.out.trim()
        val ok = put.success && current == "0"
        XLog.i("PrivilegedShell: 解除隐藏API限制 -> put=${put.success}, 当前值=$current, ok=$ok")
        if (ok) put.copy(success = true) else Result.fail("设置失败（put=${put.success}, 当前值=$current）")
    }
}
