package com.tensun.parentalguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.tensun.parentalguard.core.BlockJudge
import com.tensun.parentalguard.core.ForegroundTracker
import com.tensun.parentalguard.core.KidPrompt
import com.tensun.parentalguard.core.LockKind
import com.tensun.parentalguard.core.StateBus
import com.tensun.parentalguard.data.ConfigRepository

/**
 * @brief 监测当前前台应用，并提供「返回桌面」的拦截动作
 *
 * 窗口切换事件到达时立即做一次应用级判定（白名单 / 单应用限额），
 * 命中的话不等 1 秒心跳就返回桌面，并给出图形 + 语音提示。
 */
class GuardAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: GuardAccessibilityService? = null

        /** @brief 拦截非白名单 / 超额应用时把设备送回桌面 */
        fun goHome(): Boolean = try {
            val s = instance
            if (s != null) {
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
        if (pkg == "android" || pkg.startsWith("com.android.systemui")) return
        // 悬浮遮罩 / 通知窗口属于本应用但不是家长界面，忽略
        if (pkg == packageName && !cls.startsWith("com.tensun.parentalguard.ui.")) return
        ForegroundTracker.update(pkg, cls)
        instantBlock(pkg)
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
        ForegroundTracker.update("", "")
        return super.onUnbind(intent)
    }
}
