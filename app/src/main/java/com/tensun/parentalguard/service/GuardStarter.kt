package com.tensun.parentalguard.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** @brief 统一启动入口，兼容 Android 8+ 前台服务启动限制 */
object GuardStarter {
    private const val TAG = "GuardStarter"

    fun start(ctx: Context, action: String = GuardService.ACTION_REFRESH) {
        try {
            val app = ctx.applicationContext
            val i = Intent(app, GuardService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(i)
            } else {
                app.startService(i)
            }
        } catch (e: Exception) {
            // 后台启动被系统拒绝时，下一次开机广播 / 打开界面 / 无障碍连接会再次拉起
            Log.w(TAG, "启动管控服务失败: ${e.message}")
        }
    }
}
