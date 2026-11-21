package com.parentalguard.app.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** @brief 三类名单的模型行为：自保护、序列化roundtrip、仅本机生效、判定语义 */
class RulesConfigTest {

    private val self = "com.parentalguard.app"

    @Test
    fun ensureSelf_addsToAllThreeLists() {
        val cfg = RulesConfig()
        cfg.whitelist.packages.add("com.tencent.mm")
        cfg.ensureSelf(self)

        assertTrue("可玩白名单", cfg.whitelist.packages.contains(self))
        assertTrue("休息放行", cfg.restWhitelist.packages.contains(self))
        assertTrue("免计时", cfg.noCountWhitelist.packages.contains(self))
        // 不能顺手清掉家长已有的选择
        assertTrue(cfg.whitelist.packages.contains("com.tencent.mm"))
    }

    @Test
    fun ensureSelf_isIdempotentAndIgnoresEmptyPackage() {
        val cfg = RulesConfig()
        cfg.ensureSelf(self)
        cfg.ensureSelf(self)
        assertEquals(1, cfg.whitelist.packages.size)
        assertEquals(1, cfg.restWhitelist.packages.size)
        assertEquals(1, cfg.noCountWhitelist.packages.size)

        val blank = RulesConfig()
        blank.ensureSelf("")
        assertTrue(blank.whitelist.packages.isEmpty())
    }

    @Test
    fun jsonRoundTrip_keepsThreeLists() {
        val cfg = RulesConfig()
        cfg.whitelist.enabled = true
        cfg.whitelist.packages.addAll(listOf(self, "com.android.chrome"))
        cfg.restWhitelist.packages.addAll(listOf(self, "com.android.dialer"))
        cfg.noCountWhitelist.packages.addAll(listOf(self, "com.tencent.edu"))
        cfg.session.enabled = true
        cfg.session.continuousMinutes = 25

        val restored = RulesConfig.fromJson(JSONObject(cfg.toJson().toString()))

        assertEquals(cfg.whitelist.packages, restored.whitelist.packages)
        assertEquals(cfg.restWhitelist.packages, restored.restWhitelist.packages)
        assertEquals(cfg.noCountWhitelist.packages, restored.noCountWhitelist.packages)
        assertTrue(restored.whitelist.enabled)
        assertEquals(25, restored.session.continuousMinutes)
    }

    @Test
    fun legacyJson_withoutNewFields_keepsListsEmpty() {
        // 老版本配置里没有 restWhitelist / noCountWhitelist，升级后不能报错或产生脏数据
        val legacy = JSONObject(
            """{"schema":1,"masterEnabled":true,"parentPin":"1234",
               "whitelist":{"enabled":true,"packages":["com.a"]}}"""
        )
        val cfg = RulesConfig.fromJson(legacy)
        assertTrue(cfg.restWhitelist.packages.isEmpty())
        assertTrue(cfg.noCountWhitelist.packages.isEmpty())
        assertEquals(setOf("com.a"), cfg.whitelist.packages)
    }

    @Test
    fun shareSnapshot_dropsAllThreeLists() {
        val cfg = RulesConfig()
        cfg.ensureSelf(self)
        cfg.restWhitelist.packages.add("com.android.dialer")
        cfg.noCountWhitelist.packages.add("com.tencent.edu")

        val shared = JSONObject(cfg.toShareJson().toString())
        assertFalse(shared.has("whitelist"))
        assertFalse(shared.has("restWhitelist"))
        assertFalse(shared.has("noCountWhitelist"))
        // 其他规则仍需参与共享
        assertTrue(shared.has("session"))
        assertTrue(shared.has("dailyTotal"))
    }

    @Test
    fun copyLocalOnlyTo_carriesThreeListsAndKeepsSharedRules() {
        val local = RulesConfig()
        local.ensureSelf(self)
        local.restWhitelist.packages.add("com.android.dialer")

        val fromShare = RulesConfig()
        fromShare.session.enabled = true
        fromShare.whitelist.enabled = false

        local.copyLocalOnlyTo(fromShare)
        assertTrue(fromShare.restWhitelist.packages.contains("com.android.dialer"))
        assertTrue(fromShare.noCountWhitelist.packages.contains(self))
        assertTrue(fromShare.session.enabled)
    }

    @Test
    fun switchesTurnListsOffWithoutLosingPackages() {
        val cfg = RulesConfig()
        cfg.ensureSelf(self)
        cfg.restWhitelist.packages.add("com.android.dialer")
        cfg.noCountWhitelist.packages.add("com.tencent.edu")

        // 关闭开关：名单内容保留，但判定一律不放行 / 一律计时
        cfg.restWhitelist.enabled = false
        cfg.noCountWhitelist.enabled = false
        assertFalse(cfg.restAllows("com.android.dialer"))
        assertFalse(cfg.restAllows(self))
        assertTrue(cfg.countsUsage("com.tencent.edu"))
        assertTrue(cfg.countsUsage(self))
        assertTrue(cfg.restWhitelist.packages.contains("com.android.dialer"))
        assertTrue(cfg.noCountWhitelist.packages.contains("com.tencent.edu"))

        // 重新打开立即恢复
        cfg.restWhitelist.enabled = true
        cfg.noCountWhitelist.enabled = true
        assertTrue(cfg.restAllows("com.android.dialer"))
        assertFalse(cfg.countsUsage("com.tencent.edu"))
    }

    @Test
    fun jsonRoundTrip_keepsSwitchStates() {
        val cfg = RulesConfig()
        cfg.restWhitelist.enabled = false
        cfg.noCountWhitelist.enabled = true
        val restored = RulesConfig.fromJson(JSONObject(cfg.toJson().toString()))
        assertFalse(restored.restWhitelist.enabled)
        assertTrue(restored.noCountWhitelist.enabled)
    }

    @Test
    fun legacyJson_defaultsSwitchesToOn() {
        val legacy = JSONObject("""{"schema":1,"restWhitelist":{"packages":["com.a"]}}""")
        val cfg = RulesConfig.fromJson(legacy)
        assertTrue("老配置默认开启", cfg.restWhitelist.enabled)
        assertTrue(cfg.noCountWhitelist.enabled)
    }

    @Test
    fun restAllowsAndCountsUsage() {
        val cfg = RulesConfig()
        cfg.ensureSelf(self)
        cfg.restWhitelist.packages.add("com.android.dialer")
        cfg.noCountWhitelist.packages.add("com.tencent.edu")

        assertTrue(cfg.restAllows("com.android.dialer"))
        assertTrue("管控自身必须休息放行", cfg.restAllows(self))
        assertFalse(cfg.restAllows("com.tencent.mm"))

        assertFalse(cfg.countsUsage("com.tencent.edu"))
        assertFalse("管控自身不计时", cfg.countsUsage(self))
        assertTrue(cfg.countsUsage("com.tencent.mm"))
    }
}
