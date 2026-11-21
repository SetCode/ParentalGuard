package com.tensun.parentalguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.tensun.parentalguard.R
import com.tensun.parentalguard.core.Formatter
import com.tensun.parentalguard.core.ForegroundTracker
import com.tensun.parentalguard.core.KidPrompt
import com.tensun.parentalguard.core.LockKind
import com.tensun.parentalguard.core.LockScope
import com.tensun.parentalguard.core.Permissions
import com.tensun.parentalguard.core.RuleEngine
import com.tensun.parentalguard.core.StateBus
import com.tensun.parentalguard.core.UiStatus
import com.tensun.parentalguard.core.UsageAccess
import com.tensun.parentalguard.data.ConfigRepository
import com.tensun.parentalguard.data.UsageRepository
import com.tensun.parentalguard.model.RulesConfig
import com.tensun.parentalguard.model.UsageSnapshot
import com.tensun.parentalguard.share.ShareSync
import com.tensun.parentalguard.ui.MainActivity

/**
 * @brief 常驻管控服务
 *
 * 每秒执行一次：恢复/重置当日计数 -> 汇总当前用量 -> 叠加判定全部规则 ->
 * 选择最严格限制 -> 更新遮罩、通知、状态 -> 触发休息与图形/语音提示。
 */
class GuardService : Service() {

    companion object {
        const val ACTION_REFRESH = "com.tensun.parentalguard.REFRESH"
        const val ACTION_BOOT = "com.tensun.parentalguard.BOOT"
        private const val CHANNEL_ID = "guard_status"
        private const val NOTIF_ID = 1001
        private const val TICK_MS = 1000L
        private const val TAG = "GuardService"

        @Volatile
        private var live: GuardService? = null

        /**
         * @brief 窗口切换事件触发一次即时心跳：退出应用时立刻结算暂停，
         * 重新进入时立刻开启新片段，避免最多 1 秒的计时漂移
         */
        fun requestTick() {
            val s = live ?: return
            s.handler.post {
                try {
                    s.tick()
                } catch (e: Exception) {
                    Log.w(TAG, "即时刷新异常: ${e.message}")
                }
            }
        }

        /**
         * @brief 时间重置：清理进程内计时相关的运行态，并立即重算状态、遮罩与通知
         *
         * 服务未运行（live == null）时为空操作，界面按降级路径读取已清零的数据。
         */
        fun onTimeReset() {
            val s = live ?: return
            s.handler.post {
                try {
                    s.resetRuntimeState()
                    s.tick()
                } catch (e: Exception) {
                    Log.w(TAG, "重置后刷新异常: ${e.message}")
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private lateinit var overlay: LockOverlay
    private var lastNotificationKey = ""
    private var lastLocked = false
    private var lastBlockedPkg = ""
    private var lastHomeAttemptMs = 0L
    private var restTotalMs = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Exception) {
                Log.w(TAG, "心跳异常: ${e.message}")
            }
            if (ticking) handler.postDelayed(this, TICK_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val now = System.currentTimeMillis()
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> UsageRepository.onScreenOff(this@GuardService, now)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT ->
                    UsageRepository.onScreenOn(this@GuardService, now)
            }
            try { tick() } catch (e: Exception) { /* 忽略单次刷新异常 */ }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("儿童设备管控", "管控服务启动中…"))
        overlay = LockOverlay(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            registerReceiver(screenReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "注册屏幕广播失败: ${e.message}")
        }

        UsageRepository.onBoot(this, System.currentTimeMillis())
        live = this
        StateBus.serviceRunning = true
        startTicking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val now = System.currentTimeMillis()
        if (intent?.action == ACTION_BOOT) {
            UsageRepository.onBoot(this, now)
        } else {
            UsageRepository.ensureToday(this, now)
        }
        if (!ticking) startTicking()
        try { tick() } catch (e: Exception) { Log.w(TAG, "刷新异常: ${e.message}") }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ticking = false
        handler.removeCallbacks(tickRunnable)
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) { /* 已注销 */ }
        if (::overlay.isInitialized) overlay.hide()
        live = null
        StateBus.serviceRunning = false
        super.onDestroy()
    }

    private fun startTicking() {
        ticking = true
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    /**
     * @brief 清空休息进度、锁定 / 拦截去重、通知去重缓存与全屏遮罩，回到刚启动时的运行态
     *
     * 这些值只在进程内保存，但会直接影响「已结束 / 休息中」等状态残留：
     * 不清空会导致重置后遮罩不消失、通知仍显示旧数值、提示被去重键吞掉。
     */
    private fun resetRuntimeState() {
        restTotalMs = 0L
        lastLocked = false
        lastBlockedPkg = ""
        lastHomeAttemptMs = 0L
        lastNotificationKey = ""
        KidPrompt.clearThrottle()
        if (::overlay.isInitialized) overlay.hide()
    }

    /** @brief 单次心跳：全部规则的判定与执行 */
    private fun tick() {
        val now = System.currentTimeMillis()
        val cfg = ConfigRepository.get(this)
        UsageRepository.ensureToday(this, now)

        val restFinished = UsageRepository.finishRestIfNeeded(this, now)
        if (restFinished) {
            restTotalMs = 0L
            KidPrompt.fire(this, cfg, KidPrompt.Event.REST_END, "rest_end")
            lastLocked = false
        }

        val fg = currentForeground(now)
        val screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

        var snap = UsageRepository.snapshot(this, now)
        var eval = RuleEngine.evaluate(this, cfg, snap, fg, now)

        // 锁定期间不累计使用时长；屏幕熄灭时同样暂停。
        // 计时范围严格按当前前台判定：桌面 / 系统界面 / 后台 / 未知前台由 sync 统一暂停，
        // 不再用哨兵包名把非应用状态继续算成设备使用。
        UsageRepository.sync(this, now, fg, screenOn, !eval.locked)
        // 把「本设备」的计时分量写入共享数据集，供局域网内其他设备合并
        ShareSync.onLocalTimingChanged(
            now,
            screenOn && !eval.locked && UsageAccess.isCountableForeground(this, fg),
            fg
        )
        snap = UsageRepository.snapshot(this, now)
        eval = RuleEngine.evaluate(this, cfg, snap, fg, now)

        // 单次连续使用到点：进入强制休息（restMinutes = 0 时仅清零连续计数）
        if (cfg.masterEnabled && cfg.session.enabled && !eval.locked &&
            screenOn && snap.restUntilMs <= now
        ) {
            val limitMs = cfg.session.continuousMinutes.toLong() * RuleEngine.MINUTE_MS
            if (snap.continuousMs >= limitMs) {
                val restMs = cfg.session.restMinutes.toLong() * RuleEngine.MINUTE_MS
                if (restMs > 0L) {
                    UsageRepository.beginRest(this, now, restMs)
                    restTotalMs = restMs
                    KidPrompt.fire(this, cfg, KidPrompt.Event.REST_START, "rest")
                } else {
                    UsageRepository.resetContinuous(this, now)
                }
                snap = UsageRepository.snapshot(this, now)
                eval = RuleEngine.evaluate(this, cfg, snap, fg, now)
            }
        }

        playWarnings(cfg, snap)

        if (eval.locked) {
            val primary = eval.primary!!
            val appOnly = primary.scope == LockScope.CURRENT_APP
            // 应用级限制用「立即返回桌面 + 短图形卡片 + 语音」提示，不再弹全屏遮罩：
            // 既避免遮罩盖住桌面和图形卡片，也避免同一次拦截播报两遍
            if (!lastLocked && !appOnly) {
                val ev = lockEvent(primary.kind)
                // REST_START 与 RESTING 共用去重键，避免 1 秒内重复播报
                val key = if (primary.kind == LockKind.REST) "rest" else "locked:" + primary.kind.name
                KidPrompt.fire(this, cfg, ev, key)
            }

            if (appOnly && fg.isNotEmpty() && fg != packageName && fg != lastBlockedPkg) {
                lastBlockedPkg = fg
                if (!GuardAccessibilityService.goHome()) goHomeFallback()
                // 与无障碍即时拦截共用去重键，保证一次拦截只提示一次
                val ev = if (primary.kind == LockKind.WHITELIST) {
                    KidPrompt.Event.BLOCK_WHITELIST
                } else {
                    KidPrompt.Event.BLOCK_APP_LIMIT
                }
                KidPrompt.fire(this, cfg, ev, "block:" + primary.kind.name + ":" + fg)
            }
            if (appOnly) {
                overlay.hide()
            } else {
                overlay.show(buildLockUi(primary, eval, snap, now))
            }

            // 无悬浮窗权限时，仍周期性把设备送回桌面，保证限制不失效
            if (!Permissions.overlay(this) && now - lastHomeAttemptMs > 5_000L) {
                lastHomeAttemptMs = now
                if (!GuardAccessibilityService.goHome()) goHomeFallback()
            }
        } else {
            overlay.hide()
            lastBlockedPkg = ""
        }
        lastLocked = eval.locked

        publishStatus(now, cfg, snap, eval, fg)
    }

    /** @brief 限制类型对应的儿童提示事件 */
    private fun lockEvent(kind: LockKind): KidPrompt.Event = when (kind) {
        LockKind.DAILY_TOTAL -> KidPrompt.Event.DAILY_TOTAL
        LockKind.REST -> KidPrompt.Event.RESTING
        LockKind.WHITELIST -> KidPrompt.Event.BLOCK_WHITELIST
        LockKind.APP_LIMIT -> KidPrompt.Event.BLOCK_APP_LIMIT
        else -> KidPrompt.Event.WARNING
    }

    private fun playWarnings(cfg: RulesConfig, snap: UsageSnapshot) {
        if (!cfg.soundEnabled || !cfg.masterEnabled) return
        if (cfg.dailyTotal.enabled) {
            val remain = cfg.dailyTotal.limitMinutes.toLong() * RuleEngine.MINUTE_MS - snap.totalMs
            if (remain in 1..60_000L && !UsageRepository.isWarnedDaily(this)) {
                KidPrompt.fire(this, cfg, KidPrompt.Event.WARNING, "warn")
                UsageRepository.setWarnedDaily(this)
            }
        }
        if (cfg.session.enabled) {
            val remain = cfg.session.continuousMinutes.toLong() * RuleEngine.MINUTE_MS - snap.continuousMs
            if (remain in 1..60_000L && !UsageRepository.isWarnedSession(this)) {
                KidPrompt.fire(this, cfg, KidPrompt.Event.WARNING, "warn")
                UsageRepository.setWarnedSession(this)
            }
        }
    }

    private fun buildLockUi(
        primary: com.tensun.parentalguard.core.LockDecision,
        eval: com.tensun.parentalguard.core.Evaluation,
        snap: UsageSnapshot,
        now: Long
    ): LockUi {
        val unlockAt = primary.unlockAtMs
        val countdown = if (unlockAt > now) Formatter.hms(unlockAt - now) else "切换应用后解除"
        val extra = buildString {
            append(primary.hint)
            if (eval.decisions.size > 1) {
                append("\n\n同时生效的限制：")
                for (d in eval.decisions) append("\n• ").append(d.reason)
            }
            append("\n\n今日已使用 ").append(Formatter.human(snap.totalMs))
        }
        val restRemain = (snap.restUntilMs - now).coerceAtLeast(0L)
        val progress = if (restTotalMs > 0L && snap.restUntilMs > now) {
            ((restRemain * 100L) / restTotalMs).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val cue = KidPrompt.cueOf(lockEvent(primary.kind))
        return LockUi(
            title = primary.title,
            kidTitle = cue.kidTitle,
            iconRes = cue.iconRes,
            accentColor = cue.color,
            reasons = eval.reasons,
            countdown = countdown,
            countdownLabel = if (primary.scope == LockScope.FULL_DEVICE) {
                "还要等多久"
            } else {
                "换一个能玩的应用吧"
            },
            extra = extra,
            progress = progress,
            showHome = primary.scope == LockScope.CURRENT_APP,
            showCountdown = unlockAt > now
        )
    }

    /**
     * @brief 解析当前前台状态，供计时与规则判定共用
     *
     * 明确停留在桌面时直接返回桌面包名（不设过期），由 sync 判为非计时状态；
     * 无障碍信号过期后用 UsageEvents 前后台事件序列判断「已离开 / 已进入 / 无新事件」；
     * 只有「无任何新事件且上次是普通应用」才保持原状态继续计时，避免静止使用丢时长。
     */
    private fun currentForeground(now: Long): String {
        // 家长正在操作本应用时优先采信这个信号：
        // 无障碍未上报 / 使用统计滞后时，取到的可能是上一个应用的包名，
        // 一旦按白名单拦截就会把家长从设置页踢出去（历史 bug）
        if (StateBus.selfActivityVisible) return packageName
        if (ForegroundTracker.atHome) return ForegroundTracker.currentPackage
        val tracked = ForegroundTracker.currentPackage
        val fresh = tracked.isNotEmpty() && StateBus.accessibilityConnected &&
                now - ForegroundTracker.lastUpdateMs < 10_000L
        if (fresh) return tracked
        val state = UsageAccess.recentForegroundState(this, now)
        if (state.known) return state.pkg
        return if (tracked.isNotEmpty() && !UsageAccess.isSystemSurface(this, tracked)) tracked else ""
    }

    private fun goHomeFallback() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // 后台启动受限时由遮罩的「返回桌面」按钮兜底
        }
    }

    private fun publishStatus(
        now: Long,
        cfg: RulesConfig,
        snap: UsageSnapshot,
        eval: com.tensun.parentalguard.core.Evaluation,
        fg: String
    ) {
        val appRemaining = cfg.appLimits.filter { it.enabled }.map { r ->
            val used = snap.perAppMs[r.packageName] ?: 0L
            val label = if (r.appLabel.isEmpty()) UsageAccess.appLabel(this, r.packageName) else r.appLabel
            label to (r.dailyMinutes.toLong() * RuleEngine.MINUTE_MS - used).coerceAtLeast(0L)
        }
        val status = UiStatus(
            now = now,
            masterEnabled = cfg.masterEnabled,
            serviceRunning = true,
            accessibilityConnected = StateBus.accessibilityConnected,
            dailyEnabled = cfg.dailyTotal.enabled,
            dailyLimitMs = cfg.dailyTotal.limitMinutes.toLong() * RuleEngine.MINUTE_MS,
            dailyUsedMs = snap.totalMs,
            sessionEnabled = cfg.session.enabled,
            sessionLimitMs = cfg.session.continuousMinutes.toLong() * RuleEngine.MINUTE_MS,
            continuousUsedMs = snap.continuousMs,
            restRemainingMs = (snap.restUntilMs - now).coerceAtLeast(0L),
            restTotalMs = restTotalMs,
            locked = eval.locked,
            lockTitle = eval.primary?.title.orEmpty(),
            lockReasons = eval.reasons,
            foregroundLabel = if (fg.isEmpty()) "未知" else UsageAccess.appLabel(this, fg),
            appRemaining = appRemaining,
            overlayError = StateBus.overlayError
        )
        StateBus.status = status
        // 两项数值精确到秒，通知必须每秒刷新；updateNotification 内部按内容去重，
        // 避免窗口事件触发的即时心跳在同一秒内重复 notify
        updateNotification(status, cfg)
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "管控状态", NotificationManager.IMPORTANCE_LOW)
        ch.description = "显示两项精确到时 / 分 / 秒的剩余时长、休息倒计时与锁定原因"
        ch.setSound(null, null)
        nm.createNotificationChannel(ch)
    }

    /**
     * @brief 构建常驻通知
     *
     * 折叠状态两行分别显示「可用时长」与「单次连续剩余」两项秒级数值，
     * 下拉展开后用 BigTextStyle 同时展示两项数值及已用、状态、休息等信息，
     * 无需打开应用即可查看；setOnlyAlertOnce 保证每秒刷新不产生提示音或振动。
     */
    private fun buildNotification(title: String, text: String, bigText: String = ""): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (bigText.isNotEmpty()) {
            builder.setStyle(
                Notification.BigTextStyle()
                    .setBigContentTitle("儿童设备管控 · 实时计时")
                    .bigText(bigText)
            )
        }
        return builder.build()
    }

    /** @brief 每秒刷新通知：下拉后折叠与展开都能同时看到两项精确到时 / 分 / 秒的数值 */
    private fun updateNotification(s: UiStatus, cfg: RulesConfig) {
        val daily = if (s.dailyEnabled) Formatter.hmsFull(s.dailyRemainingMs) else "未启用"
        val session = if (s.sessionEnabled) Formatter.hmsFull(s.sessionRemainingMs) else "未启用"
        val title = "可用时长 $daily"
        val text = "单次连续剩余 $session"
        val stateText = when {
            !cfg.masterEnabled -> "管控总开关已关闭"
            s.locked -> "已锁定 · ${s.lockTitle}"
            else -> "设备可用"
        }
        val nl = System.lineSeparator()
        val lines = ArrayList<String>()
        lines.add("可用时长：$daily")
        lines.add("单次使用连续剩余时间：$session")
        lines.add("今日已用：${Formatter.hmsFull(s.dailyUsedMs)}")
        lines.add("本次已连续：${Formatter.hmsFull(s.continuousUsedMs)}")
        lines.add("状态：$stateText")
        if (s.locked && s.lockReasons.isNotEmpty()) {
            lines.add("限制原因：" + s.lockReasons.joinToString("；"))
        }
        if (s.restRemainingMs > 0L) {
            lines.add("休息倒计时：" + Formatter.hmsFull(s.restRemainingMs))
        }
        lines.add("当前应用：" + s.foregroundLabel)
        val bigText = lines.joinToString(nl)

        // 同一秒内窗口事件触发的即时心跳不重复刷新通知
        val key = "$title|$text|$bigText"
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(title, text, bigText))
        } catch (e: Exception) {
            Log.w(TAG, "更新通知失败: ${e.message}")
        }
    }
}
