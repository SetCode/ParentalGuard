package com.parentalguard.app.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * @brief 开机自启动辅助
 *
 * Android 没有统一的自启动权限，各厂商用私有「自启动管理」控制后台拉起，
 * 只能引导用户手动放行。这里按机型尝试直达对应的管理页，失败则退回应用详情页，
 * 再由详情页进入权限管理，避免直接抛 ActivityNotFound 崩掉界面。
 */
object AutoStart {

    private const val PREF = "pg_boot"
    private const val KEY_LAST_BOOT_MS = "lastBootMs"
    private const val KEY_LAST_BOOT_SOURCE = "lastBootSource"

    /** @brief 记录一次开机自启动，用于界面验证「自启动是否真的生效」 */
    fun recordBoot(ctx: Context, now: Long, source: String) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_BOOT_MS, now)
            .putString(KEY_LAST_BOOT_SOURCE, source)
            .apply()
    }

    /** @brief 上次开机自启动的时间戳，0 表示从未记录 */
    fun lastBootAt(ctx: Context): Long =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_BOOT_MS, 0L)

    /** @brief 上次自启动的来源（开机 / 升级 / 服务重建），便于排查 */
    fun lastBootSource(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_LAST_BOOT_SOURCE, "").orEmpty()

    /**
     * @brief 打开本机的自启动管理页
     *
     * @return 是否成功打开某个系统页面；false 表示该机型没有任何可用入口，
     *         调用方应提示用户手动前往「设置 → 应用 → 自启动」。
     */
    fun openSettings(ctx: Context): Boolean {
        for (intent in candidates(ctx)) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                return true
            } catch (e: Exception) {
                // 该机型没有这个页面，继续尝试下一个
            }
        }
        return false
    }

    /** @brief 当前机型是否有直达的自启动管理页（用于界面上区分文案） */
    fun hasDirectEntry(ctx: Context): Boolean = candidates(ctx).isNotEmpty()

    private fun candidates(ctx: Context): List<Intent> {
        val list = ArrayList<Intent>()
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        fun comp(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

        when {
            brand.contains("xiaomi") || brand.contains("redmi") -> list.add(
                comp(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            )

            brand.contains("huawei") || brand.contains("honor") -> list.add(
                comp(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            )

            brand.contains("oppo") || brand.contains("realme") -> list.add(
                comp("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
            )

            brand.contains("vivo") || brand.contains("iqoo") -> list.add(
                comp("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            )

            brand.contains("meizu") -> list.add(
                comp("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
            )

            brand.contains("asus") -> list.add(
                comp("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")
            )
        }

        // 通用兜底：应用详情页 → 权限/电池
        list.add(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", ctx.packageName, null)
            )
        )
        list.add(Intent(Settings.ACTION_SETTINGS))
        return list
    }
}
