package com.liziwa.hisense_autorefresh.activity

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.LinearLayoutManager
import com.elvishew.xlog.XLog
import com.liziwa.hisense_autorefresh.view.AppListAdapter
import com.liziwa.hisense_autorefresh.AppPreferences
import com.liziwa.hisense_autorefresh.EInkAccessibilityService
import com.liziwa.hisense_autorefresh.R
import com.liziwa.hisense_autorefresh.util.Utils
import com.liziwa.hisense_autorefresh.databinding.ActivityAppsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * 应用选择界面：复用同一列表，按 mode 区分：
 * - [MODE_MONITOR]          配置监控应用（写入 targetPackageName）
 * - [MODE_READING_WHITELIST] 阅读白名单（写入 readingWhitelist，忽略自动识别）
 * 保存时发送 ACTION_CONFIG_CHANGE 广播，通知无障碍服务刷新配置。
 */
class AppsActivity : AppCompatActivity(), View.OnClickListener, CoroutineScope {

    private lateinit var binding: ActivityAppsBinding
    /** 当前显示的列表（适配器直接持有此引用），由 applyFilter() 按条件从 allApps 筛选填充 */
    private var appItems = mutableListOf<AppListAdapter.ListItem>()
    /** 主数据列表：所有已加载应用（含选中态与独立配置），是保存时的唯一数据源 */
    private var allApps = mutableListOf<AppListAdapter.ListItem>()
    private lateinit var prefs: AppPreferences

    /** 搜索关键字（按应用名称或包名匹配） */
    private var searchQuery: String = ""
    /** 是否包括系统应用（默认 true，与改动前行为一致：显示所有含启动图标的已安装应用） */
    private var includeSystemApps: Boolean = true

    companion object {
        private const val MSG_UPDATE_APP_LIST = 101
        const val EXTRA_MODE = "extra_mode"
        const val MODE_MONITOR = "monitor"
        const val MODE_READING_WHITELIST = "whitelist"
    }

    /** 当前模式：monitor=监控应用配置，whitelist=阅读白名单配置 */
    private var mode: String = MODE_MONITOR

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = DataBindingUtil.setContentView(this, R.layout.activity_apps)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        job = Job()
        prefs = AppPreferences.getInstance(applicationContext)
        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_MONITOR
        // 自定义标题栏：按模式设置标题文字（监控模式“监控应用列表”，忽略应用模式“忽略应用列表”）
        binding.tvTitle.text = if (mode == MODE_READING_WHITELIST)
            getString(R.string.title_activity_apps_whitelist) else getString(R.string.title_activity_apps)
        XLog.d("AppsActivity: onCreate mode=$mode")

        binding.btnSave.setOnClickListener { this.onClick(it) }
        binding.btnBack.setOnClickListener { this.onClick(it) }

        // 搜索框：输入即实时过滤（按应用名称或包名）
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // “包括系统应用”开关：默认勾选（与改动前一致），取消则隐藏系统应用
        binding.cbIncludeSystem.isChecked = includeSystemApps
        binding.cbIncludeSystem.setOnCheckedChangeListener { _, checked ->
            includeSystemApps = checked
            applyFilter()
        }

        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.rvList.adapter = adapter
        getAllApps()
    }


    /** 异步加载已安装应用，按已选列表标记选中态（排除本应用自身）后刷新 UI */
    private fun getAllApps() {
        launch {
            val choiceApps = if (mode == MODE_READING_WHITELIST) {
                prefs.readingWhitelist?.split(",")?.filter { it.isNotEmpty() }
            } else {
                prefs.targetPackageName?.split(",")?.filter { it.isNotEmpty() }
            }
            XLog.d("getAllApps: mode=$mode, 已选=${choiceApps?.size}个")
            allApps.clear()
            Utils.getLauncherApps(applicationContext)
                .filter { !packageName.equals(it.packageName) } // 排除本应用，避免自我监控
                .forEach { it ->
                    // 读取该应用已保存的独立刷新配置（未配置则用默认值）
                    val cfg = prefs.getAppRefreshConfig(it.packageName)
                    allApps.add(
                        AppListAdapter.ListItem(
                            it.name,
                            it.packageName,
                            it.icon,
                            choiceApps?.contains(it.packageName) ?: false,
                            cfg.first,
                            cfg.second,
                            mode == MODE_MONITOR, // 仅监控模式显示独立配置入口
                            it.isSystem
                        )
                    )
                }
            XLog.d("getAllApps: 加载完成，共 ${allApps.size} 个应用")
            applyFilter()
            myHandler.sendEmptyMessage(MSG_UPDATE_APP_LIST)
        }
    }

    /**
     * 按「搜索关键字」与「是否包括系统应用」过滤主列表，结果写入显示列表 appItems。
     * 已勾选的应用始终保留可见（无论是否被搜索/系统开关过滤），避免误保存时丢配置。
     * 结果按应用名排序，作为稳定的默认排序。
     */
    private fun applyFilter() {
        val q = searchQuery.trim().lowercase()
        val filtered = allApps.filter { item ->
            val keepChecked = item.isChecked
            val matchSystem = includeSystemApps || !item.isSystem
            val matchQuery = q.isEmpty() ||
                item.title.lowercase().contains(q) ||
                item.pkg.lowercase().contains(q)
            keepChecked || (matchSystem && matchQuery)
        }.sortedBy { it.title.lowercase() }
        appItems.clear()
        appItems.addAll(filtered)
        adapter.notifyDataSetChanged()
    }

    private val adapter: AppListAdapter =
        AppListAdapter(
            appItems,
            object : AppListAdapter.OnItemCheckedChangeListener {
                override fun onItemCheckedChange(
                    position: Int,
                    item: AppListAdapter.ListItem,
                    checked: Boolean
                ) {
                    item.isChecked = checked
                    adapter.notifyItemChanged(position)
                }
            },
            object : AppListAdapter.OnItemConfigClickListener {
                override fun onItemConfigClick(position: Int, item: AppListAdapter.ListItem) {
                    showAppConfigDialog(position, item)
                }
            }
        )

    /**
     * 弹出独立配置窗口：编辑该应用的「触发屏幕刷新间隔次数」与「触发屏幕刷新时延迟」。
     * 确认后写回 ListItem 并刷新列表显示。
     */
    private fun showAppConfigDialog(position: Int, item: AppListAdapter.ListItem) {
        val intervalEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(item.interval.toString())
            hint = getString(R.string.hint_config_interval)
        }
        val delayEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(item.delayTime.toString())
            hint = getString(R.string.hint_config_delay)
        }
        val intervalLabel = TextView(this).apply {
            text = getString(R.string.tv_interval)
            textSize = 14f
            setPadding(0, 16, 0, 4)
        }
        val delayLabel = TextView(this).apply {
            text = getString(R.string.tv_delay)
            textSize = 14f
            setPadding(0, 16, 0, 4)
        }
        val tipText = TextView(this).apply {
            text = getString(R.string.dialog_config_tip)
            setTextColor(ContextCompat.getColor(this@AppsActivity, android.R.color.darker_gray))
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
            addView(tipText)
            addView(intervalLabel)
            addView(intervalEdit)
            addView(delayLabel)
            addView(delayEdit)
        }
        AlertDialog.Builder(this)
            .setTitle("${item.title} ${getString(R.string.dialog_title_config)}")
            .setView(layout)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                val iv = intervalEdit.text.toString().toIntOrNull() ?: AppPreferences.DEFAULT_APP_INTERVAL
                val dv = delayEdit.text.toString().toIntOrNull() ?: AppPreferences.DEFAULT_APP_DELAY
                item.interval = iv
                item.delayTime = dv
                adapter.notifyItemChanged(position)
                XLog.d("AppsActivity: 配置 pkg=${item.pkg}, interval=$iv, delay=$dv")
            }
            .setNegativeButton(R.string.btn_cancel) { d, _ -> d.dismiss() }
            .show()
    }

    private var myHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            if (msg.what == MSG_UPDATE_APP_LIST) {
                adapter.notifyDataSetChanged()
                binding.tvLoading.visibility = View.GONE
            }
        }
    }

    override fun onClick(p0: View) {
        when (p0) {
            binding.btnSave -> {
                // 汇总所有选中包名，逗号分隔（基于主列表 allApps，确保被搜索/过滤掉的已选项不丢失）
                val stringBuilder = StringBuilder()
                allApps.forEach {
                    if (it.isChecked) {
                        stringBuilder.append(it.pkg).append(",")
                    }
                }
                XLog.d("onClick: 保存 mode=$mode, 选中=${stringBuilder}")
                if (mode == MODE_READING_WHITELIST) {
                    prefs.readingWhitelist = stringBuilder.toString()
                } else {
                    prefs.targetPackageName = stringBuilder.toString()
                    // 监控模式：选中为空则退化为“监控所有应用”
                    prefs.monitorGlobal = TextUtils.isEmpty(stringBuilder.toString())
                    // 写入各选中应用的独立刷新配置（pkg:interval:delay）
                    val configBuilder = StringBuilder()
                    allApps.forEach { app ->
                        if (app.isChecked) {
                            configBuilder.append(app.pkg).append(":")
                                .append(app.interval).append(":").append(app.delayTime).append(",")
                        }
                    }
                    prefs.appRefreshConfigs = configBuilder.toString()
                    XLog.d("onClick: 保存应用独立配置=${configBuilder}")
                }
                sendBroadcast(Intent(EInkAccessibilityService.Companion.ACTION_CONFIG_CHANGE))
                Toast.makeText(applicationContext, R.string.toast_save, Toast.LENGTH_SHORT).show()
            }

            binding.btnBack -> {
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job
}