package com.tensun.parentalguard.ui

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
import com.tensun.parentalguard.R
import com.tensun.parentalguard.core.AppEntry
import com.tensun.parentalguard.core.UsageAccess
import com.tensun.parentalguard.data.ConfigRepository
import com.tensun.parentalguard.model.RulesConfig
import java.util.Locale

/** @brief 应用白名单：勾选 / 取消勾选即可增删，立即持久化 */
class WhitelistActivity : Activity() {

    private lateinit var cfg: RulesConfig
    private lateinit var swEnabled: Switch
    private lateinit var editSearch: EditText
    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: AppsAdapter

    private var allApps: List<AppEntry> = emptyList()
    private var shownApps: List<AppEntry> = emptyList()
    private val selected = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whitelist)
        cfg = ConfigRepository.get(this)
        selected.addAll(cfg.whitelist.packages)

        swEnabled = findViewById(R.id.swWhitelistEnabled)
        editSearch = findViewById(R.id.editSearch)
        listView = findViewById(R.id.listApps)
        tvEmpty = findViewById(R.id.tvEmpty)

        swEnabled.isChecked = cfg.whitelist.enabled
        swEnabled.setOnCheckedChangeListener { _, checked ->
            ConfigRepository.update(this) { it.whitelist.enabled = checked }
        }

        adapter = AppsAdapter()
        listView.adapter = adapter
        listView.emptyView = tvEmpty

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter() }
        })

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        loadApps()
    }

    private fun loadApps() {
        tvEmpty.text = "正在读取应用列表…"
        Thread {
            val list = UsageAccess.installedApps(applicationContext)
            runOnUiThread {
                allApps = list
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
        adapter.setItems(shownApps)
        tvEmpty.text = if (allApps.isEmpty()) "未读取到可启动应用" else "没有匹配的应用"
    }

    private inner class AppsAdapter : BaseAdapter() {
        private var items: List<AppEntry> = emptyList()

        fun setItems(list: List<AppEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: LayoutInflater.from(this@WhitelistActivity)
                .inflate(R.layout.item_app_check, parent, false)
            val e = items[position]
            v.findViewById<TextView>(R.id.appLabel).text = e.label
            v.findViewById<TextView>(R.id.appPackage).text = e.pkg
            val icon = v.findViewById<ImageView>(R.id.appIcon)
            if (e.icon != null) {
                icon.setImageDrawable(e.icon)
            } else {
                icon.setImageResource(R.drawable.ic_launcher)
            }
            val check = v.findViewById<CheckBox>(R.id.appCheck)
            check.setOnCheckedChangeListener(null)
            check.isChecked = selected.contains(e.pkg)
            check.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selected.add(e.pkg) else selected.remove(e.pkg)
                persistSelection()
            }
            v.setOnClickListener { check.isChecked = !check.isChecked }
            return v
        }
    }

    private fun persistSelection() {
        ConfigRepository.update(this) { c ->
            c.whitelist.packages.clear()
            c.whitelist.packages.addAll(selected)
        }
    }
}
