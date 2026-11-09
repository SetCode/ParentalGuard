package com.tensun.parentalguard

import android.app.Application
import com.tensun.parentalguard.data.ConfigRepository
import com.tensun.parentalguard.data.UsageRepository
import com.tensun.parentalguard.service.GuardService
import com.tensun.parentalguard.service.GuardStarter
import com.tensun.parentalguard.share.ShareSync

/** @brief 应用入口：预加载配置、恢复共享数据集并尝试恢复常驻服务 */
class ParentalGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigRepository.get(this)
        UsageRepository.ensureToday(this, System.currentTimeMillis())
        // 语音为 apk 内置音频，无需预热引擎
        // 恢复局域网共享：重新读取已保存的数据集、按设置重启服务器 / 自动重连
        ShareSync.init(this)
        GuardStarter.start(this, GuardService.ACTION_REFRESH)
    }
}
