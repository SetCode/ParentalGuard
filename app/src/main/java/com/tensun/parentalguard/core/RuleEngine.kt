package com.tensun.parentalguard.core

import android.content.Context
import com.tensun.parentalguard.model.RulesConfig
import com.tensun.parentalguard.model.UsageSnapshot
import java.util.Calendar

enum class LockKind { NONE, DAILY_TOTAL, REST, WHITELIST, APP_LIMIT }

/** @brief 限制作用域；值越大越严格 */
enum class LockScope { NONE, CURRENT_APP, FULL_DEVICE }

data class LockDecision(
    val kind: LockKind,
    val scope: LockScope,
    /** 自动解除时间戳；0 表示「直到切换应用为止」 */
    val unlockAtMs: Long,
    val title: String,
    val reason: String,
    val hint: String
)

/** @brief 所有命中的规则 + 按更严格原则选出的主限制 */
data class Evaluation(
    val decisions: List<LockDecision>,
    val primary: LockDecision?
) {
    val locked: Boolean get() = primary != null
    val reasons: List<String> get() = decisions.map { it.reason }
}

/**
 * @brief 规则叠加判定引擎
 *
 * 同一时刻可能同时命中多条规则：先全部收集，再按「作用域更大者优先，
 * 同作用域下解除时间更晚者优先」选出主限制；界面同时展示全部命中原因。
 */
object RuleEngine {
    const val MINUTE_MS = 60_000L

    /** @brief 次日 00:00（本地时间）时间戳 */
    fun nextMidnight(now: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun stricter(a: LockDecision?, b: LockDecision): LockDecision {
        if (a == null) return b
        if (a.scope.ordinal != b.scope.ordinal) {
            return if (a.scope.ordinal > b.scope.ordinal) a else b
        }
        val ua = if (a.unlockAtMs > 0L) a.unlockAtMs else Long.MIN_VALUE
        val ub = if (b.unlockAtMs > 0L) b.unlockAtMs else Long.MIN_VALUE
        return if (ua >= ub) a else b
    }

    fun evaluate(
        ctx: Context,
        cfg: RulesConfig,
        snap: UsageSnapshot,
        foreground: String,
        now: Long
    ): Evaluation {
        if (!cfg.masterEnabled) return Evaluation(emptyList(), null)

        // 家长设置界面保持可操作（其内部有密码门），避免锁定后无法改配置；
        // 儿童停留在密码界面时设备同样不可用。
        if (foreground == ctx.packageName) return Evaluation(emptyList(), null)

        val list = ArrayList<LockDecision>()
        val midnight = nextMidnight(now)
        val alwaysAllowed = UsageAccess.alwaysAllowed(ctx)

        // 1. 强制休息（全设备）
        if (snap.restUntilMs > now) {
            list.add(
                LockDecision(
                    LockKind.REST, LockScope.FULL_DEVICE, snap.restUntilMs,
                    "强制休息中",
                    "单次连续使用已达上限，正在强制休息",
                    "休息结束后，若其他规则允许将自动恢复使用"
                )
            )
        }

        // 2. 每日设备总时长（全设备，次日零点解除）
        if (cfg.dailyTotal.enabled) {
            val limitMs = cfg.dailyTotal.limitMinutes.toLong() * MINUTE_MS
            if (snap.totalMs >= limitMs) {
                list.add(
                    LockDecision(
                        LockKind.DAILY_TOTAL, LockScope.FULL_DEVICE, midnight,
                        "今日使用时长已用完",
                        "今日设备总使用时长已达到 ${cfg.dailyTotal.limitMinutes} 分钟",
                        "次日 00:00 自动解除限制"
                    )
                )
            }
        }

        // 3 / 4. 应用级规则：白名单、单应用每日限额
        val appCheckable = foreground.isNotEmpty() &&
                !UsageAccess.isIgnoredPackage(foreground) &&
                !alwaysAllowed.contains(foreground)
        if (appCheckable) {
            if (cfg.whitelist.enabled && !cfg.whitelist.packages.contains(foreground)) {
                val label = UsageAccess.appLabel(ctx, foreground)
                list.add(
                    LockDecision(
                        LockKind.WHITELIST, LockScope.CURRENT_APP, 0L,
                        "应用不在白名单",
                        "$label 未被家长允许使用",
                        "请返回桌面，或打开白名单内的应用"
                    )
                )
            }
            for (r in cfg.appLimits) {
                if (!r.enabled || r.packageName != foreground) continue
                val limitMs = r.dailyMinutes.toLong() * MINUTE_MS
                if ((snap.perAppMs[foreground] ?: 0L) >= limitMs) {
                    val label = if (r.appLabel.isEmpty()) {
                        UsageAccess.appLabel(ctx, foreground)
                    } else {
                        r.appLabel
                    }
                    list.add(
                        LockDecision(
                            LockKind.APP_LIMIT, LockScope.CURRENT_APP, midnight,
                            "该应用今日时长已用完",
                            "$label 今日累计使用已达到 ${r.dailyMinutes} 分钟",
                            "次日 00:00 自动恢复该应用"
                        )
                    )
                }
            }
        }

        var primary: LockDecision? = null
        for (d in list) primary = stricter(primary, d)
        return Evaluation(list, primary)
    }
}
