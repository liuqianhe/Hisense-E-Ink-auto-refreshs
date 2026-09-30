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
 * 优先级：Root > Shizuku（Root 更稳，Shizuku 更通用）。
 */
object PrivilegedShell {

    enum class Mode { NONE, SHIZUKU, ROOT }

    data class Result(val success: Boolean, val exitCode: Int, val out: String, val mode: Mode) {
        companion object {
            fun fail(msg: String) = Result(false, -1, msg, Mode.NONE)
        }
    }

    private const val TIMEOUT_SEC = 15L

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

    @Volatile
    private var rootProbed = false

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

    /** 探测可用的 su（结果缓存，避免反复触发 Magisk 授权弹窗） */
    private fun findSu(): String? {
        suPath?.let { return it }
        if (rootProbed) return null
        for (candidate in SU_CANDIDATES) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf(candidate, "-c", "id"))
                val finished = p.waitFor(5, TimeUnit.SECONDS)
                val out = p.inputStream.bufferedReader().readText()
                val ok = finished && p.exitValue() == 0 && out.contains("uid=0")
                XLog.d("PrivilegedShell: 探测 su [$candidate] -> $ok, 输出=${out.trim()}")
                if (ok) {
                    suPath = candidate
                    return candidate
                }
            } catch (e: Throwable) {
                XLog.d("PrivilegedShell: 探测 su [$candidate] 异常: ${e.message}")
            }
        }
        rootProbed = true
        return null
    }

    /** 设备是否已有 root 且本应用已被 su 放行 */
    fun hasRoot(): Boolean = findSu() != null

    /** 当前可用的提权方式；Root 不可用且 Shizuku 未授权时返回 NONE */
    fun currentMode(): Mode {
        return when {
            hasRoot() -> Mode.ROOT
            shizukuReady() -> Mode.SHIZUKU
            else -> Mode.NONE
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
        val su = findSu() ?: "su"
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
}
