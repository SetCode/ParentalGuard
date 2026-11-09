package com.tensun.parentalguard.core

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.tensun.parentalguard.service.GuardAccessibilityService

data class PermissionState(
    val usage: Boolean,
    val accessibility: Boolean,
    val overlay: Boolean,
    val notification: Boolean,
    val battery: Boolean
) {
    /** 限制逻辑真正依赖的三项能力 */
    val critical: Boolean
        get() = usage && accessibility && overlay
}

/** @brief 运行时 / 特殊权限检测 */
object Permissions {

    fun usageAccess(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    fun accessibility(ctx: Context): Boolean = try {
        val enabled = Settings.Secure.getInt(
            ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
        ) == 1
        val services = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val cn = ComponentName(ctx, GuardAccessibilityService::class.java)
        enabled && (services.contains(cn.flattenToString()) ||
                services.contains(cn.flattenToShortString()) ||
                (StateBus.accessibilityConnected && services.contains(ctx.packageName)))
    } catch (e: Exception) {
        StateBus.accessibilityConnected
    }

    fun overlay(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (e: Exception) {
        false
    }

    fun notification(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun battery(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) {
        true
    }

    fun check(ctx: Context) = PermissionState(
        usage = usageAccess(ctx),
        accessibility = accessibility(ctx),
        overlay = overlay(ctx),
        notification = notification(ctx),
        battery = battery(ctx)
    )
}
