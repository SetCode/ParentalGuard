package com.parentalguard.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.parentalguard.app.R
import com.parentalguard.app.core.Formatter
import com.parentalguard.app.core.ForegroundTracker
import com.parentalguard.app.core.KidPrompt
import com.parentalguard.app.core.Permissions
import com.parentalguard.app.core.RuleEngine
import com.parentalguard.app.core.AutoStart
import com.parentalguard.app.core.TimeWindow
import com.parentalguard.app.core.StateBus
import com.parentalguard.app.core.TimeReset
import com.parentalguard.app.core.UiStatus
import com.parentalguard.app.core.UsageAccess
import com.parentalguard.app.core.VoicePrompt
import com.parentalguard.app.data.ConfigRepository
import com.parentalguard.app.data.UsageRepository
import com.parentalguard.app.model.RulesConfig
import com.parentalguard.app.service.GuardStarter
import com.parentalguard.app.update.AppVersion
import com.parentalguard.app.update.ReleaseRepo
import com.parentalguard.app.update.UpdateChecker
import com.parentalguard.app.update.UpdatePrefs
import com.parentalguard.app.update.UpdateResult
import com.parentalguard.app.update.formatPublishTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** @brief 家长主界面：实时状态、权限引导、全部规则开关、儿童提示方式与版本更新检查 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_NOTIFICATION = 2001

        /** @brief 启动时自动检查更新的最小间隔，避免每次冷启动都访问 GitHub */
        private const val AUTO_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000

        /** @brief 分页偏好：记住家长上次停留的页面 */
        private const val PREFS_UI = "pg_ui"
        private const val KEY_PAGE = "mainPage"
    }

    private lateinit var cfg: RulesConfig
    private lateinit var swMaster: Switch
    private lateinit var swSound: Switch
    private lateinit var swVoice: Switch
    private lateinit var swBlockSound: Switch
    private lateinit var swVisual: Switch
    private lateinit var tvGuardRunning: TextView
    private lateinit var tvLockState: TextView
    private lateinit var tvLockReason: TextView
    private lateinit var tvDailyRemaining: TextView
    private lateinit var tvDailyUsed: TextView
    private lateinit var tvSessionRemaining: TextView
    private lateinit var tvSessionUsed: TextView
    private lateinit var tvRestCountdown: TextView
    private lateinit var tvForeground: TextView
    private lateinit var tvAppLimitsSummary: TextView
    private lateinit var pbDaily: ProgressBar
    private lateinit var pbSession: ProgressBar
    private lateinit var permContainer: LinearLayout
    private lateinit var rulesContainer: LinearLayout
    private lateinit var btnRefreshPerm: Button
    private lateinit var btnResetTime: Button
    private lateinit var btnChangePin: Button
    private lateinit var btnShare: Button
    private lateinit var btnTestVoice: Button
    private lateinit var btnTtsSettings: Button
    private lateinit var tvVoiceEngine: TextView
    private lateinit var tvVersionInfo: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var btnCheckUpdate: Button
    private lateinit var tvAutoStartStatus: TextView
    private lateinit var btnResetSettings: Button
    private lateinit var btnAutoStart: Button
    private lateinit var btnOpenReleases: Button
    private lateinit var tabs: List<TextView>
    private lateinit var pages: List<View>
    private var currentPage = 0

    private val handler = Handler(Looper.getMainLooper())
    private var refreshing = false
    private var updatingUi = false
    private var pinPassed = true
    private var checkingUpdate = false

    private data class RuleCardRef(
        val sw: Switch,
        val summary: TextView,
        val enabledOf: (RulesConfig) -> Boolean,
        val summaryOf: (RulesConfig) -> String
    )

    private data class PermRowRef(
        val status: TextView,
        val action: Button,
        val check: () -> Boolean,
        val grant: () -> Unit
    )

    private val ruleCards = ArrayList<RuleCardRef>()
    private val permRows = ArrayList<PermRowRef>()

    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderStatus()
            renderPermissions()
            if (refreshing) handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cfg = ConfigRepository.get(this)
        bindViews()
        buildRuleCards()
        buildPermissionRows()
        setupListeners()
        GuardStarter.start(this)
        requestNotificationPermission()
        renderConfig()
        renderStatus()
        renderPermissions()
        renderVersionInfo()
        // 恢复上次停留的页面；首次进入固定落在概览页
        showPage(getSharedPreferences(PREFS_UI, MODE_PRIVATE).getInt(KEY_PAGE, 0), persist = false)
        askPinIfNeeded()
        // 自动检查放在密码门之后：有密码时由验证成功回调触发，避免提示框压在验证框上
        maybeAutoUpdateCheck()
    }

    override fun onResume() {
        super.onResume()
        cfg = ConfigRepository.get(this)
        renderConfig()
        renderStatus()
        renderPermissions()
        renderAutoStart()
        refreshing = true
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
        GuardStarter.start(this)
    }

    override fun onPause() {
        refreshing = false
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun bindViews() {
        swMaster = findViewById(R.id.swMaster)
        swSound = findViewById(R.id.swSound)
        swVoice = findViewById(R.id.swVoice)
        swBlockSound = findViewById(R.id.swBlockSound)
        swVisual = findViewById(R.id.swVisual)
        tvGuardRunning = findViewById(R.id.tvGuardRunning)
        tvLockState = findViewById(R.id.tvLockState)
        tvLockReason = findViewById(R.id.tvLockReason)
        tvDailyRemaining = findViewById(R.id.tvDailyRemaining)
        tvDailyUsed = findViewById(R.id.tvDailyUsed)
        tvSessionRemaining = findViewById(R.id.tvSessionRemaining)
        tvSessionUsed = findViewById(R.id.tvSessionUsed)
        tvRestCountdown = findViewById(R.id.tvRestCountdown)
        tvForeground = findViewById(R.id.tvForeground)
        tvAppLimitsSummary = findViewById(R.id.tvAppLimitsSummary)
        pbDaily = findViewById(R.id.pbDaily)
        pbSession = findViewById(R.id.pbSession)
        permContainer = findViewById(R.id.permContainer)
        rulesContainer = findViewById(R.id.rulesContainer)
        btnRefreshPerm = findViewById(R.id.btnRefreshPerm)
        btnResetTime = findViewById(R.id.btnResetTime)
        btnChangePin = findViewById(R.id.btnChangePin)
        btnShare = findViewById(R.id.btnShare)
        btnTestVoice = findViewById(R.id.btnTestVoice)
        btnTtsSettings = findViewById(R.id.btnTtsSettings)
        tvVoiceEngine = findViewById(R.id.tvVoiceEngine)
        tvVersionInfo = findViewById(R.id.tvVersionInfo)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        tvAutoStartStatus = findViewById(R.id.tvAutoStartStatus)
        btnResetSettings = findViewById(R.id.btnResetSettings)
        btnAutoStart = findViewById(R.id.btnAutoStart)
        btnOpenReleases = findViewById(R.id.btnOpenReleases)
        // 分页标签与页面容器：索引一一对应，切换时只改可见性
        tabs = listOf(
            findViewById(R.id.tabOverview),
            findViewById(R.id.tabRules),
            findViewById(R.id.tabPrompt),
            findViewById(R.id.tabSettings)
        )
        pages = listOf(
            findViewById(R.id.pageOverview),
            findViewById(R.id.pageRules),
            findViewById(R.id.pagePrompt),
            findViewById(R.id.pageSettings)
        )
    }

    /** @brief 切换分页：只改可见性与标签选中态，各页滚动位置与已绑定视图都保留 */
    private fun showPage(index: Int, persist: Boolean = true) {
        val i = index.coerceIn(0, pages.size - 1)
        val changed = i != currentPage
        currentPage = i
        for (idx in pages.indices) {
            pages[idx].visibility = if (idx == i) View.VISIBLE else View.GONE
            tabs[idx].isSelected = idx == i
        }
        if (persist) {
            getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit().putInt(KEY_PAGE, i).apply()
        }
        // 回到概览页时刷新一次，避免看到离开期间停留的旧数值
        if (i == 0 && changed) renderStatus()
    }

    private fun setupListeners() {
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { showPage(index) }
        }
        swMaster.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            ConfigRepository.update(this) { it.masterEnabled = checked }
            renderConfig()
        }
        swSound.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            ConfigRepository.update(this) { it.soundEnabled = checked }
            renderConfig()
        }
        swVoice.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            ConfigRepository.update(this) { it.voiceEnabled = checked }
        }
        swBlockSound.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            ConfigRepository.update(this) { it.blockSoundEnabled = checked }
        }
        swVisual.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            ConfigRepository.update(this) { it.visualPromptEnabled = checked }
        }
        btnRefreshPerm.setOnClickListener {
            renderPermissions()
            GuardStarter.start(this)
            Toast.makeText(this, "已重新检测权限", Toast.LENGTH_SHORT).show()
        }
        btnResetTime.setOnClickListener {
            if (!pinPassed) {
                Toast.makeText(this, "请先通过家长验证", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showResetConfirmDialog()
        }
        btnChangePin.setOnClickListener { showPinSettingDialog() }
        btnShare.setOnClickListener { startActivity(Intent(this, ShareActivity::class.java)) }
        btnTestVoice.setOnClickListener {
            cfg = ConfigRepository.get(this)
            if (!cfg.soundEnabled && !cfg.visualPromptEnabled) {
                Toast.makeText(this, "请先开启「有声提示」或「弹出图形提示」", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            KidPrompt.test(this, cfg)
            if (!cfg.soundEnabled) {
                Toast.makeText(this, "当前为静音模式，仅显示图形提示", Toast.LENGTH_SHORT).show()
            }
        }
        btnTtsSettings.setOnClickListener { openSoundSettings() }
        // 检查失败（如被 GitHub 限流）时家长仍有手动入口
        btnOpenReleases.setOnClickListener { openUrl(ReleaseRepo.RELEASES_PAGE) }
        btnResetSettings.setOnClickListener { confirmResetSettings() }
        btnAutoStart.setOnClickListener {
            if (!AutoStart.openSettings(this)) {
                Toast.makeText(this, "本机没有直达入口，请到「设置 → 应用 → 自启动管理」手动允许", Toast.LENGTH_LONG).show()
            }
        }
        btnCheckUpdate.setOnClickListener {
            if (!pinPassed) {
                Toast.makeText(this, "请先通过家长验证", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runUpdateCheck(manual = true)
        }
    }

    /** @brief 打开系统声音设置，便于家长调高媒体音量 */
    private fun openSoundSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "请在系统设置中调高媒体音量", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** @brief 展示内置语音与媒体音量状态；内置音频无需设备语音引擎 */
    private fun renderVoiceEngine() {
        tvVoiceEngine.text =
            "内置中文语音：已就绪（随应用安装，无需设备语音引擎、无需联网）\n" +
                VoicePrompt.volumeText(this)
        tvVoiceEngine.setTextColor(getColor(R.color.success))
    }

    // ---------------------------------------------------------------- 时间重置

    /** @brief 明确的重置入口：二次确认列清会清零与不会改动的范围，避免误触 */
    private fun showResetConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("重置今日计时")
            .setMessage(
                "将把当前监控对象的以下时间值恢复到初始状态：\n\n" +
                    "• 今日设备总使用时长与已用时长\n" +
                    "• 单次连续使用时长与剩余时间\n" +
                    "• 强制休息倒计时\n" +
                    "• 各应用今日累计时长\n" +
                    "• 进行中的计时片段、暂停锚点与提醒标志\n\n" +
                    "不会修改：管控规则、时长上限、应用白名单、单应用限额、家长密码、" +
                    "提示方式、监控对象与局域网连接。\n\n" +
                    "重置会同步到同一数据集的其他设备，且不可撤销。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("确认重置") { _, _ -> doResetTime() }
            .show()
    }

    /** @brief 执行重置并立即刷新界面，结果无论成功失败都明确告知家长 */
    private fun doResetTime() {
        val r = TimeReset.perform(this)
        cfg = ConfigRepository.get(this)
        renderStatus()
        Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- 规则卡片

    private fun addRuleCard(
        title: String,
        actionLabel: String,
        enabledOf: (RulesConfig) -> Boolean,
        summaryOf: (RulesConfig) -> String,
        onToggle: (Boolean) -> Unit,
        onAction: () -> Unit,
        localOnly: Boolean = false
    ) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_rule_card, rulesContainer, false)
        val sw = v.findViewById<Switch>(R.id.ruleSwitch)
        val summary = v.findViewById<TextView>(R.id.ruleSummary)
        v.findViewById<TextView>(R.id.ruleTitle).text =
            if (localOnly) "$title · 仅本机" else title
        val btn = v.findViewById<Button>(R.id.ruleAction)
        btn.text = actionLabel
        sw.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            onToggle(checked)
            renderConfig()
        }
        btn.setOnClickListener { onAction() }
        v.setOnClickListener { sw.isChecked = !sw.isChecked }
        rulesContainer.addView(v)
        val renderedSummary: (RulesConfig) -> String = { c ->
            val s = summaryOf(c)
            if (localOnly) "$s\n仅本机生效，不同步到局域网其他设备" else s
        }
        ruleCards.add(RuleCardRef(sw, summary, enabledOf, renderedSummary))
    }

    private fun buildRuleCards() {
        addRuleCard(
            title = "每日设备总使用时长",
            actionLabel = "设置每日时长",
            enabledOf = { it.dailyTotal.enabled },
            summaryOf = {
                if (it.dailyTotal.enabled) {
                    "每天累计可用 ${it.dailyTotal.limitMinutes} 分钟；达到上限后当天限制继续使用，次日 00:00 自动解除"
                } else {
                    "未启用：不限制每日总使用时长"
                }
            },
            onToggle = { v -> ConfigRepository.update(this) { it.dailyTotal.enabled = v } },
            onAction = {
                showMinutesDialog(
                    "每日设备总使用时长",
                    "请输入每天允许使用的总分钟数（1 ~ 1440）",
                    cfg.dailyTotal.limitMinutes
                ) { m ->
                    ConfigRepository.update(this) { it.dailyTotal.limitMinutes = m }
                    renderConfig()
                }
            }
        )

        addRuleCard(
            title = "单次连续使用与强制休息",
            actionLabel = "设置连续 / 休息时长",
            enabledOf = { it.session.enabled },
            summaryOf = {
                if (it.session.enabled) {
                    "连续使用 ${it.session.continuousMinutes} 分钟后强制休息 ${it.session.restMinutes} 分钟；休息期间限制设备，结束后自动恢复"
                } else {
                    "未启用：不限制单次连续使用时长"
                }
            },
            onToggle = { v -> ConfigRepository.update(this) { it.session.enabled = v } },
            onAction = { showSessionDialog() }
        )

        addRuleCard(
            title = "时间段管控（禁止使用时段）",
            actionLabel = "管理时段",
            enabledOf = { it.timeWindow.enabled },
            summaryOf = { c ->
                val slots = c.timeWindow.slots
                when {
                    slots.isEmpty() -> "未设置时段：可添加「每天 21:00–07:00」这类禁止使用的时间段"
                    !c.timeWindow.enabled -> "已设置 ${slots.size} 个时段，当前未启用（规则不生效）"
                    else -> {
                        val head = "已启用 ${slots.size} 个时段：" +
                                slots.take(2).joinToString("；") { TimeWindow.slotLabel(it) }
                        if (slots.size > 2) "$head 等" else head
                    }
                }
            },
            onToggle = { v -> ConfigRepository.update(this) { it.timeWindow.enabled = v } },
            onAction = { startActivity(Intent(this, TimeWindowActivity::class.java)) }
        )

        addRuleCard(
            title = "应用名单（可玩 / 休息放行 / 免计时）",
            actionLabel = "管理三类名单",
            enabledOf = { it.whitelist.enabled },
            summaryOf = { c ->
                val playable = c.whitelist.packages.size
                val rest = c.restWhitelist.packages.size
                val noCount = c.noCountWhitelist.packages.size
                val head = if (c.whitelist.enabled) {
                    "可玩白名单已启用：$playable 个应用（含本应用），名单外应用一打开就返回桌面"
                } else {
                    "可玩白名单未启用：所有应用均可打开（已勾选 $playable 个）"
                }
                val restState = if (c.restWhitelist.enabled) "已开" else "已关"
                val countState = if (c.noCountWhitelist.enabled) "已开" else "已关"
                "$head\n休息放行 $restState（$rest 个） · 免计时 $countState（$noCount 个） · 均含本应用"
            },
            onToggle = { v -> ConfigRepository.update(this) { c -> c.whitelist.enabled = v } },
            onAction = {
                startActivity(Intent(this, WhitelistActivity::class.java))
            },
            localOnly = true
        )

        addRuleCard(
            title = "单应用每日累计时长",
            actionLabel = "新增 / 修改规则",
            enabledOf = { c -> c.appLimits.isNotEmpty() && c.appLimits.any { it.enabled } },
            summaryOf = {
                val on = it.appLimits.count { r -> r.enabled }
                "共 ${it.appLimits.size} 条规则，已启用 $on 条；可对单个或多个应用分别设置每日上限"
            },
            onToggle = { v ->
                ConfigRepository.update(this) { c -> c.appLimits.forEach { it.enabled = v } }
            },
            onAction = {
                startActivity(Intent(this, AppLimitActivity::class.java))
            }
        )
    }

    // ---------------------------------------------------------------- 权限行

    private fun addPermRow(name: String, check: () -> Boolean, grant: () -> Unit) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_permission_row, permContainer, false)
        v.findViewById<TextView>(R.id.permName).text = name
        val status = v.findViewById<TextView>(R.id.permStatus)
        val action = v.findViewById<Button>(R.id.permAction)
        action.setOnClickListener { grant() }
        permContainer.addView(v)
        permRows.add(PermRowRef(status, action, check, grant))
    }

    private fun buildPermissionRows() {
        addPermRow("使用情况访问（时长统计）", { Permissions.usageAccess(this) }, { openUsageSettings() })
        addPermRow("无障碍服务（前台应用监测）", { Permissions.accessibility(this) }, { openAccessibilitySettings() })
        addPermRow("悬浮窗（锁定遮罩与图形提示）", { Permissions.overlay(this) }, { openOverlaySettings() })
        addPermRow("通知（常驻服务）", { Permissions.notification(this) }, { requestNotificationPermission() })
        addPermRow("忽略电池优化", { Permissions.battery(this) }, { openBatterySettings() })
    }

    private fun renderPermissions() {
        val green = getColor(R.color.success)
        val red = getColor(R.color.danger)
        for (r in permRows) {
            val ok = r.check()
            r.status.text = if (ok) "已开启" else "未开启"
            r.status.setTextColor(if (ok) green else red)
            r.action.visibility = if (ok) View.GONE else View.VISIBLE
        }
    }

    private fun openUsageSettings() {
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))
        try {
            startActivity(direct)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "请在系统设置中开启「使用情况访问」", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "请在系统设置 → 无障碍中开启本服务", Toast.LENGTH_LONG).show()
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            Toast.makeText(this, "请在系统设置中开启「显示在其他应用上层」", Toast.LENGTH_LONG).show()
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "请在电池设置中关闭本应用的电池优化", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Permissions.notification(this)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        }
    }

    // ---------------------------------------------------------------- 编辑对话框

    private fun showMinutesDialog(title: String, hint: String, current: Int, onOk: (Int) -> Unit) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_value, null)
        v.findViewById<TextView>(R.id.dialogHint).text = hint
        val edit = v.findViewById<EditText>(R.id.dialogValue)
        edit.setText(current.toString())
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(v)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val n = edit.text.toString().trim().toIntOrNull()
                if (n == null || n !in 1..1440) {
                    Toast.makeText(this, "请输入 1 ~ 1440 之间的分钟数", Toast.LENGTH_SHORT).show()
                } else {
                    onOk(n)
                }
            }
            .show()
    }

    private fun showSessionDialog() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_session, null)
        val eContinuous = v.findViewById<EditText>(R.id.editContinuous)
        val eRest = v.findViewById<EditText>(R.id.editRest)
        eContinuous.setText(cfg.session.continuousMinutes.toString())
        eRest.setText(cfg.session.restMinutes.toString())
        AlertDialog.Builder(this)
            .setTitle("连续使用与强制休息")
            .setView(v)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val c = eContinuous.text.toString().trim().toIntOrNull()
                val r = eRest.text.toString().trim().toIntOrNull()
                if (c == null || c !in 1..1440 || r == null || r !in 0..1440) {
                    Toast.makeText(this, "连续时长需 1 ~ 1440，休息时长需 0 ~ 1440", Toast.LENGTH_SHORT).show()
                } else {
                    ConfigRepository.update(this) {
                        it.session.continuousMinutes = c
                        it.session.restMinutes = r
                    }
                    renderConfig()
                }
            }
            .show()
    }

    private fun showPinSettingDialog() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_value, null)
        v.findViewById<TextView>(R.id.dialogHint).text =
            "设置 4 ~ 8 位数字家长密码；留空表示关闭密码保护"
        val edit = v.findViewById<EditText>(R.id.dialogValue)
        edit.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        edit.setText(cfg.parentPin)
        AlertDialog.Builder(this)
            .setTitle("修改家长密码")
            .setView(v)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val s = edit.text.toString().trim()
                if (s.isNotEmpty() && (s.length !in 4..8 || !s.all { it.isDigit() })) {
                    Toast.makeText(this, "密码需为 4 ~ 8 位数字，或留空关闭", Toast.LENGTH_SHORT).show()
                } else {
                    ConfigRepository.update(this) { it.parentPin = s }
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** @brief 冷启动密码门：儿童无法通过家长入口修改规则 */
    private fun askPinIfNeeded() {
        if (cfg.parentPin.isEmpty()) {
            pinPassed = true
            return
        }
        pinPassed = false
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_value, null)
        v.findViewById<TextView>(R.id.dialogHint).text = "请输入家长密码后继续"
        val edit = v.findViewById<EditText>(R.id.dialogValue)
        edit.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        edit.hint = "家长密码"
        val dlg = AlertDialog.Builder(this)
            .setTitle("家长验证")
            .setView(v)
            .setPositiveButton("确定", null)
            .setCancelable(false)
            .create()
        dlg.setCanceledOnTouchOutside(false)
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (edit.text.toString() == cfg.parentPin) {
                    pinPassed = true
                    dlg.dismiss()
                    maybeAutoUpdateCheck()
                } else {
                    edit.text.clear()
                    edit.error = "密码不正确"
                }
            }
        }
        dlg.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                finish()
                true
            } else {
                false
            }
        }
        dlg.show()
    }

    // ---------------------------------------------------------------- 版本与更新

    /** @brief 展示本机版本号与上次检查时间，未检查过时给出明确文案 */
    /**
     * @brief 重置设置：两段确认，避免误触清掉整套规则
     *
     * 第一段说明会清掉什么、保留什么；第二段才是不可撤销的最终确认。
     */
    private fun confirmResetSettings() {
        val cfg = ConfigRepository.get(this)
        val affected = buildString {
            append("将清除：\n")
            append("• 每日总时长、连续使用与强制休息设置\n")
            append("• 三类应用名单（可玩 / 休息放行 / 免计时）\n")
            append("• 时间段管控（${cfg.timeWindow.slots.size} 个时段）\n")
            append("• 单应用每日限额（${cfg.appLimits.size} 条）\n")
            append("• 声音与图形提示开关\n\n")
            append("保留：家长密码、今日计时数据、开机自启动与局域网共享设置。")
        }
        AlertDialog.Builder(this)
            .setTitle("重置全部规则设置？")
            .setMessage(affected)
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ -> doResetSettings() }
            .show()
    }

    private fun doResetSettings() {
        AlertDialog.Builder(this)
            .setTitle("再次确认")
            .setMessage("此操作不可撤销，重置后所有规则需重新设置。确定继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认重置") { _, _ ->
                ConfigRepository.update(this) { c ->
                    c.resetToDefaults()
                    // 立刻重新套用「系统应用默认进两名单」，不必等下次冷启动
                    c.applySystemDefaults(UsageAccess.systemPackages(this))
                    c.ensureSelf(packageName)
                }
                cfg = ConfigRepository.get(this)
                renderConfig()
                renderStatus()
                Toast.makeText(this, "已重置全部规则设置", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** @brief 展示上次开机自启动时间，让家长能确认自启动确实生效 */
    private fun renderAutoStart() {
        val last = AutoStart.lastBootAt(this)
        val source = AutoStart.lastBootSource(this)
        tvAutoStartStatus.text = if (last <= 0L) {
            "上次开机自启动：尚未记录（开机一次后回来查看）"
        } else {
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            val src = if (source.isEmpty()) "" else "（$source）"
            "上次自启动：${fmt.format(java.util.Date(last))}$src"
        }
    }

    private fun renderVersionInfo() {
        tvVersionInfo.text = "当前版本 ${AppVersion.current(this)}（build ${AppVersion.currentCode(this)}）"
        val last = UpdatePrefs.lastCheckMs(this)
        tvUpdateStatus.text = if (last <= 0L) {
            "尚未检查更新"
        } else {
            "上次检查：${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(last))}"
        }
        tvUpdateStatus.setTextColor(getColor(R.color.text_sub))
    }

    /** @brief 启动时自动检查：受 12 小时间隔与家长密码限制，失败不打扰家长 */
    private fun maybeAutoUpdateCheck() {
        if (!pinPassed || checkingUpdate) return
        val now = System.currentTimeMillis()
        if (now - UpdatePrefs.lastCheckMs(this) < AUTO_CHECK_INTERVAL_MS) return
        runUpdateCheck(manual = false)
    }

    /** @brief 执行检查；结果回到主线程后按四类情况分别渲染 */
    private fun runUpdateCheck(manual: Boolean) {
        if (checkingUpdate) {
            if (manual) Toast.makeText(this, "正在检查，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        checkingUpdate = true
        btnCheckUpdate.isEnabled = false
        btnCheckUpdate.text = "检查中…"
        if (manual) {
            tvUpdateStatus.text = "正在连接 GitHub…"
            tvUpdateStatus.setTextColor(getColor(R.color.text_sub))
        }
        UpdateChecker.checkAsync(this) { result ->
            checkingUpdate = false
            if (isFinishing || isDestroyed) return@checkAsync
            btnCheckUpdate.isEnabled = true
            btnCheckUpdate.text = "检查更新"
            UpdatePrefs.markChecked(this, System.currentTimeMillis())
            renderUpdateResult(result, manual)
        }
    }

    /** @brief 按结果类型渲染状态行；手动触发时额外用 Toast 明确告知，自动检查静默处理失败 */
    private fun renderUpdateResult(result: UpdateResult, manual: Boolean) {
        when (result) {
            is UpdateResult.Available -> {
                tvUpdateStatus.text = "发现新版本 v${result.info.version}（当前 ${result.current}）"
                tvUpdateStatus.setTextColor(getColor(R.color.danger))
                showUpdateDialog(result)
            }
            is UpdateResult.Latest -> {
                tvUpdateStatus.text = "已是最新版本 v${result.latest}"
                tvUpdateStatus.setTextColor(getColor(R.color.success))
                if (manual) {
                    Toast.makeText(this, "当前已是最新版本 v${result.latest}", Toast.LENGTH_SHORT).show()
                }
            }
            is UpdateResult.NoRelease -> {
                tvUpdateStatus.text = "GitHub 上还没有正式发布版本（当前 ${result.current}）"
                tvUpdateStatus.setTextColor(getColor(R.color.warning))
                if (manual) {
                    Toast.makeText(this, "仓库尚未发布任何版本，暂无更新可装", Toast.LENGTH_LONG).show()
                }
            }
            is UpdateResult.NetworkError -> {
                tvUpdateStatus.text = "检查失败：${result.detail}"
                tvUpdateStatus.setTextColor(getColor(R.color.danger))
                if (manual) {
                    Toast.makeText(this, "检查失败：${result.detail}", Toast.LENGTH_LONG).show()
                }
            }
            is UpdateResult.FormatError -> {
                tvUpdateStatus.text = "版本信息异常：${result.detail}"
                tvUpdateStatus.setTextColor(getColor(R.color.warning))
                if (manual) {
                    Toast.makeText(this, "版本信息异常：${result.detail}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** @brief 新版本提示：列出当前/最新版本与发布说明，提供安装包下载与发布页两个入口 */
    private fun showUpdateDialog(result: UpdateResult.Available) {
        val info = result.info
        val message = buildString {
            append("当前版本：").append(result.current).append('\n')
            append("最新版本：v").append(info.version)
            if (info.publishedAt.isNotEmpty()) {
                append('\n').append("发布时间：").append(formatPublishTime(info.publishedAt))
            }
            append("\n\n")
            append(info.notes.ifEmpty { "本次发布未填写说明，可打开发布页查看详情。" })
            append("\n\n覆盖安装不会影响本机已有的规则与计时配置。")
        }
        AlertDialog.Builder(this)
            .setTitle("发现新版本 v${info.version}")
            .setMessage(message)
            .setNegativeButton("稍后", null)
            .setNeutralButton("发布页") { _, _ -> openUrl(info.pageUrl) }
            .setPositiveButton("下载安装包") { _, _ -> openUrl(info.apkUrl) }
            .show()
    }

    /** @brief 交给系统浏览器 / 下载器打开链接，无可用应用时提示手动访问 */
    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开浏览器，请手动访问：\n$url", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- 状态渲染

    private fun renderConfig() {
        updatingUi = true
        swMaster.isChecked = cfg.masterEnabled
        swSound.isChecked = cfg.soundEnabled
        swVoice.isChecked = cfg.voiceEnabled
        swBlockSound.isChecked = cfg.blockSoundEnabled
        swVisual.isChecked = cfg.visualPromptEnabled
        // 总静音时把两个声音子开关置灰，避免家长误以为仍在生效
        swVoice.isEnabled = cfg.soundEnabled
        swBlockSound.isEnabled = cfg.soundEnabled
        for (c in ruleCards) {
            c.sw.isChecked = c.enabledOf(cfg)
            c.summary.text = c.summaryOf(cfg)
        }
        updatingUi = false
    }

    private fun renderStatus() {
        val now = System.currentTimeMillis()
        val s = if (now - StateBus.status.now < 5_000L) StateBus.status else fallbackStatus(now)

        tvGuardRunning.text = buildString {
            append("服务：").append(if (StateBus.serviceRunning) "运行中" else "未运行")
            append(" · 无障碍：").append(if (StateBus.accessibilityConnected) "已连接" else "未连接")
            append(" · 悬浮窗：").append(if (Permissions.overlay(this@MainActivity)) "已授权" else "未授权")
            if (s.overlayError.isNotEmpty()) append("\n").append(s.overlayError)
        }

        // 禁玩时段单独上台：它不弹遮罩，只把名单外应用送回桌面，家长需要一眼看到当前处于该状态
        val window = TimeWindow.activeSlot(cfg.timeWindow, System.currentTimeMillis())
        when {
            !cfg.masterEnabled -> {
                tvLockState.text = "状态：管控总开关已关闭"
                tvLockState.setTextColor(getColor(R.color.warning))
            }
            window != null -> {
                tvLockState.text = "状态：禁玩时段（${TimeWindow.dayLabel(window.slot.days)} " +
                        "${TimeWindow.formatMinute(window.slot.startMinute)}–" +
                        "${TimeWindow.formatMinute(window.slot.endMinute)}）"
                tvLockState.setTextColor(getColor(R.color.warning))
            }
            s.locked -> {
                tvLockState.text = "状态：已锁定 · ${s.lockTitle}"
                tvLockState.setTextColor(getColor(R.color.danger))
            }
            else -> {
                tvLockState.text = "状态：设备可用"
                tvLockState.setTextColor(getColor(R.color.success))
            }
        }
        tvLockReason.text = when {
            window != null -> {
                val mins = ((window.untilMs - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000L)
                "禁玩时段约 ${mins / 60} 小时 ${mins % 60} 分钟后解除；\n" +
                        "不在「休息放行」名单的应用会被立即送回桌面，本应用不受影响"
            }
            s.lockReasons.isEmpty() -> "当前无限制"
            else -> s.lockReasons.joinToString("\n") { "• $it" }
        }

        // 规则关闭时用次要灰显示「未启用」并隐藏空进度条，避免抢走对真实数值的注意力
        if (cfg.dailyTotal.enabled) {
            tvDailyRemaining.text = Formatter.hmsFull(s.dailyRemainingMs)
            tvDailyRemaining.setTextColor(getColor(R.color.primary))
            pbDaily.visibility = View.VISIBLE
            pbDaily.progress = percent(s.dailyUsedMs, s.dailyLimitMs)
        } else {
            tvDailyRemaining.text = "未启用"
            tvDailyRemaining.setTextColor(getColor(R.color.text_sub))
            pbDaily.visibility = View.GONE
        }
        tvDailyUsed.text = "今日已用 ${Formatter.hmsFull(s.dailyUsedMs)}"

        if (cfg.session.enabled) {
            tvSessionRemaining.text = Formatter.hmsFull(s.sessionRemainingMs)
            tvSessionRemaining.setTextColor(getColor(R.color.primary))
            pbSession.visibility = View.VISIBLE
            pbSession.progress = percent(s.continuousUsedMs, s.sessionLimitMs)
        } else {
            tvSessionRemaining.text = "未启用"
            tvSessionRemaining.setTextColor(getColor(R.color.text_sub))
            pbSession.visibility = View.GONE
        }
        tvSessionUsed.text = "本次已连续 ${Formatter.hmsFull(s.continuousUsedMs)}"

        if (s.restRemainingMs > 0L) {
            tvRestCountdown.text = "休息中，剩余 ${Formatter.hmsFull(s.restRemainingMs)}"
            tvRestCountdown.setTextColor(getColor(R.color.warning))
        } else {
            tvRestCountdown.text = "未在休息"
            tvRestCountdown.setTextColor(getColor(R.color.text_sub))
        }
        tvForeground.text = "当前前台应用：${s.foregroundLabel}" +
                if (pinPassed) "" else "（家长验证中）"

        tvAppLimitsSummary.text = if (s.appRemaining.isEmpty()) {
            "单应用限额：未配置"
        } else {
            "单应用今日剩余：\n" + s.appRemaining.joinToString("\n") {
                "• ${it.first}  ${Formatter.human(it.second)}"
            }
        }
        renderVoiceEngine()
    }

    private fun percent(used: Long, limit: Long): Int =
        if (limit <= 0L) 0 else ((used * 100L) / limit).toInt().coerceIn(0, 100)

    /** @brief 服务尚未启动时的降级状态：直接读取本地用量与规则计算 */
    private fun fallbackStatus(now: Long): UiStatus {
        val snap = UsageRepository.snapshot(this, now)
        val fg = ForegroundTracker.currentPackage
        val eval = RuleEngine.evaluate(this, cfg, snap, fg, now)
        return UiStatus(
            now = now,
            masterEnabled = cfg.masterEnabled,
            serviceRunning = StateBus.serviceRunning,
            accessibilityConnected = StateBus.accessibilityConnected,
            dailyEnabled = cfg.dailyTotal.enabled,
            dailyLimitMs = cfg.dailyTotal.limitMinutes.toLong() * RuleEngine.MINUTE_MS,
            dailyUsedMs = snap.totalMs,
            sessionEnabled = cfg.session.enabled,
            sessionLimitMs = cfg.session.continuousMinutes.toLong() * RuleEngine.MINUTE_MS,
            continuousUsedMs = snap.continuousMs,
            restRemainingMs = (snap.restUntilMs - now).coerceAtLeast(0L),
            locked = eval.locked,
            lockTitle = eval.primary?.title.orEmpty(),
            lockReasons = eval.reasons,
            foregroundLabel = if (fg.isEmpty()) "未知" else UsageAccess.appLabel(this, fg),
            overlayError = StateBus.overlayError
        )
    }
}
