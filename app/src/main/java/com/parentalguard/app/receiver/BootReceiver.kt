package com.parentalguard.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.parentalguard.app.data.UsageRepository
import com.parentalguard.app.service.GuardService
import com.parentalguard.app.service.GuardStarter

/** @brief 开机 / 应用升级后恢复配置与当日计数，并重新拉起常驻服务 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        UsageRepository.onBoot(ctx, System.currentTimeMillis())
        GuardStarter.start(ctx, GuardService.ACTION_BOOT)
    }
}
