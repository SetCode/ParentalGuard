package com.parentalguard.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.parentalguard.app.core.AutoStart
import com.parentalguard.app.data.UsageRepository
import com.parentalguard.app.service.GuardService
import com.parentalguard.app.service.GuardStarter

/**
 * @brief 开机 / 应用升级后恢复配置与当日计数，并重新拉起常驻服务
 *
 * 同时记录本次触发时间，界面据此显示「上次开机自启动」，
 * 让家长能确认自启动真的生效，而不是只能凭感觉。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val now = System.currentTimeMillis()
        val source = when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> "开机"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "应用升级"
            else -> intent?.action ?: "其他"
        }
        AutoStart.recordBoot(ctx, now, source)
        UsageRepository.onBoot(ctx, now)
        GuardStarter.start(ctx, GuardService.ACTION_BOOT)
    }
}
