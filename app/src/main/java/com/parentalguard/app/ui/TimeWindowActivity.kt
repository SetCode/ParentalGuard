package com.parentalguard.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.parentalguard.app.R
import com.parentalguard.app.core.TimeWindow
import com.parentalguard.app.data.ConfigRepository
import com.parentalguard.app.model.RulesConfig
import com.parentalguard.app.model.TimeSlot
import java.util.UUID

/**
 * @brief 时间段管控配置：按星期 + 起止时间禁止使用设备
 *
 * 支持每天 / 工作日 / 周末快捷选择，也可逐天勾选；起止时间用系统时间选择器，
 * 结束时间小于开始时间即按跨零点处理（例如 21:00–07:00）。
 */
class TimeWindowActivity : Activity() {

    private lateinit var cfg: RulesConfig
    private lateinit var swEnabled: Switch
    private lateinit var tvNextBoundary: TextView
    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: SlotsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_time_window)
        cfg = ConfigRepository.get(this)

        swEnabled = findViewById(R.id.swTimeWindow)
        tvNextBoundary = findViewById(R.id.tvNextBoundary)
        listView = findViewById(R.id.listSlots)
        tvEmpty = findViewById(R.id.tvEmpty)

        swEnabled.isChecked = cfg.timeWindow.enabled
        swEnabled.setOnCheckedChangeListener { _, checked ->
            ConfigRepository.update(this) { it.timeWindow.enabled = checked }
            cfg = ConfigRepository.get(this)
            render()
        }

        adapter = SlotsAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> editSlot(cfg.timeWindow.slots[position]) }

        findViewById<Button>(R.id.btnAddSlot).setOnClickListener { editSlot(null) }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        render()
    }

    private fun render() {
        cfg = ConfigRepository.get(this)
        adapter.notifyDataSetChanged()
        val slots = cfg.timeWindow.slots
        tvEmpty.visibility = if (slots.isEmpty()) View.VISIBLE else View.GONE
        val now = System.currentTimeMillis()
        val active = TimeWindow.activeSlot(cfg.timeWindow, now)
        tvNextBoundary.text = when {
            slots.isEmpty() -> "尚未设置时段"
            !cfg.timeWindow.enabled -> "已设置 ${slots.size} 个时段，但管控未启用"
            active != null -> {
                val mins = ((active.untilMs - now).coerceAtLeast(0L) / 60_000L)
                "当前处于禁止使用时段，约 ${mins / 60} 小时 ${mins % 60} 分钟后解除"
            }
            else -> {
                val mins = (TimeWindow.nextBoundaryIn(cfg.timeWindow, now) / 60_000L)
                "现在可以正常使用，距离下一个禁止时段约 ${mins / 60} 小时 ${mins % 60} 分钟"
            }
        }
    }

    /** @brief 新建（slot == null）或修改某个时段 */
    private fun editSlot(existing: TimeSlot?) {
        val startMinute = existing?.startMinute ?: (21 * 60)
        val endMinute = existing?.endMinute ?: (7 * 60)
        val selectedDays = LinkedHashSet(existing?.days ?: (1..7).toList())

        var start = startMinute
        var end = endMinute

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
        }

        // 快捷选择
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(presetRow)
        val dayChecks = ArrayList<CheckBox>(7)

        fun applyPreset(days: Set<Int>) {
            for (i in 1..7) dayChecks[i - 1].isChecked = days.contains(i)
        }

        listOf(
            "每天" to (1..7).toSet(),
            "工作日" to setOf(1, 2, 3, 4, 5),
            "周末" to setOf(6, 7)
        ).forEach { (label, days) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(getColor(R.color.primary))
                background = getDrawable(R.drawable.bg_btn_secondary)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { applyPreset(days) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            presetRow.addView(chip, lp)
        }

        // 7 天勾选：两行（4 + 3）
        for (row in 0 until 2) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            root.addView(line)
            val from = row * 4
            val to = minOf(from + 4, 7)
            for (i in from until to) {
                val cb = CheckBox(this).apply {
                    text = TimeWindow.DAY_NAMES[i]
                    textSize = 13f
                    isChecked = selectedDays.contains(i + 1)
                    tag = i + 1
                }
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                line.addView(cb, lp)
                dayChecks.add(cb)
            }
        }

        // 起止时间
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(timeRow)
        val btnStart = Button(this).apply {
            background = getDrawable(R.drawable.bg_btn_secondary)
            setTextColor(getColor(R.color.primary))
            text = "开始 ${TimeWindow.formatMinute(start)}"
            setOnClickListener {
                TimePickerDialog(
                    this@TimeWindowActivity,
                    { _, h, m ->
                        start = h * 60 + m
                        text = "开始 ${TimeWindow.formatMinute(start)}"
                    },
                    start / 60, start % 60, true
                ).show()
            }
        }
        val btnEnd = Button(this).apply {
            background = getDrawable(R.drawable.bg_btn_secondary)
            setTextColor(getColor(R.color.primary))
            text = "结束 ${TimeWindow.formatMinute(end)}"
            setOnClickListener {
                TimePickerDialog(
                    this@TimeWindowActivity,
                    { _, h, m ->
                        end = h * 60 + m
                        text = "结束 ${TimeWindow.formatMinute(end)}"
                    },
                    end / 60, end % 60, true
                ).show()
            }
        }
        timeRow.addView(btnStart, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val endLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        endLp.marginStart = dp(8)
        timeRow.addView(btnEnd, endLp)

        root.addView(TextView(this).apply {
            text = "结束时间早于开始时间表示跨到次日；两者相同表示所选星期全天禁止。"
            textSize = 11f
            setTextColor(getColor(R.color.text_sub))
            setPadding(0, dp(8), 0, 0)
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加禁止使用时段" else "修改时段")
            .setView(root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val days = LinkedHashSet<Int>()
                for (cb in dayChecks) if (cb.isChecked) days.add(cb.tag as Int)
                if (days.isEmpty()) {
                    Toast.makeText(this, "至少选择一个星期", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val target = existing?.id ?: UUID.randomUUID().toString()
                ConfigRepository.update(this) { c ->
                    val list = c.timeWindow.slots
                    val idx = list.indexOfFirst { it.id == target }
                    if (idx >= 0) {
                        list[idx].days.clear()
                        list[idx].days.addAll(days)
                        list[idx].startMinute = start
                        list[idx].endMinute = end
                        list[idx].enabled = true
                    } else {
                        list.add(
                            TimeSlot(
                                id = target,
                                days = days,
                                startMinute = start,
                                endMinute = end,
                                enabled = true
                            )
                        )
                    }
                }
                dialog.dismiss()
                render()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(slot: TimeSlot) {
        AlertDialog.Builder(this)
            .setTitle("删除时段")
            .setMessage("确定删除「${TimeWindow.slotLabel(slot)}」？")
            .setPositiveButton("删除") { _, _ ->
                ConfigRepository.update(this) { c ->
                    c.timeWindow.slots.removeAll { it.id == slot.id }
                }
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private inner class SlotsAdapter : BaseAdapter() {
        private val slots: List<TimeSlot> get() = cfg.timeWindow.slots

        override fun getCount(): Int = slots.size
        override fun getItem(position: Int): Any = slots[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: LayoutInflater.from(this@TimeWindowActivity)
                .inflate(R.layout.item_time_slot, parent, false)
            val slot = slots[position]
            v.findViewById<TextView>(R.id.slotTitle).text = TimeWindow.slotLabel(slot)
            val cross = slot.startMinute > slot.endMinute
            v.findViewById<TextView>(R.id.slotHint).text =
                if (cross) "禁止使用设备 · 跨零点 · 点按修改" else "禁止使用设备 · 点按修改"
            v.findViewById<Button>(R.id.btnDeleteSlot).setOnClickListener { confirmDelete(slot) }
            return v
        }
    }
}
