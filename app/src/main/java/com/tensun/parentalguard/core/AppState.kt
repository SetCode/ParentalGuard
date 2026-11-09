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
}

/** @brief 无障碍服务上报的当前前台应用 */
object ForegroundTracker {
    @Volatile
    var currentPackage: String = ""

    @Volatile
    var currentClass: String = ""

    @Volatile
    var lastUpdateMs: Long = 0L

    fun update(pkg: String, cls: String) {
        currentPackage = pkg
        currentClass = cls
        lastUpdateMs = System.currentTimeMillis()
    }
}
