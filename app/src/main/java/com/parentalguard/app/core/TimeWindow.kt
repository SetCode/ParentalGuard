package com.parentalguard.app.core

import com.parentalguard.app.model.TimeSlot
import com.parentalguard.app.model.TimeWindowRule
import java.util.Calendar

/**
 * @brief 时间段管控的纯计算逻辑
 *
 * 只依赖时间戳与配置，不碰 Context，便于单元测试覆盖跨零点、边界与星期切换。
 * 星期使用 ISO 编号（周一 = 1 … 周日 = 7），与界面勾选顺序一致。
 */
object TimeWindow {

    const val MINUTE_MS = 60_000L

    /** @brief 当前命中且解除时间最晚的时段；null 表示不在禁止时段内 */
    class Active(val slot: TimeSlot, val untilMs: Long)

    /** @brief 某一时刻的 ISO 星期（周一 = 1 … 周日 = 7） */
    fun isoDayOf(now: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        // Calendar: 周日 = 1 … 周六 = 7，转成 ISO 需要 -1 并把周日挪到 7
        val calDay = c.get(Calendar.DAY_OF_WEEK)
        return if (calDay == Calendar.SUNDAY) 7 else calDay - 1
    }

    /** @brief 当天 0 点时间戳；dayOffset = 1 表示明天 0 点 */
    fun dayStart(now: Long, dayOffset: Int = 0): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (dayOffset != 0) c.add(Calendar.DAY_OF_YEAR, dayOffset)
        return c.timeInMillis
    }

    /** @brief 当天 0 点起经过的分钟数（0..1439） */
    fun minuteOfDay(now: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** @brief 分钟数格式化成 HH:mm */
    fun formatMinute(minute: Int): String {
        val m = minute.coerceIn(0, 1439)
        return String.format("%02d:%02d", m / 60, m % 60)
    }

    /** @brief 星期集合的中文描述：每天 / 工作日 / 周末 / 周一、周三 */
    fun dayLabel(days: Set<Int>): String {
        val d = days.filter { it in 1..7 }.toSortedSet()
        return when {
            d.isEmpty() -> "未选择星期"
            d.size == 7 -> "每天"
            d == setOf(1, 2, 3, 4, 5) -> "工作日"
            d == setOf(6, 7) -> "周末"
            else -> d.joinToString("、") { DAY_NAMES[it - 1] }
        }
    }

    val DAY_NAMES = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** @brief 时段的单行描述，例如「工作日 21:00–07:00」 */
    fun slotLabel(slot: TimeSlot): String {
        val span = if (slot.startMinute == slot.endMinute) {
            "全天"
        } else {
            "${formatMinute(slot.startMinute)}–${formatMinute(slot.endMinute)}"
        }
        return "${dayLabel(slot.days)} $span"
    }

    /**
     * @brief 计算当前是否落在某个启用时段内
     *
     * 三种形态：
     * - 同一天内（start < end）：今天且星期命中且 minute ∈ [start, end)；
     * - 跨零点（start > end）：今天 start 之后，或昨天命中且今天 end 之前；
     * - 全天（start == end）：命中星期的整天，次日 00:00 解除。
     * 多个时段重叠时取解除时间最晚的一个，保证按更严格规则执行。
     */
    fun activeSlot(rule: TimeWindowRule, now: Long): Active? {
        if (!rule.enabled) return null
        var best: Active? = null
        for (slot in rule.slots) {
            if (!slot.enabled || slot.days.isEmpty()) continue
            val hit = match(slot, now) ?: continue
            val current = best
            if (current == null || hit.untilMs > current.untilMs) best = hit
        }
        return best
    }

    private fun match(slot: TimeSlot, now: Long): Active? {
        val today = isoDayOf(now)
        val yesterday = if (today == 1) 7 else today - 1
        val minute = minuteOfDay(now)
        val start = slot.startMinute.coerceIn(0, 1439)
        val end = slot.endMinute.coerceIn(0, 1439)

        if (start == end) {
            if (!slot.days.contains(today)) return null
            return Active(slot, dayStart(now, 1))
        }
        if (start < end) {
            if (!slot.days.contains(today) || minute < start || minute >= end) return null
            return Active(slot, dayStart(now) + end * MINUTE_MS)
        }
        // 跨零点
        if (slot.days.contains(today) && minute >= start) {
            return Active(slot, dayStart(now, 1) + end * MINUTE_MS)
        }
        if (slot.days.contains(yesterday) && minute < end) {
            return Active(slot, dayStart(now) + end * MINUTE_MS)
        }
        return null
    }

    /** @brief 距离下一个边界还有多少毫秒（用于卡片提示），无启用时段时返回 0 */
    fun nextBoundaryIn(rule: TimeWindowRule, now: Long): Long {
        if (!rule.enabled) return 0L
        val hit = activeSlot(rule, now)
        if (hit != null) return (hit.untilMs - now).coerceAtLeast(0L)
        // 不在时段内：找下一个开始时间
        var nearest = Long.MAX_VALUE
        for (slot in rule.slots) {
            if (!slot.enabled || slot.days.isEmpty()) continue
            for (offset in 0..7) {
                val dayBase = dayStart(now, offset)
                val startAt = dayBase + slot.startMinute.coerceIn(0, 1439) * MINUTE_MS
                if (startAt <= now) continue
                val day = isoDayOf(startAt)
                if (!slot.days.contains(day)) continue
                if (startAt < nearest) nearest = startAt
                break
            }
        }
        return if (nearest == Long.MAX_VALUE) 0L else nearest - now
    }
}
