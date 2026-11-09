package com.tensun.parentalguard.core

import java.util.Locale

/** @brief 时长展示格式化 */
object Formatter {
    /** @brief 倒计时格式：h:mm:ss / mm:ss */
    fun hms(ms0: Long): String {
        val sec = ms0.coerceAtLeast(0L) / 1000L
        val h = sec / 3600L
        val m = (sec % 3600L) / 60L
        val s = sec % 60L
        return if (h > 0L) {
            String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.CHINA, "%02d:%02d", m, s)
        }
    }

    /** @brief 中文可读时长（向上取整到分钟） */
    fun human(ms0: Long): String {
        val min = (ms0.coerceAtLeast(0L) + 59_999L) / 60_000L
        return when {
            min >= 60L -> "${min / 60L} 小时 ${min % 60L} 分"
            min > 0L -> "$min 分钟"
            else -> "不足 1 分钟"
        }
    }

    fun minutes(value: Int): String = "$value 分钟"
}
