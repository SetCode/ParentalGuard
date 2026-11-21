package com.parentalguard.app.model

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** @brief 当日计费状态，持久化保存，重启后继续累计 */
data class DailyUsage(
    var date: String = "",
    var totalUsedMs: Long = 0L,
    var perAppUsedMs: MutableMap<String, Long> = HashMap(),
    /** 自上次强制休息结束（或跨天）以来累计的连续使用时长 */
    var continuousUsedMs: Long = 0L,
    /** 休息截止时间戳，0 表示当前不在休息期 */
    var restUntilMs: Long = 0L,
    /** 当前正在计时的前台片段起点，0 表示已暂停计时 */
    var segmentStartMs: Long = 0L,
    var segmentPackage: String = "",
    var screenOffAtMs: Long = 0L,
    var lastCommitMs: Long = 0L,
    var warnedDaily: Boolean = false,
    var warnedSession: Boolean = false,
    var restFinishAnnounced: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("date", date)
        put("totalUsedMs", totalUsedMs)
        put("continuousUsedMs", continuousUsedMs)
        put("restUntilMs", restUntilMs)
        put("segmentStartMs", segmentStartMs)
        put("segmentPackage", segmentPackage)
        put("screenOffAtMs", screenOffAtMs)
        put("lastCommitMs", lastCommitMs)
        put("warnedDaily", warnedDaily)
        put("warnedSession", warnedSession)
        put("restFinishAnnounced", restFinishAnnounced)
        put("perAppUsedMs", JSONObject().also { o ->
            perAppUsedMs.forEach { (k, v) -> o.put(k, v) }
        })
    }

    /** @brief 跨天时清空全部每日计数，休息期与配置不受影响 */
    fun resetFor(newDate: String) {
        date = newDate
        totalUsedMs = 0L
        perAppUsedMs.clear()
        continuousUsedMs = 0L
        restUntilMs = 0L
        segmentStartMs = 0L
        segmentPackage = ""
        screenOffAtMs = 0L
        lastCommitMs = 0L
        warnedDaily = false
        warnedSession = false
        restFinishAnnounced = false
    }

    /**
     * @brief 恢复到项目预设的初始计时状态：当天日期 + 全部时间值归零 + 无片段 + 提示标志复位
     *
     * 与「新安装 / 跨天首次使用」完全一致的初始状态，供时间重置入口调用；
     * 无论重置前处于计时进行中、暂停、强制休息、已用完还是数据异常，结果都相同。
     */
    fun resetToInitial(now: Long) {
        resetFor(dateString(now))
    }

    companion object {
        fun dateString(now: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(now))

        fun fromJson(o: JSONObject): DailyUsage {
            val s = DailyUsage()
            s.date = o.optString("date", "")
            s.totalUsedMs = o.optLong("totalUsedMs", 0L)
            s.continuousUsedMs = o.optLong("continuousUsedMs", 0L)
            s.restUntilMs = o.optLong("restUntilMs", 0L)
            s.segmentStartMs = o.optLong("segmentStartMs", 0L)
            s.segmentPackage = o.optString("segmentPackage", "")
            s.screenOffAtMs = o.optLong("screenOffAtMs", 0L)
            s.lastCommitMs = o.optLong("lastCommitMs", 0L)
            s.warnedDaily = o.optBoolean("warnedDaily", false)
            s.warnedSession = o.optBoolean("warnedSession", false)
            s.restFinishAnnounced = o.optBoolean("restFinishAnnounced", false)
            o.optJSONObject("perAppUsedMs")?.let { obj ->
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    s.perAppUsedMs[k] = obj.optLong(k, 0L)
                }
            }
            return s
        }
    }
}

/** @brief 某一时刻的用量快照（含尚未落盘的前台片段增量） */
data class UsageSnapshot(
    val date: String,
    val totalMs: Long,
    val perAppMs: Map<String, Long>,
    val continuousMs: Long,
    val restUntilMs: Long
)
