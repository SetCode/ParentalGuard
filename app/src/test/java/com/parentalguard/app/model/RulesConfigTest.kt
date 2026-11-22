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
    fun timeWindowRoundTrip_keepsSlotsAndDays() {
        val cfg = RulesConfig()
        cfg.timeWindow.enabled = true
        cfg.timeWindow.slots.add(
            TimeSlot(
                id = "s1",
                days = LinkedHashSet(listOf(1, 3, 5)),
                startMinute = 21 * 60 + 30,
                endMinute = 6 * 60 + 45,
                enabled = true
            )
        )
        val restored = RulesConfig.fromJson(JSONObject(cfg.toJson().toString()))
        assertTrue(restored.timeWindow.enabled)
        assertEquals(1, restored.timeWindow.slots.size)
        val slot = restored.timeWindow.slots[0]
        assertEquals(setOf(1, 3, 5), slot.days)
        assertEquals(21 * 60 + 30, slot.startMinute)
        assertEquals(6 * 60 + 45, slot.endMinute)
    }

    @Test
    fun timeWindow_dirtyJsonIsClamped() {
        // 越界分钟、非法星期、缺字段都要被安全处理
        val dirty = JSONObject(
            """{"timeWindow":{"enabled":true,"slots":[
                {"days":[0,8,2],"startMinute":9999,"endMinute":-5}]}}"""
        )
        val cfg = RulesConfig.fromJson(dirty)
        val slot = cfg.timeWindow.slots[0]
        assertEquals("只保留合法星期", setOf(2), slot.days)
        assertEquals(1439, slot.startMinute)
        assertEquals(0, slot.endMinute)
    }

    @Test
    fun applySystemDefaults_addsToBothListsOnce() {
        val cfg = RulesConfig()
        val sys = setOf("com.android.settings", "com.android.bluetooth")

        assertTrue(cfg.applySystemDefaults(sys))
        assertTrue(cfg.systemDefaultsApplied)
        assertTrue(cfg.restWhitelist.packages.containsAll(sys))
        assertTrue(cfg.noCountWhitelist.packages.containsAll(sys))

        // 家长移出其中一个后，再次初始化不能把它加回来
        cfg.restWhitelist.packages.remove("com.android.bluetooth")
        assertFalse("已初始化过不再执行", cfg.applySystemDefaults(sys))
        assertFalse(cfg.restWhitelist.packages.contains("com.android.bluetooth"))
        // 免计时名单里的不受影响
        assertTrue(cfg.noCountWhitelist.packages.contains("com.android.bluetooth"))
    }

    @Test
    fun applySystemDefaults_emptyInputDoesNotLockInTheFlag() {
        val cfg = RulesConfig()
        assertFalse("空集合视为查询失败", cfg.applySystemDefaults(emptyList()))
        assertFalse(cfg.systemDefaultsApplied)
        // 下次拿到数据仍能正常初始化
        assertTrue(cfg.applySystemDefaults(setOf("com.android.settings")))
    }

    @Test
    fun systemDefaultsFlag_survivesJsonRoundTrip() {
        val cfg = RulesConfig()
        cfg.applySystemDefaults(setOf("com.android.settings"))
        val restored = RulesConfig.fromJson(JSONObject(cfg.toJson().toString()))
        assertTrue("重启后不能重复初始化", restored.systemDefaultsApplied)
        assertTrue(restored.restWhitelist.packages.contains("com.android.settings"))
    }

    @Test
    fun legacyConfig_defaultsToNotApplied() {
        val legacy = JSONObject("""{"schema":1,"masterEnabled":true}""")
        assertFalse(RulesConfig.fromJson(legacy).systemDefaultsApplied)
    }

    @Test
    fun resetToDefaults_clearsRulesButKeepsPin() {
        val cfg = RulesConfig()
        cfg.parentPin = "8899"
        cfg.masterEnabled = true
        cfg.dailyTotal.enabled = true
        cfg.dailyTotal.limitMinutes = 300
        cfg.session.enabled = true
        cfg.session.continuousMinutes = 45
        cfg.whitelist.enabled = true
        cfg.whitelist.packages.addAll(listOf("com.tencent.mm", "com.android.chrome"))
        cfg.restWhitelist.packages.add("com.android.dialer")
        cfg.noCountWhitelist.packages.add("com.tencent.edu")
        cfg.timeWindow.enabled = true
        cfg.timeWindow.slots.add(TimeSlot(days = LinkedHashSet(listOf(1, 2)), startMinute = 60, endMinute = 120))
        cfg.appLimits.add(AppLimitRule(packageName = "com.tencent.mm", dailyMinutes = 20, enabled = true))
        cfg.soundEnabled = false
        cfg.voiceEnabled = false
        cfg.systemDefaultsApplied = true

        cfg.resetToDefaults()

        // 规则回到默认并清空
        assertFalse(cfg.dailyTotal.enabled)
        assertFalse(cfg.session.enabled)
        assertFalse(cfg.whitelist.enabled)
        assertTrue("白名单内容清空", cfg.whitelist.packages.isEmpty())
        assertTrue(cfg.restWhitelist.packages.isEmpty())
        assertTrue(cfg.noCountWhitelist.packages.isEmpty())
        assertFalse(cfg.timeWindow.enabled)
        assertTrue(cfg.timeWindow.slots.isEmpty())
        assertTrue(cfg.appLimits.isEmpty())
        assertTrue("提示开关恢复默认", cfg.soundEnabled && cfg.voiceEnabled)
        assertFalse("重置后重新套用系统应用默认", cfg.systemDefaultsApplied)

        // 密码必须保留，否则孩子可以靠重置绕过管控
        assertEquals("8899", cfg.parentPin)
    }

    @Test
    fun resetToDefaultsThenEnsureSelfAndDefaults() {
        val cfg = RulesConfig()
        cfg.resetToDefaults()
        cfg.ensureSelf(self)
        cfg.applySystemDefaults(setOf("com.android.settings"))
        assertTrue("自保护仍然生效", cfg.restWhitelist.packages.contains(self))
        assertTrue(cfg.noCountWhitelist.packages.contains("com.android.settings"))
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
