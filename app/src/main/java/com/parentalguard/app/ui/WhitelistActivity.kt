package com.parentalguard.app.ui

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import com.parentalguard.app.R
import com.parentalguard.app.core.AppEntry
import com.parentalguard.app.core.UsageAccess
import com.parentalguard.app.data.ConfigRepository
import com.parentalguard.app.model.RulesConfig
import java.util.Locale

/**
 * @brief 三类应用名单：可玩白名单 / 强制休息放行 / 免计时
 *
 * 三者共用一套列表与搜索，顶部标签切换编辑对象。
 * 管控自身在三类名单里都固定选中且不可取消，避免家长把自己锁在设置之外。
 */
class WhitelistActivity : Activity() {

    private companion object {
        const val MODE_PLAYABLE = 0
        const val MODE_REST = 1
        const val MODE_NO_COUNT = 2
        const val PREFS_UI = "pg_ui"
        const val KEY_MODE = "whitelistMode"

        val ENABLED_LABELS = arrayOf(
            "启用可玩白名单限制",
            "启用休息放行名单",
            "启用免计时名单"
        )

        val DESCRIPTIONS = arrayOf(
            "勾选允许打开的应用；未勾选的应用启动后会被立即拦截并返回桌面。系统桌面与管控应用始终自动允许。此设置仅本机生效，不同步到局域网其他设备。",
            "开关打开后，强制休息开始时这里勾选的应用仍可继续使用，未勾选的应用会被全屏拦截。只豁免「强制休息」这一条规则：每日总时长、单应用限额、可玩白名单照常生效。开关关闭时休息期间一律拦截。",
            "开关打开后，这里勾选的应用在前台时不累计任何时长（今日总量、单次连续、单应用时长都不增长），适合通话、网课等不该占用额度的应用。它只影响计时，不改变任何拦截判定。开关关闭时全部照常计时。"
        )
    }

    private lateinit var cfg: RulesConfig
    private lateinit var tabs: List<TextView>
    private lateinit var tvModeDesc: TextView
    private lateinit var rowEnabled: View
    private lateinit var tvEnabledLabel: TextView
    private lateinit var swEnabled: Switch
    /** 程序化设置开关状态时置位，避免回调把刚切的模式又写回去 */
    private var bindingSwitch = false
    private lateinit var editSearch: EditText
    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: AppsAdapter

    private var mode = MODE_PLAYABLE
    private var selfPackage = ""
    private var allApps: List<AppEntry> = emptyList()
    private var shownApps: List<AppEntry> = emptyList()
    private var loaded = false

    /** 当前模式下已勾选的包名；始终包含管控自身 */
    private val selected = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whitelist)
        cfg = ConfigRepository.get(this)
        selfPackage = packageName

        tabs = listOf(
            findViewById(R.id.tabPlayable),
            findViewById(R.id.tabRest),
            findViewById(R.id.tabNoCount)
        )
        tvModeDesc = findViewById(R.id.tvModeDesc)
        rowEnabled = findViewById(R.id.rowEnabled)
        tvEnabledLabel = findViewById(R.id.tvEnabledLabel)
        swEnabled = findViewById(R.id.swWhitelistEnabled)
        editSearch = findViewById(R.id.editSearch)
        listView = findViewById(R.id.listApps)
        tvEmpty = findViewById(R.id.tvEmpty)

        // 三份名单各自独立开关：回调按当前模式写入对应字段
        swEnabled.setOnCheckedChangeListener { _, checked ->
            if (bindingSwitch) return@setOnCheckedChangeListener
            val target = mode
            ConfigRepository.update(this) { c ->
                when (target) {
                    MODE_REST -> c.restWhitelist.enabled = checked
                    MODE_NO_COUNT -> c.noCountWhitelist.enabled = checked
                    else -> c.whitelist.enabled = checked
                }
            }
            cfg = ConfigRepository.get(this)
        }

        adapter = AppsAdapter()
        listView.adapter = adapter

        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { switchMode(index) }
        }

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter() }
        })

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        val saved = getSharedPreferences(PREFS_UI, MODE_PRIVATE).getInt(KEY_MODE, MODE_PLAYABLE)
        switchMode(saved.coerceIn(MODE_PLAYABLE, MODE_NO_COUNT), persist = false)
        loadApps()
    }

    /** @brief 切换当前编辑的名单：标签选中态、说明文案、开关可见性、勾选内容一起换 */
    private fun switchMode(index: Int, persist: Boolean = true) {
        mode = index
        for (i in tabs.indices) tabs[i].isSelected = i == index
        tvModeDesc.text = DESCRIPTIONS[index]
        // 三份名单都有独立开关，标签与开关状态随模式切换
        tvEnabledLabel.text = ENABLED_LABELS[index]
        bindingSwitch = true
        swEnabled.isChecked = enabledOf(index)
        bindingSwitch = false
        selected.clear()
        selected.addAll(packagesOf(index))
        selected.add(selfPackage)
        if (persist) {
            getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit().putInt(KEY_MODE, index).apply()
        }
        applyFilter()
    }

    private fun enabledOf(index: Int): Boolean = when (index) {
        MODE_REST -> cfg.restWhitelist.enabled
        MODE_NO_COUNT -> cfg.noCountWhitelist.enabled
        else -> cfg.whitelist.enabled
    }

    private fun packagesOf(index: Int): Set<String> = when (index) {
        MODE_REST -> cfg.restWhitelist.packages
        MODE_NO_COUNT -> cfg.noCountWhitelist.packages
        else -> cfg.whitelist.packages
    }

    private fun loadApps() {
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = "正在读取应用列表…"
        Thread {
            val list = UsageAccess.installedApps(applicationContext)
            runOnUiThread {
                allApps = list
                loaded = true
                applyFilter()
            }
        }.start()
    }

    private fun applyFilter() {
        val q = editSearch.text.toString().trim().lowercase(Locale.CHINA)
        shownApps = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.lowercase(Locale.CHINA).contains(q) ||
                        it.pkg.lowercase(Locale.CHINA).contains(q)
            }
        }
        adapter.notifyDataSetChanged()
        // 把可选应用总数写在说明里：家长能据此核对「列表是否完整」，排查漏应用时不用猜
        tvModeDesc.text = if (loaded) {
            DESCRIPTIONS[mode] + "\n本机共 ${allApps.size} 个应用可选"
        } else {
            DESCRIPTIONS[mode]
        }
        tvEmpty.text = when {
            !loaded -> "正在读取应用列表…"
            allApps.isEmpty() -> "未读取到可启动应用"
            shownApps.isEmpty() -> "没有匹配的应用"
            else -> ""
        }
        tvEmpty.visibility = if (tvEmpty.text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** @brief 只写当前模式对应的集合；ConfigRepository 会再次强制补上管控自身 */
    private fun persistSelection() {
        val snapshot = LinkedHashSet(selected)
        ConfigRepository.update(this) { c ->
            val target = when (mode) {
                MODE_REST -> c.restWhitelist.packages
                MODE_NO_COUNT -> c.noCountWhitelist.packages
                else -> c.whitelist.packages
            }
            target.clear()
            target.addAll(snapshot)
        }
        cfg = ConfigRepository.get(this)
    }

    /**
     * @brief 列表适配器：第 0 行固定是管控自身（不可取消），其后才是设备上可启动的应用
     */
    private inner class AppsAdapter : BaseAdapter() {

        /** 首行固定为管控自身，因此总数要 +1 */
        override fun getCount(): Int = shownApps.size + 1
        override fun getItem(position: Int): Any =
            if (position == 0) selfPackage else shownApps[position - 1]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: LayoutInflater.from(this@WhitelistActivity)
                .inflate(R.layout.item_app_check, parent, false)
            val label = v.findViewById<TextView>(R.id.appLabel)
            val pkgView = v.findViewById<TextView>(R.id.appPackage)
            val icon = v.findViewById<ImageView>(R.id.appIcon)
            val check = v.findViewById<CheckBox>(R.id.appCheck)
            check.setOnCheckedChangeListener(null)

            if (position == 0) {
                label.text = "${getString(R.string.app_name)}（本应用）"
                pkgView.text = "固定包含 · 不可移除"
                icon.setImageResource(R.drawable.ic_launcher)
                check.isChecked = true
                check.isEnabled = false
                v.isClickable = false
                v.setOnClickListener(null)
                return v
            }

            val e = shownApps[position - 1]
            label.text = e.label
            pkgView.text = e.pkg
            if (e.icon != null) icon.setImageDrawable(e.icon) else icon.setImageResource(R.drawable.ic_launcher)
            check.isEnabled = true
            check.isChecked = selected.contains(e.pkg)
            check.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selected.add(e.pkg) else selected.remove(e.pkg)
                // 管控自身不允许被任何操作移出名单
                selected.add(selfPackage)
                persistSelection()
            }
            v.isClickable = true
            v.setOnClickListener { check.isChecked = !check.isChecked }
            return v
        }
    }
}
