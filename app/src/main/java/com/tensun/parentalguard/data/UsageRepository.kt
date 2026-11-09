package com.tensun.parentalguard.data

import android.content.Context
import com.tensun.parentalguard.core.Permissions
import com.tensun.parentalguard.core.UsageAccess
import com.tensun.parentalguard.model.DailyUsage
import com.tensun.parentalguard.model.UsageSnapshot
import org.json.JSONObject

/**
 * @brief 当日用量计费仓库
 *
 * 计费模型：以「前台片段」为单位累加。片段起点与当前包名持久化保存，
 * 每次应用切换 / 屏幕熄灭 / 每 5 秒结算一次，因此杀进程或重启最多丢失 5 秒。
 * 锁屏 / 休息 / 灭屏期间不计时；跨天清空每日计数；重启不清空计数，防止通过重启规避限制。
 */
object UsageRepository {
    private const val PREF = "pg_usage"
    private const val KEY = "daily_json"
    private const val COMMIT_INTERVAL_MS = 5_000L
    private const val IDLE_RESET_MS = 5 * 60 * 1000L
    private const val MAX_DELTA_MS = 60_000L

    private val lockObj = Any()

    @Volatile
    private var cached: DailyUsage? = null

    private fun load(ctx: Context): DailyUsage {
        cached?.let { return it }
        val sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null)
        val s = if (raw.isNullOrBlank()) {
            DailyUsage(date = DailyUsage.dateString(System.currentTimeMillis()))
        } else {
            try {
                DailyUsage.fromJson(JSONObject(raw))
            } catch (e: Exception) {
                DailyUsage(date = DailyUsage.dateString(System.currentTimeMillis()))
            }
        }
        cached = s
        return s
    }

    private fun persist(ctx: Context, s: DailyUsage) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, s.toJson().toString())
            .apply()
    }

    /** @brief 日期切换：清零每日计数；未结束的强制休息继续保留 */
    private fun ensureDate(s: DailyUsage, now: Long): Boolean {
        val today = DailyUsage.dateString(now)
        if (s.date == today) return false
        val restUntil = s.restUntilMs
        s.resetFor(today)
        s.restUntilMs = if (restUntil > now) restUntil else 0L
        return true
    }

    fun ensureToday(ctx: Context, now: Long) = synchronized(lockObj) {
        val s = load(ctx)
        if (ensureDate(s, now)) persist(ctx, s)
    }

    /** @brief 开机 / 服务重建：恢复同一天的计数并与系统使用情况数据对账 */
    fun onBoot(ctx: Context, now: Long) = synchronized(lockObj) {
        val s = load(ctx)
        ensureDate(s, now)
        // 断电期间不计时：直接丢弃未结算片段，避免把关机时长算成使用时长
        s.segmentStartMs = 0L
        s.segmentPackage = ""
        s.screenOffAtMs = 0L
        s.lastCommitMs = now

        // 以系统统计数据补全服务被杀期间产生的用量（取较大值，遵循更严格原则）
        if (Permissions.usageAccess(ctx)) {
            try {
                val stats = UsageAccess.todayForeground(ctx, now)
                var sum = 0L
                for ((pkg, ms) in stats) {
                    if (pkg == ctx.packageName || pkg == "android" || pkg.startsWith("com.android.systemui")) continue
                    if (ms > (s.perAppUsedMs[pkg] ?: 0L)) s.perAppUsedMs[pkg] = ms
                    sum += ms
                }
                if (sum > s.totalUsedMs) s.totalUsedMs = sum
            } catch (e: Exception) {
                // 无使用情况权限或厂商实现异常时保留本地计数
            }
        }
        persist(ctx, s)
    }

    private fun pendingDelta(s: DailyUsage, now: Long): Long =
        if (s.segmentStartMs > 0L) (now - s.segmentStartMs).coerceIn(0L, MAX_DELTA_MS) else 0L

    private fun closeSegment(s: DailyUsage, now: Long) {
        if (s.segmentStartMs > 0L) {
            val delta = (now - s.segmentStartMs).coerceIn(0L, MAX_DELTA_MS)
            if (delta > 0L) {
                s.totalUsedMs += delta
                s.continuousUsedMs += delta
                val pkg = s.segmentPackage
                if (pkg.isNotEmpty()) {
                    s.perAppUsedMs[pkg] = (s.perAppUsedMs[pkg] ?: 0L) + delta
                }
            }
        }
        s.segmentStartMs = 0L
        s.segmentPackage = ""
        s.lastCommitMs = now
    }

    private fun startSegment(s: DailyUsage, now: Long, pkg: String) {
        s.segmentStartMs = now
        s.segmentPackage = pkg
    }

    private fun countable(ctx: Context, pkg: String): Boolean =
        pkg.isNotEmpty() && pkg != ctx.packageName && pkg != "android" &&
                !pkg.startsWith("com.android.systemui")

    /** @brief 心跳调用：结算片段、处理应用切换、周期落盘 */
    fun sync(ctx: Context, now: Long, foregroundPkg: String, screenOn: Boolean, trackingAllowed: Boolean) =
        synchronized(lockObj) {
            val s = load(ctx)
            ensureDate(s, now)
            val active = screenOn && trackingAllowed && countable(ctx, foregroundPkg)
            if (!active) {
                if (s.segmentStartMs > 0L) closeSegment(s, now)
            } else {
                when {
                    s.segmentStartMs == 0L -> startSegment(s, now, foregroundPkg)
                    s.segmentPackage != foregroundPkg -> {
                        closeSegment(s, now)
                        startSegment(s, now, foregroundPkg)
                    }
                    now - s.lastCommitMs >= COMMIT_INTERVAL_MS -> {
                        closeSegment(s, now)
                        startSegment(s, now, foregroundPkg)
                    }
                }
            }
            persist(ctx, s)
        }

    /** @brief 当前用量快照（含未落盘的片段增量） */
    fun snapshot(ctx: Context, now: Long): UsageSnapshot = synchronized(lockObj) {
        val s = load(ctx)
        ensureDate(s, now)
        val delta = pendingDelta(s, now)
        val perApp = HashMap(s.perAppUsedMs)
        if (delta > 0L && s.segmentPackage.isNotEmpty()) {
            val pkg = s.segmentPackage
            perApp[pkg] = (perApp[pkg] ?: 0L) + delta
        }
        UsageSnapshot(
            date = s.date,
            totalMs = s.totalUsedMs + delta,
            perAppMs = perApp,
            continuousMs = s.continuousUsedMs + delta,
            restUntilMs = s.restUntilMs
        )
    }

    fun beginRest(ctx: Context, now: Long, restMs: Long) = synchronized(lockObj) {
        val s = load(ctx)
        closeSegment(s, now)
        s.restUntilMs = now + restMs
        s.continuousUsedMs = 0L
        s.warnedSession = false
        s.restFinishAnnounced = false
        persist(ctx, s)
    }

    /** @brief 休息结束：清零连续计数，由调用方决定是否播放提示音 */
    fun finishRestIfNeeded(ctx: Context, now: Long): Boolean = synchronized(lockObj) {
        val s = load(ctx)
        if (s.restUntilMs > 0L && now >= s.restUntilMs) {
            s.restUntilMs = 0L
            s.continuousUsedMs = 0L
            s.warnedSession = false
            s.restFinishAnnounced = true
            closeSegment(s, now)
            persist(ctx, s)
            true
        } else {
            false
        }
    }

    fun onScreenOff(ctx: Context, now: Long) = synchronized(lockObj) {
        val s = load(ctx)
        closeSegment(s, now)
        s.screenOffAtMs = now
        persist(ctx, s)
    }

    /** @brief 屏幕重新点亮：熄屏超过 5 分钟视为一次新的连续使用 */
    fun onScreenOn(ctx: Context, now: Long) = synchronized(lockObj) {
        val s = load(ctx)
        val off = s.screenOffAtMs
        if (off > 0L && now - off >= IDLE_RESET_MS) {
            s.continuousUsedMs = 0L
            s.warnedSession = false
        }
        s.screenOffAtMs = 0L
        persist(ctx, s)
    }

    fun resetContinuous(ctx: Context, now: Long) = synchronized(lockObj) {
        val s = load(ctx)
        closeSegment(s, now)
        s.continuousUsedMs = 0L
        s.warnedSession = false
        persist(ctx, s)
    }

    fun setWarnedDaily(ctx: Context) = synchronized(lockObj) {
        val s = load(ctx); s.warnedDaily = true; persist(ctx, s)
    }

    fun setWarnedSession(ctx: Context) = synchronized(lockObj) {
        val s = load(ctx); s.warnedSession = true; persist(ctx, s)
    }

    fun isWarnedDaily(ctx: Context): Boolean = synchronized(lockObj) { load(ctx).warnedDaily }
    fun isWarnedSession(ctx: Context): Boolean = synchronized(lockObj) { load(ctx).warnedSession }
}
