package com.tensun.parentalguard.share

import com.tensun.parentalguard.model.DailyUsage
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** 单个监控对象保留的更新记录条数上限 */
const val MAX_HISTORY = 50

/** 单个共享数据集允许的对象数上限，防止异常数据撑爆内存 */
const val MAX_SUBJECTS = 100

/** 单次时长字段的合理上限（30 天），用于数据校验 */
const val MAX_DURATION_MS = 30L * 24 * 60 * 60 * 1000L

/**
 * @brief 生成一次时间重置的纪元标识
 *
 * 固定 20 位毫秒时间戳 + 发起设备 ID，因此字符串字典序就是纪元的新旧顺序；
 * 空串表示「从未重置」的初始纪元，比任何非空纪元都旧。
 */
fun newResetEpoch(atMs: Long, deviceId: String): String =
    String.format(Locale.CHINA, "%020d|%s", atMs.coerceAtLeast(0L), deviceId)

/** @brief 取回纪元中的时间戳，用于设备时钟回拨时生成严格递增的新纪元 */
fun resetEpochAtMs(epoch: String): Long = epoch.substringBefore("|").toLongOrNull() ?: 0L

/** @brief 更新记录：谁、何时、做了什么、当时的数据版本 */
data class ChangeRecord(
    val atMs: Long,
    val deviceId: String,
    val deviceName: String,
    val action: String,
    val detail: String,
    val revision: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("atMs", atMs)
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("action", action)
        put("detail", detail)
        put("revision", revision)
    }

    /** 去重键：同一设备同一毫秒同一动作视为一条 */
    fun key(): String = "$atMs|$deviceId|$action|$detail"

    companion object {
        fun fromJson(o: JSONObject): ChangeRecord = ChangeRecord(
            atMs = o.optLong("atMs", 0L),
            deviceId = o.optString("deviceId", ""),
            deviceName = o.optString("deviceName", ""),
            action = o.optString("action", ""),
            detail = o.optString("detail", ""),
            revision = o.optLong("revision", 0L)
        )
    }
}

/**
 * @brief 某个设备对某个监控对象的计时分量（G-Counter）
 *
 * 每个设备只写自己的分量，合并时对同一设备取「逐字段最大值」：
 * 分量单调递增，因此不会回退；同一设备同一段时长只算一次，因此不会重复；
 * 任一设备的增量都保留在独立分量里，因此不会丢失。
 */
data class DeviceClock(
    var totalMs: Long = 0L,
    var continuousMs: Long = 0L,
    var perAppMs: MutableMap<String, Long> = HashMap(),
    var timingActive: Boolean = false,
    var timingPackage: String = "",
    var updatedAtMs: Long = 0L,
    /** 该分量所属的时间重置纪元；空串为初始纪元，不同纪元的计数不可相加 */
    var epoch: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("totalMs", totalMs)
        put("continuousMs", continuousMs)
        put("timingActive", timingActive)
        put("timingPackage", timingPackage)
        put("updatedAtMs", updatedAtMs)
        put("epoch", epoch)
        put("perAppMs", JSONObject().also { o -> perAppMs.forEach { (k, v) -> o.put(k, v) } })
    }

    /**
     * @brief 与另一台设备上报的同一分量合并：同纪元只增不减，跨纪元以较新纪元为准
     *
     * 旧纪元分量一律丢弃，避免设备重置后其他设备回放的旧用量把计数拉回；
     * 若远端纪元更新，则整体采用远端分量（旧纪元计数已作废，不能相加）。
     */
    fun absorb(other: DeviceClock) {
        if (other.epoch != epoch) {
            if (other.epoch > epoch) {
                totalMs = other.totalMs
                continuousMs = other.continuousMs
                perAppMs = HashMap(other.perAppMs)
                epoch = other.epoch
                timingActive = other.timingActive
                timingPackage = other.timingPackage
                updatedAtMs = other.updatedAtMs
            }
            return
        }
        totalMs = maxOf(totalMs, other.totalMs)
        continuousMs = maxOf(continuousMs, other.continuousMs)
        for ((pkg, ms) in other.perAppMs) {
            if (ms > (perAppMs[pkg] ?: 0L)) perAppMs[pkg] = ms
        }
        if (other.updatedAtMs >= updatedAtMs) {
            timingActive = other.timingActive
            timingPackage = other.timingPackage
            updatedAtMs = other.updatedAtMs
        }
    }

    companion object {
        fun fromJson(o: JSONObject): DeviceClock {
            val c = DeviceClock(
                totalMs = o.optLong("totalMs", 0L).coerceIn(0L, MAX_DURATION_MS),
                continuousMs = o.optLong("continuousMs", 0L).coerceIn(0L, MAX_DURATION_MS),
                timingActive = o.optBoolean("timingActive", false),
                timingPackage = o.optString("timingPackage", ""),
                updatedAtMs = o.optLong("updatedAtMs", 0L),
                epoch = o.optString("epoch", "")
            )
            o.optJSONObject("perAppMs")?.let { obj ->
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    if (k.isEmpty() || k.length > 256) continue
                    val v = obj.optLong(k, 0L)
                    if (v > 0L) c.perAppMs[k] = v.coerceAtMost(MAX_DURATION_MS)
                }
            }
            return c
        }
    }
}

/** @brief 监控对象的共享计时状态：按设备分量的 CRDT 集合 */
data class SubjectTiming(
    var day: String = "",
    var clocks: MutableMap<String, DeviceClock> = HashMap(),
    var lastUpdateMs: Long = 0L,
    /** 当前计时集合所属的重置纪元；每次时间重置都会推进，旧纪元分量一律失效 */
    var epoch: String = ""
) {
    /** @brief 全局累计设备总时长 = 各设备分量之和 */
    fun globalTotalMs(): Long = clocks.values.sumOf { it.totalMs }

    /** @brief 全局单应用累计 = 同一应用在各设备上的分量之和 */
    fun globalPerAppMs(): Map<String, Long> {
        val m = HashMap<String, Long>()
        for (c in clocks.values) {
            for ((pkg, ms) in c.perAppMs) m[pkg] = (m[pkg] ?: 0L) + ms
        }
        return m
    }

    /** @brief 连续使用时长是设备本地概念，取各设备较大值，不跨设备相加 */
    fun globalContinuousMs(): Long = clocks.values.maxOfOrNull { it.continuousMs } ?: 0L

    fun anyTimingActive(): Boolean = clocks.values.any { it.timingActive }

    fun latestUpdateMs(): Long =
        maxOf(lastUpdateMs, clocks.values.maxOfOrNull { it.updatedAtMs } ?: 0L)

    /**
     * @brief 合并远端计时集合：先按重置纪元裁决，再按跨天规则处理，最后按设备分量合并
     *
     * 纪元较新的一侧为准，旧纪元分量全部淘汰，因此时间重置后即使其他设备
     * 回放重置前的旧数据，也不会把已清零的时长恢复；两侧同为最新纪元时，
     * 仍按「逐字段取较大值」的 G-Counter 规则，保证不重复、不回退、不丢失。
     */
    fun absorb(other: SubjectTiming, today: String) {
        val targetEpoch = maxOf(epoch, other.epoch)
        val oursValid = day == today
        val theirsValid = other.day == today

        if (oursValid && !theirsValid) {
            // 远端是旧日期：只接收它的重置纪元，不带回任何旧日分量
            if (targetEpoch > epoch) {
                epoch = targetEpoch
                clocks.clear()
            }
            lastUpdateMs = maxOf(lastUpdateMs, other.lastUpdateMs)
            return
        }
        if (!oursValid) {
            // 跨天：统一到今天从零开始，保留较新的重置纪元
            day = today
            clocks.clear()
            epoch = targetEpoch
            if (!theirsValid) {
                lastUpdateMs = maxOf(lastUpdateMs, other.lastUpdateMs)
                return
            }
        } else {
            // 两侧都是今天：淘汰不属于最新纪元的分量
            if (targetEpoch != epoch) {
                epoch = targetEpoch
                clocks.clear()
            } else {
                val it = clocks.entries.iterator()
                while (it.hasNext()) {
                    if (it.next().value.epoch != targetEpoch) it.remove()
                }
            }
        }

        for ((dev, remote) in other.clocks) {
            if (dev.isEmpty() || dev.length > 128) continue
            if (remote.epoch != targetEpoch) continue
            val local = clocks[dev]
            if (local == null) clocks[dev] = remote else local.absorb(remote)
        }
        lastUpdateMs = maxOf(lastUpdateMs, other.lastUpdateMs)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("day", day)
        put("lastUpdateMs", lastUpdateMs)
        put("epoch", epoch)
        put("clocks", JSONObject().also { o ->
            clocks.forEach { (k, v) -> o.put(k, v.toJson()) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): SubjectTiming {
            val t = SubjectTiming(
                day = o.optString("day", ""),
                lastUpdateMs = o.optLong("lastUpdateMs", 0L),
                epoch = o.optString("epoch", "")
            )
            o.optJSONObject("clocks")?.let { obj ->
                val it = obj.keys()
                while (it.hasNext() && t.clocks.size < MAX_SUBJECTS) {
                    val k = it.next()
                    val v = obj.optJSONObject(k) ?: continue
                    t.clocks[k] = DeviceClock.fromJson(v)
                }
            }
            return t
        }
    }
}

/**
 * @brief 一个监控对象：配置、计时、更新记录完全独立，与其他对象互不串扰
 *
 * 元数据（名称 / 配置）采用「时间戳 + 写入设备 ID」确定性裁决；
 * 计时采用按设备分量的 CRDT；删除采用 tombstone，避免旧同步把已删对象复活。
 */
data class SubjectRecord(
    val id: String,
    var name: String = "",
    var createdAtMs: Long = 0L,
    var updatedAtMs: Long = 0L,
    var writerId: String = "",
    var configJson: String = "",
    var configUpdatedAtMs: Long = 0L,
    var configWriterId: String = "",
    var timing: SubjectTiming = SubjectTiming(),
    var history: MutableList<ChangeRecord> = ArrayList(),
    var deleted: Boolean = false,
    var deletedAtMs: Long = 0L
) {
    fun addHistory(r: ChangeRecord) {
        history.add(r)
        if (history.size > MAX_HISTORY) {
            history.sortByDescending { it.atMs }
            history = history.take(MAX_HISTORY).toMutableList()
        }
    }

    /** @brief 与远端同名对象合并，返回是否检测到并发写入冲突 */
    fun absorb(remote: SubjectRecord, today: String): Boolean {
        val concurrent = updatedAtMs != remote.updatedAtMs && writerId != remote.writerId
        val oursNewer = updatedAtMs > remote.updatedAtMs ||
                (updatedAtMs == remote.updatedAtMs && writerId > remote.writerId)
        if (!oursNewer) {
            name = remote.name
            updatedAtMs = remote.updatedAtMs
            writerId = remote.writerId
        }
        val remoteConfigNewer = remote.configUpdatedAtMs > configUpdatedAtMs ||
                (remote.configUpdatedAtMs == configUpdatedAtMs && remote.configWriterId > configWriterId)
        if (remoteConfigNewer && remote.configJson.isNotEmpty()) {
            configJson = remote.configJson
            configUpdatedAtMs = remote.configUpdatedAtMs
            configWriterId = remote.configWriterId
        }
        timing.absorb(remote.timing, today)
        // 删除不可逆：任一侧标记删除即保持删除，避免旧设备回放把它复活
        if (remote.deleted) {
            deleted = true
            deletedAtMs = maxOf(deletedAtMs, remote.deletedAtMs)
        }
        val seen = HashSet<String>()
        val merged = ArrayList<ChangeRecord>()
        for (r in history + remote.history) {
            if (seen.add(r.key())) merged.add(r)
        }
        merged.sortByDescending { it.atMs }
        history = merged.take(MAX_HISTORY).toMutableList()
        createdAtMs = if (createdAtMs == 0L) remote.createdAtMs else minOf(createdAtMs, remote.createdAtMs)
        return concurrent
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("createdAtMs", createdAtMs)
        put("updatedAtMs", updatedAtMs)
        put("writerId", writerId)
        put("configJson", configJson)
        put("configUpdatedAtMs", configUpdatedAtMs)
        put("configWriterId", configWriterId)
        put("timing", timing.toJson())
        put("deleted", deleted)
        put("deletedAtMs", deletedAtMs)
        put("history", JSONArray().also { a -> history.forEach { a.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): SubjectRecord? {
            val id = o.optString("id", "")
            if (id.isEmpty() || id.length > 128) return null
            val r = SubjectRecord(
                id = id,
                name = o.optString("name", "").take(64),
                createdAtMs = o.optLong("createdAtMs", 0L),
                updatedAtMs = o.optLong("updatedAtMs", 0L),
                writerId = o.optString("writerId", ""),
                configJson = o.optString("configJson", ""),
                configUpdatedAtMs = o.optLong("configUpdatedAtMs", 0L),
                configWriterId = o.optString("configWriterId", ""),
                timing = SubjectTiming.fromJson(o.optJSONObject("timing") ?: JSONObject()),
                deleted = o.optBoolean("deleted", false),
                deletedAtMs = o.optLong("deletedAtMs", 0L)
            )
            o.optJSONArray("history")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = a.optJSONObject(i) ?: continue
                    r.history.add(ChangeRecord.fromJson(e))
                }
            }
            r.history.sortByDescending { it.atMs }
            if (r.history.size > MAX_HISTORY) r.history = r.history.take(MAX_HISTORY).toMutableList()
            return r
        }
    }
}

/** @brief 全量共享数据集，服务端与客户端交换的就是这一份 */
data class SharedState(
    var revision: Long = 0L,
    var serverDeviceId: String = "",
    var updatedAtMs: Long = 0L,
    var subjects: MutableMap<String, SubjectRecord> = LinkedHashMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("revision", revision)
        put("serverDeviceId", serverDeviceId)
        put("updatedAtMs", updatedAtMs)
        put("subjects", JSONArray().also { a ->
            subjects.values.forEach { a.put(it.toJson()) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): SharedState? {
            if (o.optInt("schema", 1) != 1) return null
            val s = SharedState(
                revision = o.optLong("revision", 0L).coerceAtLeast(0L),
                serverDeviceId = o.optString("serverDeviceId", ""),
                updatedAtMs = o.optLong("updatedAtMs", 0L)
            )
            o.optJSONArray("subjects")?.let { a ->
                for (i in 0 until a.length()) {
                    if (s.subjects.size >= MAX_SUBJECTS) break
                    val e = a.optJSONObject(i) ?: continue
                    val r = SubjectRecord.fromJson(e) ?: continue
                    s.subjects[r.id] = r
                }
            }
            return s
        }

        /** @brief 带 schema 版本的外层包装，便于以后协议升级做校验 */
        fun envelope(state: SharedState): JSONObject = state.toJson().apply { put("schema", 1) }
    }
}

/** @brief 本机作为客户端连接一台服务器时的配置 */
data class ShareSettings(
    var serverEnabled: Boolean = false,
    var port: Int = 8765,
    var peerUrl: String = "",
    var autoConnect: Boolean = true,
    var deviceName: String = ""
)

/** @brief 连接状态，明确区分「未启动 / 不可达 / 中断 / 同步失败」等原因 */
enum class LinkState {
    IDLE, SERVER_RUNNING, CONNECTED, SERVER_NOT_STARTED,
    UNREACHABLE, DISCONNECTED, SYNC_FAILED, CONFLICT_MERGED
}

/** @brief 共享状态快照，供界面展示 */
data class ShareStatus(
    val state: LinkState = LinkState.IDLE,
    val message: String = "未启用局域网共享",
    val detail: String = "",
    val lastSyncAtMs: Long = 0L,
    val localAddress: String = "",
    val peerUrl: String = ""
)

/** @brief 当前日期字符串，用于跨天隔离计时分量 */
fun shareToday(now: Long = System.currentTimeMillis()): String = DailyUsage.dateString(now)
