package com.tensun.parentalguard.share

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** @brief 合并结果：合并后的数据集、检测到的并发冲突、新的全局版本 */
data class MergeResult(
    val state: SharedState,
    val conflicts: List<String>,
    val revision: Long
)

/**
 * @brief 共享数据集的本地持久化和并发控制
 *
 * 所有读写都在同一把锁内串行完成：HTTP 服务端的多个并发请求、客户端同步线程、
 * 1Hz 计时心跳、界面操作最终都汇聚到这里，因此不存在交叉写导致的重复累计或回退。
 * 每次变更立即写入 SharedPreferences，进程被杀 / 设备重启后由 init() 完整恢复。
 */
object ShareStore {

    private const val PREF = "pg_share"
    private const val KEY_STATE = "state_json"
    private const val KEY_SETTINGS = "settings_json"
    private const val KEY_DEVICE_ID = "device_id"

    private val lockObj = Any()

    private lateinit var appCtx: Context

    @Volatile
    var deviceId: String = ""

    @Volatile
    var settings = ShareSettings()

    @Volatile
    var status = ShareStatus()

    @Volatile
    var lastSyncAtMs: Long = 0L

    @Volatile
    var everConnected: Boolean = false

    /** 计时更新记录最多 30 秒一条，避免 1Hz 心跳刷满更新记录 */
    @Volatile
    private var lastTimingLogAt: Long = 0L

    private var state = SharedState()

    private fun sp() = appCtx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** @brief 应用启动 / 服务重建时恢复全部共享状态 */
    fun init(ctx: Context) = synchronized(lockObj) {
        if (!::appCtx.isInitialized) appCtx = ctx.applicationContext
        val p = sp()
        var id = p.getString(KEY_DEVICE_ID, null).orEmpty()
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString()
            p.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        deviceId = id

        // 服务器名称固定使用设备型号：忽略持久化里的历史自定义值，保证局域网内各端展示一致
        settings = try {
            val o = JSONObject(p.getString(KEY_SETTINGS, null).orEmpty().ifEmpty { "{}" })
            ShareSettings(
                serverEnabled = o.optBoolean("serverEnabled", false),
                port = o.optInt("port", 8765).coerceIn(1024, 65535),
                peerUrl = o.optString("peerUrl", ""),
                autoConnect = o.optBoolean("autoConnect", true),
                deviceName = deviceName()
            )
        } catch (e: Exception) {
            ShareSettings(deviceName = deviceName())
        }

        state = try {
            val raw = p.getString(KEY_STATE, null)
            if (raw.isNullOrBlank()) SharedState() else SharedState.fromJson(JSONObject(raw)) ?: SharedState()
        } catch (e: Exception) {
            SharedState()
        }
        state.serverDeviceId = id

        // 首次运行创建一个默认对象，沿用现有本地配置与计时数据，保证兼容老版本
        if (state.subjects.isEmpty()) {
            val now = System.currentTimeMillis()
            val r = SubjectRecord(id = "default", name = deviceName(), createdAtMs = now, updatedAtMs = now, writerId = id)
            r.configJson = com.tensun.parentalguard.data.ConfigRepository.currentShareJson(appCtx)
            r.addHistory(ChangeRecord(now, id, deviceName(), "create", "创建默认监控对象", 0L))
            state.subjects[r.id] = r
        }
        // 旧版本默认对象叫「默认设备」；迁移为设备型号，仅处理本机创建且仍为初始名的对象
        state.subjects["default"]?.let { d ->
            if (d.name == "默认设备" && d.writerId == id) {
                d.name = deviceName()
                d.updatedAtMs = System.currentTimeMillis()
                d.writerId = id
            }
        }

        // 迁移：旧版本共享数据集里可能存有已改为「仅本机」的字段，启动时统一剥离，
        // 避免这两项设置继续在网络上传输，也避免旧客户端读到后误用。
        sanitizeConfigsLocked()
        persistLocked()
    }

    /**
     * @brief 服务器名称固定取设备型号（Build.MODEL）
     *
     * 剔除 UDP 发现报文的分隔符与换行，长度限制 64 字符；型号不可用时兜底为「Android 设备」。
     * UDP 应答、ping 响应、更新记录与共享页展示统一从这里取值，避免各端名称不一致。
     */
    fun deviceName(): String {
        val model = try {
            android.os.Build.MODEL?.trim().orEmpty()
        } catch (e: Exception) {
            ""
        }
        return model.replace('|', '/').replace('\n', ' ').replace('\r', ' ').take(64)
            .ifEmpty { "Android 设备" }
    }

    /** @brief 剥离共享数据集中可能残留的「仅本机」字段，保证持久化与 HTTP 报文都不含这两项 */
    private fun sanitizeConfigsLocked() {
        for (sub in state.subjects.values) {
            sub.configJson = com.tensun.parentalguard.model.RulesConfig.normalizeShareJson(sub.configJson)
        }
    }

    private fun persistLocked() {
        val s = sp()
        s.edit()
            .putString(KEY_STATE, state.toJson().toString())
            .putString(KEY_SETTINGS, JSONObject().apply {
                put("serverEnabled", settings.serverEnabled)
                put("port", settings.port)
                put("peerUrl", settings.peerUrl)
                put("autoConnect", settings.autoConnect)
                put("deviceName", deviceName())
            }.toString())
            .apply()
    }

    /** @brief 深拷贝当前数据集，避免把内部可变对象交给网络线程 */
    fun snapshotState(): SharedState = synchronized(lockObj) {
        SharedState.fromJson(state.toJson()) ?: SharedState()
    }

    fun revision(): Long = synchronized(lockObj) { state.revision }

    fun activeId(): String = synchronized(lockObj) {
        val id = com.tensun.parentalguard.data.ConfigRepository.currentScope()
        if (state.subjects[id]?.deleted == false) id else "default"
    }

    fun activeSubject(): SubjectRecord? = synchronized(lockObj) {
        state.subjects[activeId()]?.takeIf { !it.deleted }
    }

    fun subjects(): List<SubjectRecord> = synchronized(lockObj) {
        state.subjects.values.filter { !it.deleted }.sortedBy { it.createdAtMs }
    }

    /** @brief 当前激活对象的全局（所有设备汇总）计时数据 */
    fun activeGlobalTiming(): SubjectTiming? = synchronized(lockObj) {
        activeSubject()?.timing?.let { SubjectTiming.fromJson(it.toJson()) }
    }

    fun subject(id: String): SubjectRecord? = synchronized(lockObj) {
        state.subjects[id]?.let { SubjectRecord.fromJson(it.toJson()) }
    }

    /** @brief 新建监控对象；id 用 UUID，永不复用，避免删除后被旧数据复活 */
    fun createSubject(name: String): SubjectRecord? = synchronized(lockObj) {
        val clean = name.trim().take(64)
        if (clean.isEmpty()) return@synchronized null
        if (state.subjects.values.count { !it.deleted } >= MAX_SUBJECTS) return@synchronized null
        val now = System.currentTimeMillis()
        val r = SubjectRecord(
            id = UUID.randomUUID().toString(), name = clean,
            createdAtMs = now, updatedAtMs = now, writerId = deviceId
        )
        r.configJson = com.tensun.parentalguard.data.ConfigRepository.currentShareJson(appCtx)
        r.timing = SubjectTiming(day = shareToday(now))
        r.addHistory(ChangeRecord(now, deviceId, deviceName(), "create", "创建监控对象「$clean」", state.revision + 1))
        state.subjects[r.id] = r
        bumpLocked()
        persistLocked()
        SubjectRecord.fromJson(r.toJson())
    }

    fun renameSubject(id: String, name: String): Boolean = synchronized(lockObj) {
        val r = state.subjects[id] ?: return@synchronized false
        if (r.deleted) return@synchronized false
        val clean = name.trim().take(64)
        if (clean.isEmpty() || clean == r.name) return@synchronized false
        val now = System.currentTimeMillis()
        r.name = clean
        r.updatedAtMs = now
        r.writerId = deviceId
        r.addHistory(ChangeRecord(now, deviceId, deviceName(), "rename", "重命名为「$clean」", state.revision + 1))
        bumpLocked()
        persistLocked()
        true
    }

    /** @brief 删除采用 tombstone：保留记录供其他设备同步，避免旧客户端回放复活 */
    fun deleteSubject(id: String): Boolean = synchronized(lockObj) {
        val r = state.subjects[id] ?: return@synchronized false
        if (r.deleted || id == "default") return@synchronized false
        val now = System.currentTimeMillis()
        r.deleted = true
        r.deletedAtMs = now
        r.updatedAtMs = now
        r.writerId = deviceId
        r.addHistory(ChangeRecord(now, deviceId, deviceName(), "delete", "删除监控对象", state.revision + 1))
        bumpLocked()
        persistLocked()
        true
    }

    /** @brief 切换激活对象：切换本地引擎的独立数据分区，并载入该对象的共享配置 */
    fun switchSubject(id: String): Boolean = synchronized(lockObj) {
        val r = state.subjects[id] ?: return@synchronized false
        if (r.deleted) return@synchronized false
        val now = System.currentTimeMillis()
        com.tensun.parentalguard.data.ConfigRepository.switchScope(appCtx, id, r.configJson)
        com.tensun.parentalguard.data.UsageRepository.switchScope(appCtx, id, "")
        r.addHistory(ChangeRecord(now, deviceId, deviceName(), "switch", "切换到「${r.name}」", state.revision + 1))
        bumpLocked()
        persistLocked()
        true
    }

    /**
     * @brief 本地配置变更：仅在「共享字段」实际变化时写入当前对象并提升版本
     *
     * 仅本机字段（启用全部管控规则 / 应用白名单）的修改不会改变共享 JSON，
     * 因此不会刷新 configUpdatedAtMs、不写更新记录、不发起同步，
     * 也不会用较新的时间戳压过其他设备真正的配置变更。
     */
    fun recordConfigChange(configJson: String): Boolean = synchronized(lockObj) {
        val r = activeSubject() ?: return@synchronized false
        if (r.configJson == configJson) return@synchronized false
        val now = System.currentTimeMillis()
        r.configJson = configJson
        r.configUpdatedAtMs = now
        r.configWriterId = deviceId
        r.updatedAtMs = now
        r.writerId = deviceId
        r.addHistory(ChangeRecord(now, deviceId, deviceName(), "config", "更新本对象配置", state.revision + 1))
        bumpLocked()
        persistLocked()
        true
    }

    /**
     * @brief 本地计时变更：只写「本设备分量」，且只增不减
     *
     * 传入的是不含远端叠加的本地快照，因此远端累计永远不会被算进本设备分量，
     * 不会形成「A 累加 B、B 再累加 A」的循环放大。
     */
    fun recordLocalTiming(
        now: Long,
        totalMs: Long,
        continuousMs: Long,
        perAppMs: Map<String, Long>,
        timingActive: Boolean,
        timingPackage: String
    ): Unit = synchronized(lockObj) {
        val r = state.subjects[activeId()]?.takeIf { !it.deleted } ?: return@synchronized
        val today = shareToday(now)
        // 跨天清零时保留重置纪元，避免旧纪元分量在跨天后重新混入
        if (r.timing.day != today) {
            r.timing = SubjectTiming(day = today, epoch = r.timing.epoch)
        }
        val c = r.timing.clocks[deviceId]
            ?: DeviceClock(epoch = r.timing.epoch).also { r.timing.clocks[deviceId] = it }
        if (c.epoch != r.timing.epoch) {
            // 本设备分量属于旧纪元（本机或其他设备刚执行过时间重置），从零重建
            c.totalMs = 0L
            c.continuousMs = 0L
            c.perAppMs.clear()
            c.epoch = r.timing.epoch
        }
        val before = c.totalMs
        c.totalMs = maxOf(c.totalMs, totalMs.coerceIn(0L, MAX_DURATION_MS))
        c.continuousMs = maxOf(c.continuousMs, continuousMs.coerceIn(0L, MAX_DURATION_MS))
        for ((pkg, ms) in perAppMs) {
            if (pkg.isEmpty() || ms <= 0L) continue
            val v = ms.coerceAtMost(MAX_DURATION_MS)
            if (v > (c.perAppMs[pkg] ?: 0L)) c.perAppMs[pkg] = v
        }
        c.timingActive = timingActive
        c.timingPackage = timingPackage
        c.updatedAtMs = now
        r.timing.lastUpdateMs = now
        if (c.totalMs > before && now - lastTimingLogAt >= 30_000L) {
            lastTimingLogAt = now
            r.addHistory(
                ChangeRecord(now, deviceId, deviceName(), "timing", "累计使用时长更新", state.revision)
            )
        }
    }

    /**
     * @brief 时间重置：清空当前监控对象的全部计时分量并推进重置纪元
     *
     * 新纪元严格大于现有纪元（设备时钟回拨时取「现有纪元时间戳 + 1」），
     * 于是其他设备随后上报的旧纪元分量会在合并时被丢弃，不会把已清零的时长拉回。
     * 只替换当前对象的 timing 并写一条更新记录，不改配置、不改元数据、
     * 不触碰其他监控对象；重复重置的结果与首次重置完全一致。
     */
    fun resetActiveTiming(now: Long): Boolean = synchronized(lockObj) {
        if (!::appCtx.isInitialized) return@synchronized false
        val r = state.subjects[activeId()]?.takeIf { !it.deleted } ?: return@synchronized false
        val at = maxOf(now, resetEpochAtMs(r.timing.epoch) + 1L)
        r.timing = SubjectTiming(
            day = shareToday(now),
            epoch = newResetEpoch(at, deviceId),
            lastUpdateMs = now
        )
        r.addHistory(
            ChangeRecord(now, deviceId, deviceName(), "reset_time", "重置当前监控对象的全部计时", state.revision + 1)
        )
        bumpLocked()
        persistLocked()
        true
    }

    fun flush(): Unit = synchronized(lockObj) { persistLocked() }

    /** @brief 记录一条更新记录（连接、冲突等事件） */
    fun addEvent(action: String, detail: String): Unit = synchronized(lockObj) {
        val r = state.subjects[activeId()]?.takeIf { !it.deleted } ?: return@synchronized
        r.addHistory(
            ChangeRecord(System.currentTimeMillis(), deviceId, deviceName(), action, detail, state.revision)
        )
        persistLocked()
    }

    private fun bumpLocked() {
        state.revision += 1
        state.updatedAtMs = System.currentTimeMillis()
    }

    /**
     * @brief 合并远端数据集（服务端串行调用 / 客户端应用服务器返回）
     *
     * 并发冲突不做「拒绝写入」，而是按确定性规则自动合并：
     * 元数据取 (updatedAtMs, writerId) 较大者，配置取 (configUpdatedAtMs, configWriterId)
     * 较大者，计时按设备分量取最大值，删除采用 tombstone，更新记录按键去重。
     */
    fun mergeRemote(remote: SharedState?, baseRevision: Long): MergeResult = synchronized(lockObj) {
        val conflicts = ArrayList<String>()
        val r = remote
        if (r == null) return@synchronized MergeResult(snapshotState(), conflicts, state.revision)
        if (baseRevision >= 0L && baseRevision < state.revision) {
            conflicts.add("并发写入：客户端基于版本 $baseRevision，服务端已到 ${state.revision}")
        }
        val today = shareToday()
        for ((id, rs) in r.subjects) {
            if (id.isEmpty() || id.length > 128) continue
            val local = state.subjects[id]
            if (local == null) {
                if (state.subjects.size < MAX_SUBJECTS) state.subjects[id] = rs
            } else {
                val concurrent = local.absorb(rs, today)
                if (concurrent) conflicts.add("对象「${local.name}」被多台设备同时修改，已自动合并")
            }
        }
        sanitizeConfigsLocked()
        bumpLocked()
        if (r.serverDeviceId.isNotEmpty()) state.serverDeviceId = r.serverDeviceId
        persistLocked()
        MergeResult(snapshotState(), conflicts, state.revision)
    }

    /** @brief 用服务器返回的全量结果替换本地数据集（合并已在服务端完成） */
    fun replaceWith(newState: SharedState): Unit = synchronized(lockObj) {
        state = newState
        if (state.serverDeviceId.isEmpty()) state.serverDeviceId = deviceId
        sanitizeConfigsLocked()
        persistLocked()
    }

    fun updateSettings(block: (ShareSettings) -> Unit): Unit = synchronized(lockObj) {
        block(settings)
        settings = settings.copy(port = settings.port.coerceIn(1024, 65535))
        persistLocked()
    }

    /** @brief 发布状态快照（命名避开 @Volatile var status 自动生成的 setter） */
    fun publishStatus(s: ShareStatus) {
        status = s
    }
}
