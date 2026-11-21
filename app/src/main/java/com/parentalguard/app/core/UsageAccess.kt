package com.parentalguard.app.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.util.Calendar

/** @brief 可启动应用条目 */
data class AppEntry(val pkg: String, val label: String, val icon: Drawable?)

/**
 * @brief 由系统使用情况事件推导出的前台状态
 * @param pkg 当前前台包名；空串表示「明确已离开应用」（桌面 / 后台 / 锁屏）
 * @param known false 表示近期没有可用事件，无法判断，调用方应保持上一次已知状态
 */
data class RecentForeground(val pkg: String, val known: Boolean)

/** @brief 系统使用情况访问 / 应用列表读取封装 */
object UsageAccess {

    private val IGNORED = setOf(
        "android", "com.android.systemui", "com.android.systemui.plugin",
        "com.android.wallpaper", "com.android.launcher"
    )

    /** 常见厂商桌面包名：部分 ROM 不通过 CATEGORY_HOME 暴露，需静态兜底 */
    private val HOME_PKGS = setOf(
        "com.android.launcher", "com.android.launcher3", "com.google.android.apps.nexuslauncher",
        "com.miui.home", "com.miui.home.launcher", "com.huawei.android.launcher",
        "com.hihonor.android.launcher", "com.oppo.launcher", "com.coloros.launcher",
        "com.vivo.launcher", "com.bbk.launcher2", "com.sec.android.app.launcher",
        "com.android.systemui", "com.lenovo.launcher", "com.meizu.flyme.launcher"
    )

    /** 系统界面 / 权限弹窗等，不属于「应用使用」 */
    private val SYSTEM_SURFACES = setOf(
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.wallpaper", "com.android.systemui.plugin", "com.android.intentresolver"
    )

    private const val EVENT_WINDOW_MS = 60_000L

    fun isIgnoredPackage(pkg: String): Boolean = pkg.isEmpty() || IGNORED.contains(pkg)

    /** @brief 列举设备上所有可从桌面启动的应用（排除本应用自身） */
    fun installedApps(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            pm.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            emptyList()
        }
        val list = ArrayList<AppEntry>()
        val seen = HashSet<String>()
        for (r in resolved) {
            val pkg = r.activityInfo?.packageName ?: continue
            if (pkg == ctx.packageName || !seen.add(pkg)) continue
            val label = try { r.loadLabel(pm).toString() } catch (e: Exception) { pkg }
            val icon = try { r.loadIcon(pm) } catch (e: Exception) { null }
            list.add(AppEntry(pkg, label, icon))
        }
        list.sortBy { it.label }
        return list
    }

    /** @brief 系统默认桌面包名集合（白名单自动放行，否则儿童无法回到桌面） */
    fun defaultLaunchers(ctx: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return try {
            ctx.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    @Volatile
    private var cachedAllowed: Set<String>? = null

    @Volatile
    private var cachedAllowedFor: String = ""

    /** @brief 白名单判定中始终放行的组件；结果缓存，避免 1Hz 心跳反复查询桌面 */
    fun alwaysAllowed(ctx: Context): Set<String> {
        val cached = cachedAllowed
        if (cached != null && cachedAllowedFor == ctx.packageName) return cached
        val s = HashSet<String>()
        s.add(ctx.packageName)
        s.addAll(defaultLaunchers(ctx))
        s.addAll(HOME_PKGS)
        s.add("android")
        s.add("com.android.systemui")
        cachedAllowedFor = ctx.packageName
        cachedAllowed = s
        return s
    }

    /** @brief 是否为系统桌面（默认桌面或常见厂商桌面） */
    fun isHomePackage(ctx: Context, pkg: String): Boolean =
        HOME_PKGS.contains(pkg) || defaultLaunchers(ctx).contains(pkg)

    /**
     * @brief 是否为桌面 / 系统界面等「非应用使用」状态
     *
     * 这些界面的前台停留不应计入任何应用的时长，也不应计入设备使用时长。
     */
    fun isSystemSurface(ctx: Context, pkg: String): Boolean {
        if (pkg.isEmpty() || pkg == ctx.packageName) return true
        if (isIgnoredPackage(pkg)) return true
        if (pkg.startsWith("com.android.systemui")) return true
        if (SYSTEM_SURFACES.contains(pkg)) return true
        if (HOME_PKGS.contains(pkg)) return true
        return alwaysAllowed(ctx).contains(pkg)
    }

    /**
     * @brief 计时范围判定：只有「用户可启动的第三方/系统应用真正在前台」才允许累计时长
     *
     * 桌面、系统界面、权限弹窗、本应用家长界面、后台与未知前台一律返回 false。
     */
    fun isCountableForeground(ctx: Context, pkg: String): Boolean = !isSystemSurface(ctx, pkg)

    fun appLabel(ctx: Context, pkg: String): String = try {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        ctx.packageManager.getApplicationLabel(ai).toString()
    } catch (e: Exception) {
        pkg
    }

    fun appIcon(ctx: Context, pkg: String): Drawable? = try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) {
        null
    }

    /** @brief 读取系统记录的当日各应用前台时长，用于重启后对账（剔除桌面/系统界面） */
    fun todayForeground(ctx: Context, now: Long): Map<String, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyMap()
        val stats = try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
        } catch (e: Exception) {
            null
        } ?: return emptyMap()
        val map = HashMap<String, Long>()
        for (s in stats) {
            val t = s.totalTimeInForeground
            if (t <= 0L) continue
            val pkg = s.packageName
            // 桌面 / 系统界面的停留不属于应用使用，对账时一并排除
            if (isSystemSurface(ctx, pkg)) continue
            map[pkg] = (map[pkg] ?: 0L) + t
        }
        return map
    }

    /**
     * @brief 无障碍服务不可用时的降级方案：按 UsageEvents 前后台事件序列推导当前前台包
     *
     * 与旧实现的区别：旧实现只取「最近一次进入前台」的包名，应用退到桌面/后台后
     * 仍会被误认为在前台；现在同时处理 MOVE_TO_BACKGROUND / ACTIVITY_PAUSED /
     * ACTIVITY_STOPPED，能明确区分「离开应用」与「仍在应用内静止使用」：
     * - 明确在桌面 → 返回桌面包名（调用方按非计时状态处理）
     * - 明确退到后台 / 锁屏 → 返回 known=true, pkg=""
     * - 近期无任何事件（例如看视频静置）→ known=false，调用方保持上次已知状态继续计时
     */
    fun recentForegroundState(ctx: Context, now: Long): RecentForeground {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return RecentForeground("", false)
        return try {
            val events = usm.queryEvents(now - EVENT_WINDOW_MS, now)
                ?: return RecentForeground("", false)
            val e = UsageEvents.Event()
            var current = ""
            var sawSwitch = false
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val pkg = e.packageName?.toString().orEmpty()
                if (pkg.isEmpty()) continue
                val type = e.eventType
                val entered = type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        (Build.VERSION.SDK_INT >= 29 && type == UsageEvents.Event.ACTIVITY_RESUMED)
                val left = type == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                        (Build.VERSION.SDK_INT >= 29 &&
                                (type == UsageEvents.Event.ACTIVITY_PAUSED ||
                                        type == UsageEvents.Event.ACTIVITY_STOPPED))
                when {
                    entered -> {
                        current = pkg
                        sawSwitch = true
                    }
                    left -> {
                        // 旧应用的 PAUSED 不应清掉刚进入前台的新应用
                        if (current.isEmpty() || pkg == current) current = ""
                        sawSwitch = true
                    }
                }
            }
            if (sawSwitch) RecentForeground(current, true) else RecentForeground("", false)
        } catch (e: Exception) {
            RecentForeground("", false)
        }
    }

    /** @brief 兼容包装：仅返回推导出的包名 */
    fun recentForeground(ctx: Context, now: Long): String = recentForegroundState(ctx, now).pkg
}
