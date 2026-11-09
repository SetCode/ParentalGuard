package com.tensun.parentalguard.core

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

/** @brief 系统使用情况访问 / 应用列表读取封装 */
object UsageAccess {

    private val IGNORED = setOf(
        "android", "com.android.systemui", "com.android.systemui.plugin",
        "com.android.wallpaper", "com.android.launcher"
    )

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
        s.add("android")
        s.add("com.android.systemui")
        cachedAllowedFor = ctx.packageName
        cachedAllowed = s
        return s
    }

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

    /** @brief 读取系统记录的当日各应用前台时长，用于重启后对账 */
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
            if (t > 0) map[s.packageName] = (map[s.packageName] ?: 0L) + t
        }
        return map
    }

    /** @brief 无障碍服务不可用时的降级方案：读取最近一次前台切换事件 */
    fun recentForeground(ctx: Context, now: Long): String {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return ""
        return try {
            val events = usm.queryEvents(now - 30_000L, now) ?: return ""
            val e = UsageEvents.Event()
            var lastPkg = ""
            var lastTs = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val type = e.eventType
                val isForeground = type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        (Build.VERSION.SDK_INT >= 29 && type == UsageEvents.Event.ACTIVITY_RESUMED)
                if (isForeground && e.timeStamp >= lastTs) {
                    lastTs = e.timeStamp
                    lastPkg = e.packageName?.toString().orEmpty()
                }
            }
            if (lastPkg.isNotEmpty() && now - lastTs < 30_000L) lastPkg else ""
        } catch (e: Exception) {
            ""
        }
    }
}
