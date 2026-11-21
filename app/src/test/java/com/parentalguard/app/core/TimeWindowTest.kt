package com.parentalguard.app.core

import com.parentalguard.app.model.TimeSlot
import com.parentalguard.app.model.TimeWindowRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** @brief 时间段管控：边界、跨零点、星期切换与多时段叠加 */
class TimeWindowTest {

    /** 以 2026-11-16（周一）为基准构造时间戳 */
    private fun ts(dayOffset: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(2026, Calendar.NOVEMBER, 16 + dayOffset, hour, minute, 0)
        return c.timeInMillis
    }

    private fun rule(vararg slots: TimeSlot, enabled: Boolean = true) =
        TimeWindowRule(enabled = enabled, slots = slots.toMutableList())

    private fun slot(
        days: Set<Int>,
        startMinute: Int,
        endMinute: Int,
        enabled: Boolean = true
    ) = TimeSlot(days = LinkedHashSet(days), startMinute = startMinute, endMinute = endMinute, enabled = enabled)

    @Test
    fun isoDay_mondayIsOne() {
        assertEquals(1, TimeWindow.isoDayOf(ts(0, 12, 0)))
        assertEquals(7, TimeWindow.isoDayOf(ts(6, 12, 0)))
    }

    @Test
    fun sameDayWindow_includesStartExcludesEnd() {
        val r = rule(slot(setOf(1), 8 * 60, 11 * 60))

        assertNull("07:59 之前不命中", TimeWindow.activeSlot(r, ts(0, 7, 59)))
        assertNotNull("08:00 起命中", TimeWindow.activeSlot(r, ts(0, 8, 0)))
        assertNotNull("10:59 仍命中", TimeWindow.activeSlot(r, ts(0, 10, 59)))
        assertNull("11:00 结束", TimeWindow.activeSlot(r, ts(0, 11, 0)))
    }

    @Test
    fun sameDayWindow_onlyOnSelectedDays() {
        val r = rule(slot(setOf(1), 8 * 60, 11 * 60))
        assertNull("周二同一时刻不生效", TimeWindow.activeSlot(r, ts(1, 9, 0)))
    }

    @Test
    fun crossMidnight_hitsLateNightAndEarlyMorning() {
        // 每天 22:00–07:00
        val r = rule(slot(setOf(1, 2, 3, 4, 5, 6, 7), 22 * 60, 7 * 60))

        assertNotNull("22:00 命中", TimeWindow.activeSlot(r, ts(0, 22, 0)))
        assertNotNull("凌晨 03:00 命中", TimeWindow.activeSlot(r, ts(1, 3, 0)))
        assertNotNull("06:59 仍命中", TimeWindow.activeSlot(r, ts(1, 6, 59)))
        assertNull("07:00 解除", TimeWindow.activeSlot(r, ts(1, 7, 0)))
        assertNull("中午不命中", TimeWindow.activeSlot(r, ts(1, 12, 0)))
    }

    @Test
    fun crossMidnight_carriesIntoNextDayEvenIfOnlyStartDaySelected() {
        // 只在周一 22:00–07:00 生效：周一 23:00 与周二 06:00 都应命中
        val r = rule(slot(setOf(1), 22 * 60, 7 * 60))
        assertNotNull("周一 23:00", TimeWindow.activeSlot(r, ts(0, 23, 0)))
        assertNotNull("周二 06:00（跨零点延续）", TimeWindow.activeSlot(r, ts(1, 6, 0)))
        assertNull("周二 23:00 不命中", TimeWindow.activeSlot(r, ts(1, 23, 0)))
    }

    @Test
    fun untilMs_pointsToSlotEnd() {
        val r = rule(slot(setOf(1), 22 * 60, 7 * 60))
        val hit = TimeWindow.activeSlot(r, ts(0, 23, 0))
        assertNotNull(hit)
        // 周一 23:00 命中，应在周二 07:00 解除
        assertEquals(ts(1, 7, 0), hit!!.untilMs)
    }

    @Test
    fun startEqualsEnd_meansAllDay() {
        val r = rule(slot(setOf(2), 0, 0))
        assertNotNull("周二 00:00 命中", TimeWindow.activeSlot(r, ts(1, 0, 0)))
        assertNotNull("周二 23:59 命中", TimeWindow.activeSlot(r, ts(1, 23, 59)))
        assertNull("周一不命中", TimeWindow.activeSlot(r, ts(0, 12, 0)))
        assertEquals("次日 00:00 解除", ts(2, 0, 0), TimeWindow.activeSlot(r, ts(1, 23, 59))!!.untilMs)
    }

    @Test
    fun disabledRuleOrSlotIsIgnored() {
        val s = slot(setOf(1, 2, 3, 4, 5, 6, 7), 22 * 60, 7 * 60)
        assertNull("规则关闭", TimeWindow.activeSlot(rule(s, enabled = false), ts(0, 23, 0)))
        assertNull("时段禁用", TimeWindow.activeSlot(rule(slot(setOf(1, 2, 3, 4, 5, 6, 7), 1320, 420, enabled = false)), ts(0, 23, 0)))
        assertNull("未选星期", TimeWindow.activeSlot(rule(slot(emptySet(), 22 * 60, 7 * 60)), ts(0, 23, 0)))
    }

    @Test
    fun overlappingSlots_pickLatestUntil() {
        // 22:00–07:00 跨零点 + 23:00–23:30 同一天内，23:10 命中后者（更晚解除？前者到次日 07:00 更晚）
        val r = rule(
            slot(setOf(1, 2, 3, 4, 5, 6, 7), 22 * 60, 7 * 60),
            slot(setOf(1, 2, 3, 4, 5, 6, 7), 23 * 60, 23 * 60 + 30)
        )
        val hit = TimeWindow.activeSlot(r, ts(0, 23, 10))
        assertNotNull(hit)
        assertEquals("按更严格原则取更晚解除的时段", ts(1, 7, 0), hit!!.untilMs)
    }

    @Test
    fun dayLabelPresets() {
        assertEquals("每天", TimeWindow.dayLabel(setOf(1, 2, 3, 4, 5, 6, 7)))
        assertEquals("工作日", TimeWindow.dayLabel(setOf(1, 2, 3, 4, 5)))
        assertEquals("周末", TimeWindow.dayLabel(setOf(6, 7)))
        assertEquals("周一、周三", TimeWindow.dayLabel(setOf(1, 3)))
        assertEquals("未选择星期", TimeWindow.dayLabel(emptySet()))
    }

    @Test
    fun slotLabelAndFormat() {
        assertEquals("21:00–07:00", slotLabelSpan(21 * 60, 7 * 60))
        assertEquals("全天", slotLabelSpan(0, 0))
        assertEquals("工作日 08:05–09:30", TimeWindow.slotLabel(slot(setOf(1, 2, 3, 4, 5), 8 * 60 + 5, 9 * 60 + 30)))
    }

    private fun slotLabelSpan(start: Int, end: Int): String {
        val label = TimeWindow.slotLabel(slot(setOf(1, 2, 3, 4, 5, 6, 7), start, end))
        return label.substringAfter(' ')
    }

    @Test
    fun nextBoundary_countedWhileOutsideWindow() {
        val r = rule(slot(setOf(1, 2, 3, 4, 5, 6, 7), 22 * 60, 7 * 60))
        val now = ts(0, 21, 0)
        val delta = TimeWindow.nextBoundaryIn(r, now)
        assertEquals(60 * 60 * 1000L, delta)
        assertTrue(delta > 0)
    }
}
