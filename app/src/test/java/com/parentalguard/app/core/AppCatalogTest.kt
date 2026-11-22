package com.parentalguard.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** @brief 名单应用源合并：不漏、不重、不淹没系统包 */
class AppCatalogTest {

    private val self = "com.parentalguard.app"

    private fun pkg(name: String, system: Boolean = false, enabled: Boolean = true) =
        AppCatalog.InstalledPkg(name, system, enabled)

    @Test
    fun includesInstalledAppWithoutLauncherIcon() {
        // 没有桌面图标但已安装的第三方应用，必须出现在名单里
        val result = AppCatalog.select(
            launcherPkgs = listOf("com.tencent.mm"),
            installed = listOf(
                pkg("com.tencent.mm"),
                pkg("com.example.ime.plugin")   // 无桌面入口
            ),
            selfPackage = self
        )
        assertTrue(result.contains("com.example.ime.plugin"))
        assertTrue(result.contains("com.tencent.mm"))
    }

    @Test
    fun launcherAppIsNotDuplicated() {
        val result = AppCatalog.select(
            launcherPkgs = listOf("com.tencent.mm", "com.android.chrome"),
            installed = listOf(pkg("com.tencent.mm"), pkg("com.android.chrome")),
            selfPackage = self
        )
        assertEquals(2, result.size)
        assertEquals(listOf("com.tencent.mm", "com.android.chrome"), result)
    }

    @Test
    fun systemAppsAreIncludedSoTheyCanBeManaged() {
        // 系统应用也要能在名单里单独增删（默认会被加进休息放行与免计时）
        val result = AppCatalog.select(
            launcherPkgs = listOf("com.android.settings"),
            installed = listOf(
                pkg("com.android.settings", system = true),
                pkg("com.android.providers.media", system = true),
                pkg("com.android.bluetooth", system = true)
            ),
            selfPackage = self
        )
        assertTrue(result.contains("com.android.settings"))
        assertTrue("无桌面入口的系统应用也要列出", result.contains("com.android.providers.media"))
        assertTrue(result.contains("com.android.bluetooth"))
    }

    @Test
    fun userAppsComeBeforeSystemApps() {
        val result = AppCatalog.select(
            launcherPkgs = listOf("com.android.settings", "com.tencent.mm"),
            installed = listOf(
                pkg("com.android.settings", system = true),
                pkg("com.tencent.mm"),
                pkg("com.example.plugin")
            ),
            selfPackage = self
        )
        // 用户应用（有入口 → 无入口）在前，系统应用在后
        assertEquals(
            listOf("com.tencent.mm", "com.example.plugin", "com.android.settings"),
            result
        )
    }

    @Test
    fun disabledAppsAreSkipped() {
        val result = AppCatalog.select(
            launcherPkgs = emptyList(),
            installed = listOf(pkg("com.example.old", enabled = false))
        , selfPackage = self)
        assertTrue(result.isEmpty())
    }

    @Test
    fun selfIsAlwaysExcluded() {
        val result = AppCatalog.select(
            launcherPkgs = listOf(self, "com.tencent.mm"),
            installed = listOf(pkg(self))
        , selfPackage = self)
        assertEquals(listOf("com.tencent.mm"), result)
        assertFalse(result.contains(self))
    }

    @Test
    fun emptyInputsAreSafe() {
        assertTrue(AppCatalog.select(emptyList(), emptyList(), self).isEmpty())
    }
}
