package com.parentalguard.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.parentalguard.app.core.BlockJudge
import com.parentalguard.app.core.ForegroundTracker
import com.parentalguard.app.core.KidPrompt
import com.parentalguard.app.core.LockKind
import com.parentalguard.app.core.StateBus
import com.parentalguard.app.core.UsageAccess
import com.parentalguard.app.data.ConfigRepository
import com.parentalguard.app.data.UsageRepository

/**
 * @brief 监测当前前台应用，并提供「返回桌面」的拦截动作
 *
 * 窗口切换事件到达时立即做一次应用级判定（白名单 / 单应用限额），
 * 命中的话不等 1 秒心跳就返回桌面，并给出图形 + 语音提示。
 * 同时把前台状态变化即时通知计时链路：退出应用立刻暂停片段，
 * 重新进入立刻开启新片段，避免退出到桌面后仍按旧包名累计时长。
 */
class GuardAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: GuardAccessibilityService? = null

        /** @brief 拦截非白名单 / 超额应用时把设备送回桌面 */
        fun goHome(): Boolean = try {
            val s = instance
            if (s != null) {
                // 先落定「非应用状态」并结算片段，避免桌面窗口事件到达前
                // 仍按被拦截应用继续计时
                val home = UsageAccess.defaultLaunchers(s).firstOrNull().orEmpty()
                ForegroundTracker.markHome(home)
                UsageRepository.pause(s, System.currentTimeMillis())
                s.performGlobalAction(GLOBAL_ACTION_HOME)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        StateBus.accessibilityConnected = true
        GuardStarter.start(this, GuardService.ACTION_REFRESH)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        // 内容变化事件频率极高，只处理窗口切换，避免无谓耗电
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString().orEmpty()
        if (pkg.isEmpty()) return
        // 通知栏 / 状态栏等系统浮层短暂覆盖在应用之上，不改变应用归属
        if (pkg == "android" || pkg.startsWith("com.android.systemui")) return
        // 输入法键盘同理：它浮在当前应用之上，不能算「切换到了输入法应用」，
        // 否则在设置页打字会被误判并触发拦截
        if (UsageAccess.isImePackage(this, pkg)) return

        val now = System.currentTimeMillis()
        val prev = ForegroundTracker.currentPackage
        val prevAtHome = ForegroundTracker.atHome

        if (UsageAccess.isHomePackage(this, pkg)) {
            // 回到桌面：明确标记非应用状态，立即暂停计时
            ForegroundTracker.markHome(pkg)
            notifyTimingChanged(now, true)
            return
        }
        if (pkg == packageName || UsageAccess.isSystemSurface(this, pkg)) {
            // 家长设置界面 / 系统权限弹窗：同样不属于「目标应用在前台」
            // 悬浮遮罩等非 UI 窗口忽略，避免遮罩自身触发状态切换
            if (pkg == packageName && !cls.startsWith("com.parentalguard.app.ui.")) return
            ForegroundTracker.markHome("")
            notifyTimingChanged(now, true)
            return
        }

        // 普通应用窗口：包名变化或从桌面重新进入时立即结算旧片段、开启新片段
        ForegroundTracker.update(pkg, cls)
        val changed = pkg != prev || prevAtHome
        if (changed) notifyTimingChanged(now, true)
        instantBlock(pkg)
    }

    /** @brief 立即结算当前片段并请求一次心跳，实现「退出即暂停、进入即继续」 */
    private fun notifyTimingChanged(now: Long, request: Boolean) {
        UsageRepository.pause(this, now)
        if (request) GuardService.requestTick()
    }

    /** @brief 应用打开的瞬间判定并拦截；全设备限制仍由 GuardService 的全屏遮罩处理 */
    private fun instantBlock(pkg: String) {
        val cfg = ConfigRepository.get(this)
        val hit = BlockJudge.check(this, cfg, pkg, System.currentTimeMillis()) ?: return
        goHome()
        val ev = if (hit.kind == LockKind.WHITELIST) {
            KidPrompt.Event.BLOCK_WHITELIST
        } else {
            KidPrompt.Event.BLOCK_APP_LIMIT
        }
        // 与 GuardService 心跳使用同一去重键，避免同一应用被重复播报
        KidPrompt.fire(this, cfg, ev, "block:" + hit.kind.name + ":" + pkg)
    }

    override fun onInterrupt() {
        // 无需处理
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        StateBus.accessibilityConnected = false
        // 无法再确认前台时按「非应用状态」处理，宁可暂停也不误计
        ForegroundTracker.markHome("")
        UsageRepository.pause(this, System.currentTimeMillis())
        return super.onUnbind(intent)
    }
}
