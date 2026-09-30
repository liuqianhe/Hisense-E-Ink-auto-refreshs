package com.liziwa.hisense_autorefresh.view

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.elvishew.xlog.XLog
import com.liziwa.hisense_autorefresh.AppPreferences
import com.liziwa.hisense_autorefresh.databinding.ItemAppListBinding

/**
 * 应用选择列表适配器（DataBinding 版）。
 * - 点击整行即切换该应用的选中状态；
 * - 选中态前的「配置」按钮可打开独立配置窗口（仅监控模式下可用）。
 *
 * @param items 应用列表项（title/pkg/icon/isChecked/interval/delayTime/showConfig）
 * @param onItemCheckedChangeListener 选中状态变化回调
 * @param onItemConfigClickListener    配置按钮点击回调（打开独立配置窗口）
 */
class AppListAdapter(
    private val items: List<ListItem>,
    private val onItemCheckedChangeListener: OnItemCheckedChangeListener,
    private val onItemConfigClickListener: OnItemConfigClickListener
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val binding = ItemAppListBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {
        val item = items[position]
        holder.binding.item = item
        // 整行点击：取反当前选中态并回传
        holder.binding.root.setOnClickListener { v ->
            XLog.d("AppListAdapter: 点击 position=$position, pkg=${item.pkg}, newChecked=${!item.isChecked}")
            onItemCheckedChangeListener.onItemCheckedChange(
                position,
                item,
                !item.isChecked
            )
        }
        // 配置按钮：打开该应用的独立刷新配置窗口
        holder.binding.btnConfig.setOnClickListener {
            if (item.showConfig) {
                XLog.d("AppListAdapter: 配置点击 position=$position, pkg=${item.pkg}")
                onItemConfigClickListener.onItemConfigClick(position, item)
            }
        }
        // 配置按钮仅在应用被勾选后可用（未勾选则禁用置灰）
        holder.binding.btnConfig.isEnabled = item.isChecked
        holder.binding.executePendingBindings()
    }

    override fun getItemCount() = items.size

    /** 选中状态变化监听：position 项被切换为 checked */
    interface OnItemCheckedChangeListener {
        fun onItemCheckedChange(position: Int, item: ListItem, checked: Boolean)
    }

    /** 配置按钮点击监听：打开该应用的独立配置窗口 */
    interface OnItemConfigClickListener {
        fun onItemConfigClick(position: Int, item: ListItem)
    }

    class ViewHolder(val binding: ItemAppListBinding) : RecyclerView.ViewHolder(binding.root)

    /** 列表项数据：应用名、包名、图标、选中态，以及独立刷新配置 */
    data class ListItem(
        val title: String,
        val pkg: String,
        val icon: Drawable,
        var isChecked: Boolean = false,
        var interval: Int = AppPreferences.DEFAULT_APP_INTERVAL,
        var delayTime: Int = AppPreferences.DEFAULT_APP_DELAY,
        val showConfig: Boolean = true,
        val isSystem: Boolean = false
    ) {
        /** 列表项底部展示的当前配置数值，如「10 : 2s」，前缀由 string 资源提供 */
        val configText: String
            get() = buildString {
                append(interval).append(" | ")
                append(if (delayTime % 1000 == 0) "${delayTime / 1000}s" else "${delayTime}ms")
            }
    }
}
