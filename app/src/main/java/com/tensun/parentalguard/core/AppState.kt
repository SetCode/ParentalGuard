package com.tensun.parentalguard.core

/** @brief 主界面展示用的状态快照，由后台服务每秒刷新 */
data class UiStatus(
    val now: Long = 0L,
    val masterEnabled: Boolean = true,
    val serviceRunning: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val dailyEnabled: Boolean = false,
    val dailyLimitMs: Long = 0L,
    val dailyUsedMs: Long = 0L,
    val sessionEnabled: Boolean = false,
    val sessionLimitMs: Long = 0L,
    val continuousUsedMs: Long = 0L,
    val restRemainingMs: Long = 0L,
    val restTotalMs: Long = 0L,
    val locked: Boolean = false,
    val lockTitle: String = "",
    val lockReasons: List<String> = emptyList(),
    val foregroundLabel: String = "未知",
    val appRemaining: List<Pair<String, Long>> = emptyList(),
    val overlayError: String = ""
) {
    val dailyRemainingMs: Long
        get() = if (!dailyEnabled) -1L else (dailyLimitMs - dailyUsedMs).coerceAtLeast(0L)

    val sessionRemainingMs: Long
        get() = if (!sessionEnabled) -1L else (sessionLimitMs - continuousUsedMs).coerceAtLeast(0L)
}

/** @brief 进程内状态总线（服务与界面同进程） */
object StateBus {
    @Volatile
    var status: UiStatus = UiStatus()

    @Volatile
    var serviceRunning: Boolean = false

    @Volatile
    var accessibilityConnected: Boolean = false

    @Volatile
    var overlayError: String = ""

    /**
     * @brief 本应用自己的界面是否处于前台
     *
     * 由 ActivityLifecycleCallbacks 维护。规则判定优先采信这个信号：
     * 无障碍未上报或使用统计滞后时，不能因为拿到一个过期的前台包名
     * 就把正在配置的家长踢回桌面（历史 bug：开启白名单后打开设置页被误杀）。
     */
    @Volatile
    var selfActivityVisible: Boolean = false
}

/**
 * @brief 无障碍服务上报的当前前台应用
 *
 * atHome 用于「明确停留在桌面 / 非应用界面」的状态：该状态不设过期时间，
 * 会一直保持到下一个应用窗口事件，避免长时间停留桌面后被回退逻辑误判为
 * 「仍在之前的应用里」而继续累计时长。
 */
object ForegroundTracker {
    @Volatile
    var currentPackage: String = ""

    @Volatile
    var currentClass: String = ""

    @Volatile
    var lastUpdateMs: Long = 0L

    @Volatile
    var atHome: Boolean = false

    /** @brief 进入一个可计时应用（清除桌面状态） */
    fun update(pkg: String, cls: String) {
        currentPackage = pkg
        currentClass = cls
        lastUpdateMs = System.currentTimeMillis()
        atHome = false
    }

    /** @brief 明确回到桌面 / 非应用界面；homePkg 仅用于状态栏展示，可为空 */
    fun markHome(homePkg: String) {
        currentPackage = homePkg
        currentClass = ""
        lastUpdateMs = System.currentTimeMillis()
        atHome = true
    }
}
