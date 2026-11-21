package com.parentalguard.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.parentalguard.app.core.StateBus
import com.parentalguard.app.data.ConfigRepository
import com.parentalguard.app.data.UsageRepository
import com.parentalguard.app.service.GuardService
import com.parentalguard.app.service.GuardStarter
import com.parentalguard.app.share.ShareSync

/** @brief 应用入口：预加载配置、恢复共享数据集并尝试恢复常驻服务 */
class ParentalGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 跟踪自身界面可见性：规则判定据此避免误拦家长正在操作的设置页
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                StateBus.selfActivityVisible = true
                // 立即触发一次心跳，让遮罩与锁定提示按新状态刷新
                GuardService.requestTick()
            }

            override fun onActivityPaused(activity: Activity) {
                StateBus.selfActivityVisible = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        ConfigRepository.get(this)
        UsageRepository.ensureToday(this, System.currentTimeMillis())
        // 语音为 apk 内置音频，无需预热引擎
        // 恢复局域网共享：重新读取已保存的数据集、按设置重启服务器 / 自动重连
        ShareSync.init(this)
        GuardStarter.start(this, GuardService.ACTION_REFRESH)
    }
}
