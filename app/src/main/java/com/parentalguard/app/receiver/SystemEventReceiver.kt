package com.parentalguard.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.parentalguard.app.data.UsageRepository
import com.parentalguard.app.service.GuardService
import com.parentalguard.app.service.GuardStarter

/** @brief 日期 / 系统时间变化后立即重算当日计数与次日零点解除时间 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        UsageRepository.ensureToday(ctx, System.currentTimeMillis())
        GuardStarter.start(ctx, GuardService.ACTION_REFRESH)
    }
}
