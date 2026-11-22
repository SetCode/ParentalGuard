package com.parentalguard.app.core

/**
 * @brief 应用目录：决定「名单里能看到哪些应用」
 *
 * 只看带桌面图标的应用会漏掉一批**已安装但没有启动图标**的应用
 * （输入法、插件、双开分身、无 launcher 入口的工具等），家长就没法把它们
 * 加进白名单/免计时/休息放行名单。这里把两个来源合并：
 *
 * 1. 有桌面入口的应用（有人要能启动它，就必须可控）；
 * 2. 其余**非系统**且处于启用状态的应用（系统应用没有图标的一律不列，
 *    否则设置页会被几百个 com.android.* 内部包淹没）。
 */
object AppCatalog {

    /** @brief 已安装包及其属性，由 PackageManager 采集后传入 */
    data class InstalledPkg(
        val pkg: String,
        val isSystem: Boolean,
        val enabled: Boolean
    )

    /**
     * @brief 合并两个来源，返回去重后的包名列表
     *
     * 顺序：先桌面可见应用（保持传入顺序），再补充其余非系统应用；
     * 本应用自身永远排除。
     */
    fun select(
        launcherPkgs: Collection<String>,
        installed: Collection<InstalledPkg>,
        selfPackage: String
    ): List<String> {
        val meta = installed.associateBy { it.pkg }
        val out = LinkedHashSet<String>()
        // 1. 用户应用（有桌面入口的）
        for (p in launcherPkgs) {
            if (p.isEmpty() || p == selfPackage) continue
            if (meta[p]?.isSystem == true) continue
            out.add(p)
        }
        // 2. 用户应用（无桌面入口，如插件、分身）
        for (p in installed) {
            if (p.pkg.isEmpty() || p.pkg == selfPackage) continue
            if (!p.enabled || p.isSystem) continue
            out.add(p.pkg)
        }
        // 3. 系统应用：有桌面入口的在前，其余在后
        for (p in launcherPkgs) {
            if (p.isEmpty() || p == selfPackage) continue
            if (meta[p]?.isSystem != true) continue
            if (meta[p]?.enabled == false) continue
            out.add(p)
        }
        for (p in installed) {
            if (p.pkg.isEmpty() || p.pkg == selfPackage) continue
            if (!p.enabled || !p.isSystem) continue
            out.add(p.pkg)
        }
        return out.toList()
    }
}
