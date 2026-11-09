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
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private lateinit var overlay: LockOverlay
    private var lastNotificationMs = 0L
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
        startForeground(NOTIF_ID, buildNotification("管控服务启动中…"))
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
        StateBus.serviceRunning = false
        super.onDestroy()
    }

    private fun startTicking() {
        ticking = true
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
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
        // 前台应用暂时识别不到时仍按屏幕使用计入设备总时长，保证每日/连续规则不失效。
        val accountingPkg = if (fg.isEmpty()) "__unknown_foreground__" else fg
        UsageRepository.sync(this, now, accountingPkg, screenOn, !eval.locked)
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

            if (appOnly && fg.isNotEmpty() && fg != lastBlockedPkg) {
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

    private fun currentForeground(now: Long): String {
        val tracked = ForegroundTracker.currentPackage
        val fresh = tracked.isNotEmpty() && now - ForegroundTracker.lastUpdateMs < 10_000L
        if (fresh && StateBus.accessibilityConnected) return tracked
        val fallback = UsageAccess.recentForeground(this, now)
        return if (fallback.isNotEmpty()) fallback else tracked
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
        if (now - lastNotificationMs >= 5_000L) {
            lastNotificationMs = now
            updateNotification(status, cfg)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "管控状态", NotificationManager.IMPORTANCE_LOW)
        ch.description = "显示剩余时长、休息倒计时与锁定原因"
        ch.setSound(null, null)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("儿童设备管控")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(s: UiStatus, cfg: RulesConfig) {
        val text = when {
            !cfg.masterEnabled -> "管控总开关已关闭"
            s.locked -> "${s.lockTitle}｜${s.lockReasons.firstOrNull().orEmpty()}"
            s.restRemainingMs > 0L -> "休息倒计时 ${Formatter.hms(s.restRemainingMs)}"
            s.dailyEnabled -> "今日剩余 ${Formatter.human(s.dailyRemainingMs)}"
            else -> "管控运行中"
        }
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "更新通知失败: ${e.message}")
        }
    }
}
