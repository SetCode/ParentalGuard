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
 * 计费模型：以「前台片段」为单位累加，片段起点与当前包名持久化保存。
 * 计时范围严格限定为「可计时应用处于前台」：
 * - 退出应用回到桌面、切到别的应用、应用退到后台、锁屏/灭屏、被管控锁定
 *   都会立即结算当前片段并把 segmentStartMs 清零（暂停）；
 * - 只有对应应用重新进入前台才从 now 开启新片段，累计到已有的 perAppUsedMs 上，
 *   即「从暂停处继续」，不重复计时也不清零已有数据；
 * - 桌面 / 系统界面 / 未知前台本身不产生任何片段，不计入设备总时长与连续时长。
 * 每次应用切换 / 窗口事件 / 屏幕熄灭 / 每 5 秒结算一次；跨天清零每日计数；
 * 重启不清空计数并与系统使用情况数据对账，防止通过重启规避限制。
 */
object UsageRepository {
    private const val PREF = "pg_usage"
    private const val KEY = "daily_json"
    private const val COMMIT_INTERVAL_MS = 5_000L
    private const val IDLE_RESET_MS = 5 * 60 * 1000L
    /**
     * 单次片段结算上限：放宽到 5 分钟，避免后台服务被国产 ROM 短暂冻结后丢失使用时长。
     * 关机 / 熄屏由 onScreenOff 立即结算、onBoot 丢弃未结算片段并对账，不会把停机时间算成使用。
     */
    private const val MAX_DELTA_MS = 5 * 60_000L

    private val lockObj = Any()

    @Volatile
    private var cached: DailyUsage? = null

    /** 当前监控对象分区：默认对象沿用原来的 pg_usage，保证已有统计数据不受影响 */
    @Volatile
    private var scope: String = ""

    /**
     * 局域网内所有设备汇总后的计时数据提供者，由 ShareSync 注册。
     * 只叠加到规则判定读到的快照上，不写回本地计数，
     * 因此「本设备分量」永远只含自己产生的使用时长，不会互相累加、不会回退。
     */
    @Volatile
    var globalUsageProvider: (() -> UsageSnapshot?)? = null

    private fun prefName(): String =
        if (scope.isEmpty() || scope == "default") PREF else "${PREF}_$scope"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(prefName(), Context.MODE_PRIVATE)

    private fun load(ctx: Context): DailyUsage {
        cached?.let { return it }
        val sp = sp(ctx)
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
        sp(ctx).edit().putString(KEY, s.toJson().toString()).apply()
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

    /**
     * @brief 时间重置：把当前监控对象的全部计时值恢复到项目预设的初始状态
     *
     * 覆盖的常见情况：计时进行中（丢弃未结算片段）、已暂停（丢弃暂停锚点）、
     * 强制休息中（清除休息截止）、已结束（清除每日 / 单应用累计与锁定锚点）、
     * 以及日期异常、时间戳回拨、持久化数据损坏等异常状态。
     * 直接以全新初始对象覆盖缓存并立即落盘，因此重复重置保持幂等；
     * 只写 pg_usage 分区，不触碰 pg_config、共享配置与任何其他业务数据。
     */
    fun resetAll(ctx: Context, now: Long) = synchronized(lockObj) {
        val s = load(ctx)
        s.resetToInitial(now)
        // 以重置时刻作为片段结算锚点，避免第一次心跳把 0 误判为「长时间未提交」
        s.lastCommitMs = now
        persist(ctx, s)
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

    /** @brief 计时范围：仅可计时应用处于前台才累计，桌面/系统界面/后台/未知前台一律暂停 */
    private fun countable(ctx: Context, pkg: String): Boolean =
        UsageAccess.isCountableForeground(ctx, pkg)

    /** @brief 立即暂停：结算当前片段并清零起点，供窗口切换事件调用（不等下一次心跳） */
    fun pause(ctx: Context, now: Long) = synchronized(lockObj) {
        val s = load(ctx)
        ensureDate(s, now)
        if (s.segmentStartMs > 0L) {
            closeSegment(s, now)
            persist(ctx, s)
        }
    }

    /**
     * @brief 心跳 / 窗口切换调用：结算片段、处理应用切换、周期落盘
     *
     * active=false（桌面、系统界面、后台、灭屏、锁定、未知前台）时立即结算并暂停；
     * active=true 且包名变化时先结算旧片段、再从 now 开新片段，切换边界不重叠、不重复计时；
     * 包名不变且仍在应用内静止使用时继续累计，不因缺少窗口事件而丢失时长。
     */
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

    /** @brief 本地用量快照（含未落盘片段增量，不含远端叠加），供共享层上报本设备分量 */
    fun localSnapshot(ctx: Context, now: Long): UsageSnapshot = synchronized(lockObj) {
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

    /**
     * @brief 规则判定与界面展示使用的快照：本地数据与局域网全局数据逐字段取较大值
     *
     * 全局值来自各设备分量之和，只做「取较大」叠加，不写回本地计数，
     * 因此不会把远端增量重复记进本设备分量，也不会让已有计数回退。
     */
    fun snapshot(ctx: Context, now: Long): UsageSnapshot {
        // 先取本地快照并释放仓库锁，再调用共享层，避免与本仓库锁形成反向加锁死锁
        val local = localSnapshot(ctx, now)
        val global = try {
            globalUsageProvider?.invoke()
        } catch (e: Exception) {
            null
        }
        if (global == null) return local
        val perApp = HashMap(local.perAppMs)
        for ((pkg, ms) in global.perAppMs) {
            if (ms > (perApp[pkg] ?: 0L)) perApp[pkg] = ms
        }
        return UsageSnapshot(
            date = local.date,
            totalMs = maxOf(local.totalMs, global.totalMs),
            perAppMs = perApp,
            continuousMs = maxOf(local.continuousMs, global.continuousMs),
            restUntilMs = local.restUntilMs
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

    /** @brief 切换监控对象分区：各对象计时状态互不串扰，切换不改动其他对象数据 */
    fun switchScope(ctx: Context, id: String, usageJson: String) = synchronized(lockObj) {
        val target = if (id == "default") "" else id
        if (scope == target) return@synchronized
        scope = target
        cached = null
        val prefs = sp(ctx)
        if (prefs.getString(KEY, null).isNullOrBlank()) {
            val s = if (usageJson.isNotEmpty()) {
                try {
                    DailyUsage.fromJson(JSONObject(usageJson))
                } catch (e: Exception) {
                    DailyUsage(date = DailyUsage.dateString(System.currentTimeMillis()))
                }
            } else {
                DailyUsage(date = DailyUsage.dateString(System.currentTimeMillis()))
            }
            prefs.edit().putString(KEY, s.toJson().toString()).apply()
        }
        cached = null
    }
}
