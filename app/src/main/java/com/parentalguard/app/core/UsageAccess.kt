package com.parentalguard.app.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.inputmethod.InputMethodManager
import java.util.Calendar

/** @brief 可启动应用条目 */
data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    /** 是否系统应用：名单里排在用户应用之后，便于区分 */
    val isSystem: Boolean = false
)

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

    @Volatile
    private var cachedIme: Set<String>? = null

    /**
     * @brief 本机输入法的包名集合
     *
     * 输入法键盘是浮在当前应用之上的窗口，无障碍会为它单独上报一次窗口切换。
     * 若把它当成「切到了输入法应用」，就会出现「在设置页打字被判成打开了别的应用」
     * 从而触发拦截的误伤。这里供各个判定入口统一排除。
     */
    fun imePackages(ctx: Context): Set<String> {
        cachedIme?.let { return it }
        val set = HashSet<String>()
        try {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.inputMethodList?.forEach { set.add(it.packageName) }
            imm?.enabledInputMethodList?.forEach { set.add(it.packageName) }
        } catch (e: Exception) {
            // 个别 ROM 查询输入法列表会抛异常，退化为空集合（不影响其他判定）
        }
        cachedIme = set
        return set
    }

    /**
     * @brief 需要纳入默认名单的系统应用
     *
     * 排除：本应用自身、android/systemui、桌面（这些本来就不参与计时与拦截）、
     * 以及输入法（键盘窗口独立于应用归属，放进来只会让名单变噪）。
     */
    fun systemPackages(ctx: Context): Set<String> {
        val out = HashSet<String>()
        val launchers = defaultLaunchers(ctx) + HOME_PKGS
        val imes = imePackages(ctx)
        try {
            for (pi in ctx.packageManager.getInstalledPackages(0)) {
                val ai = pi.applicationInfo ?: continue
                if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0) continue
                val pkg = pi.packageName
                if (pkg.isEmpty() || pkg == ctx.packageName) continue
                if (pkg == "android" || pkg.startsWith("com.android.systemui")) continue
                if (launchers.contains(pkg)) continue
                if (imes.contains(pkg)) continue
                out.add(pkg)
            }
        } catch (e: Exception) {
            // 列举失败时返回空集合，不阻塞配置加载
        }
        return out
    }

    /** @brief 是否为输入法包（键盘窗口不应改变前台应用归属） */
    fun isImePackage(ctx: Context, pkg: String): Boolean =
        pkg.isNotEmpty() && imePackages(ctx).contains(pkg)

    /**
     * @brief 列举可以管控的应用（排除本应用自身）
     *
     * 只查桌面入口会漏掉没有启动图标的已安装应用，名单里就选不到它们。
     * 这里合并「桌面可见应用」与「其余非系统已安装应用」，规则见 [AppCatalog.select]。
     */
    fun installedApps(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager

        val launcherPkgs = LinkedHashSet<String>()
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (r in pm.queryIntentActivities(intent, 0)) {
                r.activityInfo?.packageName?.let { launcherPkgs.add(it) }
            }
        } catch (e: Exception) {
            // 查询失败时退化为仅用已安装列表
        }

        val installed = ArrayList<AppCatalog.InstalledPkg>()
        try {
            for (pi in pm.getInstalledPackages(0)) {
                val ai = pi.applicationInfo ?: continue
                installed.add(
                    AppCatalog.InstalledPkg(
                        pkg = pi.packageName,
                        isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        enabled = ai.enabled
                    )
                )
            }
        } catch (e: Exception) {
            // 个别 ROM 拒绝列举时保留桌面来源
        }

        val systemSet = installed.filter { it.isSystem }.map { it.pkg }.toHashSet()
        val list = ArrayList<AppEntry>()
        for (pkg in AppCatalog.select(launcherPkgs, installed, ctx.packageName)) {
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
            val icon = try {
                pm.getApplicationIcon(pkg)
            } catch (e: Exception) {
                null
            }
            list.add(AppEntry(pkg, label, icon, systemSet.contains(pkg)))
        }
        // 用户应用在前、系统应用在后，各自按名称排序
        list.sortWith(compareBy({ it.isSystem }, { it.label.lowercase() }, { it.pkg }))
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
