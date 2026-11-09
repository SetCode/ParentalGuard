package com.tensun.parentalguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tensun.parentalguard.data.UsageRepository
import com.tensun.parentalguard.service.GuardService
import com.tensun.parentalguard.service.GuardStarter

/** @brief 日期 / 系统时间变化后立即重算当日计数与次日零点解除时间 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        UsageRepository.ensureToday(ctx, System.currentTimeMillis())
        GuardStarter.start(ctx, GuardService.ACTION_REFRESH)
    }
}
