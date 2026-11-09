package com.tensun.parentalguard.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.tensun.parentalguard.R
import com.tensun.parentalguard.core.AppEntry
import com.tensun.parentalguard.core.Formatter
import com.tensun.parentalguard.core.UsageAccess
import com.tensun.parentalguard.data.ConfigRepository
import com.tensun.parentalguard.data.UsageRepository
import com.tensun.parentalguard.model.AppLimitRule

/** @brief 单应用每日限额：新增、修改、启用 / 禁用、删除 */
class AppLimitActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: RulesAdapter

    private val rules = ArrayList<AppLimitRule>()
    private var allApps: List<AppEntry> = emptyList()
    private var usedMap: Map<String, Long> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_limit)
        rules.addAll(ConfigRepository.get(this).appLimits)

        listView = findViewById(R.id.listRules)
        tvEmpty = findViewById(R.id.tvEmpty)
        adapter = RulesAdapter()
        listView.adapter = adapter
        listView.emptyView = tvEmpty

        findViewById<Button>(R.id.btnAddRule).setOnClickListener {
            if (allApps.isEmpty()) {
                Toast.makeText(this, "正在读取应用列表，请稍后再试", Toast.LENGTH_SHORT).show()
                loadApps { showAppPicker() }
            } else {
                showAppPicker()
            }
        }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        loadApps { }
        refresh()
    }

    private fun loadApps(onLoaded: () -> Unit) {
        Thread {
            val list = UsageAccess.installedApps(applicationContext)
            runOnUiThread {
                allApps = list
                onLoaded()
            }
        }.start()
    }

    private fun refresh() {
        usedMap = UsageRepository.snapshot(this, System.currentTimeMillis()).perAppMs
        adapter.notifyDataSetChanged()
        tvEmpty.text = if (rules.isEmpty()) "还没有规则，点击下方按钮新增" else ""
    }

    private fun persist() {
        ConfigRepository.update(this) { c ->
            c.appLimits.clear()
            c.appLimits.addAll(rules)
        }
        refresh()
    }

    private fun showAppPicker() {
        if (allApps.isEmpty()) {
            Toast.makeText(this, "未读取到可启动应用", Toast.LENGTH_SHORT).show()
            return
        }
        val labels: Array<CharSequence> = allApps
            .map { "${it.label}  (${it.pkg})" }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择要限制的应用")
            .setItems(labels) { _, which -> showNewRuleDialog(allApps[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNewRuleDialog(app: AppEntry) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_value, null)
        v.findViewById<TextView>(R.id.dialogHint).text =
            "为「${app.label}」设置每日累计使用时长（1 ~ 1440 分钟）"
        val edit = v.findViewById<EditText>(R.id.dialogValue)
        edit.setText("30")
        AlertDialog.Builder(this)
            .setTitle("新增应用限额")
            .setView(v)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val n = edit.text.toString().trim().toIntOrNull()
                if (n == null || n !in 1..1440) {
                    Toast.makeText(this, "请输入 1 ~ 1440 之间的分钟数", Toast.LENGTH_SHORT).show()
                } else {
                    rules.add(
                        AppLimitRule(
                            packageName = app.pkg,
                            appLabel = app.label,
                            dailyMinutes = n,
                            enabled = true
                        )
                    )
                    persist()
                }
            }
            .show()
    }

    private fun showEditDialog(rule: AppLimitRule) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_value, null)
        v.findViewById<TextView>(R.id.dialogHint).text =
            "修改「${rule.appLabel}」每日累计时长（1 ~ 1440 分钟）"
        val edit = v.findViewById<EditText>(R.id.dialogValue)
        edit.setText(rule.dailyMinutes.toString())
        AlertDialog.Builder(this)
            .setTitle("编辑规则")
            .setView(v)
            .setPositiveButton("保存") { _, _ ->
                val n = edit.text.toString().trim().toIntOrNull()
                if (n == null || n !in 1..1440) {
                    Toast.makeText(this, "请输入 1 ~ 1440 之间的分钟数", Toast.LENGTH_SHORT).show()
                } else {
                    rule.dailyMinutes = n
                    persist()
                }
            }
            .setNeutralButton("删除") { _, _ ->
                rules.remove(rule)
                persist()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class RulesAdapter : BaseAdapter() {
        override fun getCount(): Int = rules.size
        override fun getItem(position: Int): Any = rules[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: LayoutInflater.from(this@AppLimitActivity)
                .inflate(R.layout.item_app_limit, parent, false)
            val r = rules[position]
            val label = v.findViewById<TextView>(R.id.limitLabel)
            val detail = v.findViewById<TextView>(R.id.limitDetail)
            label.text = if (r.appLabel.isEmpty()) r.packageName else r.appLabel
            val used = usedMap[r.packageName] ?: 0L
            val left = (r.dailyMinutes.toLong() * 60_000L - used).coerceAtLeast(0L)
            detail.text = "每日 ${r.dailyMinutes} 分钟 · 已用 ${Formatter.human(used)} · 剩余 ${Formatter.human(left)}"

            val icon = v.findViewById<ImageView>(R.id.limitIcon)
            val d = UsageAccess.appIcon(this@AppLimitActivity, r.packageName)
            if (d != null) icon.setImageDrawable(d) else icon.setImageResource(R.drawable.ic_launcher)

            val sw = v.findViewById<Switch>(R.id.limitSwitch)
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = r.enabled
            sw.setOnCheckedChangeListener { _, checked ->
                r.enabled = checked
                persist()
            }

            label.setOnClickListener { showEditDialog(r) }
            detail.setOnClickListener { showEditDialog(r) }
            v.findViewById<Button>(R.id.limitDelete).setOnClickListener {
                rules.remove(r)
                persist()
            }
            return v
        }
    }
}
