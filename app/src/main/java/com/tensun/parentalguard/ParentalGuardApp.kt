package com.tensun.parentalguard

import android.app.Application
import com.tensun.parentalguard.data.ConfigRepository
import com.tensun.parentalguard.data.UsageRepository
import com.tensun.parentalguard.service.GuardService
import com.tensun.parentalguard.service.GuardStarter

/** @brief 应用入口：预加载配置并尝试恢复常驻服务 */
class ParentalGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigRepository.get(this)
        UsageRepository.ensureToday(this, System.currentTimeMillis())
        // 语音为 apk 内置音频，无需预热引擎
        GuardStarter.start(this, GuardService.ACTION_REFRESH)
    }
}
