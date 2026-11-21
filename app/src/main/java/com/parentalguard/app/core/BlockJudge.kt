package com.parentalguard.app.core

import android.content.Context
import com.parentalguard.app.data.UsageRepository
import com.parentalguard.app.model.RulesConfig

/**
 * @brief 窗口切换事件的即时拦截判定
 *
 * 与心跳中的规则引擎共用同一份配置与用量数据，但只负责「应用级」限制
 * （白名单、单应用每日限额），以便在应用打开的瞬间就返回桌面，
 * 不必等到下一次 1 秒心跳。全设备级限制由全屏遮罩统一处理。
 */
object BlockJudge {

    data class Hit(val kind: LockKind, val label: String)

    /** @brief 返回需要拦截的原因；null 表示允许打开 */
    fun check(ctx: Context, cfg: RulesConfig, pkg: String, now: Long): Hit? {
        if (!cfg.masterEnabled) return null
        if (pkg.isEmpty() || pkg == ctx.packageName) return null
        if (UsageAccess.isIgnoredPackage(pkg)) return null
        if (UsageAccess.alwaysAllowed(ctx).contains(pkg)) return null

        // 时间段管控属于全设备限制，由全屏遮罩统一处理，这里不重复拦截
        if (TimeWindow.activeSlot(cfg.timeWindow, now) != null) return null

        val snap = UsageRepository.snapshot(ctx, now)
        // 全设备限制已生效时交给全屏遮罩，避免发出「应用不允许」这类误导性提示
        if (snap.restUntilMs > now) return null
        if (cfg.dailyTotal.enabled &&
            snap.totalMs >= cfg.dailyTotal.limitMinutes.toLong() * RuleEngine.MINUTE_MS
        ) return null

        val label = UsageAccess.appLabel(ctx, pkg)
        if (cfg.whitelist.enabled && !cfg.whitelist.packages.contains(pkg)) {
            return Hit(LockKind.WHITELIST, label)
        }
        for (r in cfg.appLimits) {
            if (!cfg.countsUsage(pkg)) break
            if (!r.enabled || r.packageName != pkg) continue
            val limitMs = r.dailyMinutes.toLong() * RuleEngine.MINUTE_MS
            if ((snap.perAppMs[pkg] ?: 0L) >= limitMs) {
                return Hit(LockKind.APP_LIMIT, label)
            }
        }
        return null
    }
}
