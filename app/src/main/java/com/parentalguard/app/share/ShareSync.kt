package com.parentalguard.app.share

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.parentalguard.app.data.ConfigRepository
import com.parentalguard.app.data.UsageRepository
import com.parentalguard.app.model.UsageSnapshot
import com.parentalguard.app.service.GuardService
import java.util.concurrent.Executors

/**
 * @brief 局域网共享编排层
 *
 * 负责：把本地配置 / 计时变更写入共享数据集、把服务器返回的全局计时数据
 * 叠加进规则引擎的快照、按固定周期自动同步、维护可区分原因的连接状态。
 * 进程重启后由 init() 恢复：按持久化的开关重新启动本地服务器，
 * 按持久化地址自动重连，并从已保存的数据集继续计时，不丢状态。
 */
object ShareSync {

    private lateinit var appCtx: Context
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pg-share-sync").apply { isDaemon = true } }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var inited = false

    @Volatile
    private var looping = false

    @Volatile
    private var lastFlushMs = 0L

    @Volatile
    private var lastSyncRequestMs = 0L

    private var loopThread: Thread? = null

    /** @brief 应用启动：恢复持久化状态并注册全局计时数据提供者 */
    fun init(ctx: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            appCtx = ctx.applicationContext
            ShareStore.init(appCtx)
            inited = true
        }

        // 规则引擎读到的快照 = 本设备本地数据 与 全局（所有设备汇总）数据的逐字段较大值，
        // 因此远端累计不会覆盖本地、本地也不会把远端增量重复记进自己分量。
        UsageRepository.globalUsageProvider = {
            val t = ShareStore.activeGlobalTiming()
            if (t == null || t.day != shareToday()) {
                null
            } else {
                UsageSnapshot(
                    date = t.day,
                    totalMs = t.globalTotalMs(),
                    perAppMs = t.globalPerAppMs(),
                    continuousMs = t.globalContinuousMs(),
                    restUntilMs = 0L
                )
            }
        }

        if (ShareStore.settings.serverEnabled) {
            ShareServer.start(ShareStore.settings.port)
        }
        refreshIdleStatus()
        startLoop()
        // 恢复后立即尝试重连一次，无需等待下一个周期
        if (ShareStore.settings.peerUrl.isNotEmpty() && ShareStore.settings.autoConnect) requestSync(true)
        mainHandler.post { GuardService.requestTick() }
    }

    /** @brief 本地配置发生修改时调用：仅共享字段变化才上报并触发同步 */
    fun onLocalConfigChanged() {
        if (!inited) return
        try {
            val changed = ShareStore.recordConfigChange(ConfigRepository.currentShareJson(appCtx))
            if (changed) requestSync(false)
        } catch (e: Exception) {
            // 共享层异常不得影响本地配置保存
        }
    }

    /**
     * @brief 每次心跳调用：把「本设备」的计时分量写入共享数据集
     *
     * 使用 localSnapshot（不含远端叠加），从根上避免多设备互相累加导致时长膨胀。
     */
    fun onLocalTimingChanged(now: Long, active: Boolean, pkg: String) {
        if (!inited) return
        try {
            val snap = UsageRepository.localSnapshot(appCtx, now)
            ShareStore.recordLocalTiming(
                now, snap.totalMs, snap.continuousMs, snap.perAppMs, active, pkg
            )
            if (now - lastFlushMs >= 3_000L) {
                lastFlushMs = now
                ShareStore.flush()
            }
        } catch (e: Exception) {
            // 共享层异常不得影响本地计时
        }
    }

    /**
     * @brief 时间重置后立即把新的重置纪元推送到局域网，并请求一次即时心跳
     *
     * 未配置对端地址时不发起网络请求，本机状态仍然完整重置（离线可用）。
     */
    fun onTimeReset() {
        if (!inited) return
        try {
            requestSync(true)
        } catch (e: Exception) {
            // 同步异常不得影响本机重置结果
        }
        mainHandler.post { GuardService.requestTick() }
    }

    /** @brief 请求一次同步；force=true 忽略节流（用于手动点击与启动恢复） */
    fun requestSync(force: Boolean = false) {
        if (!inited) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSyncRequestMs < 8_000L) return
        lastSyncRequestMs = now
        executor.execute { doSync() }
    }

    private fun doSync() {
        val url = ShareStore.settings.peerUrl.trim()
        if (url.isEmpty()) {
            refreshIdleStatus()
            return
        }
        val result = try {
            ShareClient.sync(url)
        } catch (e: Exception) {
            SyncResult(false, LinkState.SYNC_FAILED, "同步失败", e.message.orEmpty())
        }
        if (result.ok && result.serverState != null) {
            ShareStore.replaceWith(result.serverState)
            applyRemoteConfig()
            ShareStore.addEvent(
                if (result.state == LinkState.CONFLICT_MERGED) "conflict" else "sync",
                if (result.conflicts.isNotEmpty()) result.conflicts.joinToString("；") else "同步成功，版本 ${result.serverState.revision}"
            )
            mainHandler.post { GuardService.requestTick() }
        } else {
            ShareStore.addEvent("sync_failed", "${result.message}｜${result.detail}")
        }
        val local = ShareStore.settings
        ShareStore.publishStatus(
            ShareStatus(
                state = result.state,
                message = result.message,
                detail = result.detail,
                lastSyncAtMs = ShareStore.lastSyncAtMs,
                localAddress = if (ShareServer.isRunning()) {
                    "http://${ShareServer.localIpAddress()}:${local.port}"
                } else "",
                peerUrl = local.peerUrl
            )
        )
    }

    /** @brief 把服务端裁决后的配置应用到本地引擎（不触发回写，避免同步环） */
    private fun applyRemoteConfig() {
        val s = ShareStore.activeSubject() ?: return
        if (s.configJson.isNotEmpty()) ConfigRepository.applyShareJson(appCtx, s.configJson)
    }

    /** @brief 手动探测服务器连通性 */
    fun probe(url: String, onResult: (SyncResult) -> Unit) {
        executor.execute {
            val r = if (url.trim().isEmpty()) {
                SyncResult(false, LinkState.UNREACHABLE, "未填写服务器地址", "请输入服务器 IP 或自动发现")
            } else {
                ShareClient.probe(url)
            }
            ShareStore.publishStatus(
                ShareStatus(
                    r.state, r.message, r.detail, ShareStore.lastSyncAtMs,
                    if (ShareServer.isRunning()) "http://${ShareServer.localIpAddress()}:${ShareStore.settings.port}" else "",
                    ShareStore.settings.peerUrl
                )
            )
            mainHandler.post { onResult(r) }
        }
    }

    /** @brief 自动发现局域网内的服务器 */
    fun discover(onResult: (List<DiscoveredServer>) -> Unit) {
        executor.execute {
            val list = ShareClient.discover()
            mainHandler.post { onResult(list) }
        }
    }

    /** @brief 开关本机服务器，持久化设置后重启或停止 */
    fun setServerEnabled(enabled: Boolean, port: Int): Boolean {
        ShareStore.updateSettings {
            it.serverEnabled = enabled
            it.port = port
        }
        return if (enabled) {
            val ok = ShareServer.start(port)
            refreshIdleStatus()
            ok
        } else {
            ShareServer.stop()
            refreshIdleStatus()
            true
        }
    }

    /** @brief 连接 / 切换服务器地址 */
    fun connect(url: String): SyncResult {
        val normalized = ShareClient.normalize(url)
        ShareStore.updateSettings { it.peerUrl = normalized }
        ShareStore.everConnected = false
        ShareStore.publishStatus(
            ShareStatus(
                LinkState.UNREACHABLE, "正在连接服务器…", normalized,
                ShareStore.lastSyncAtMs,
                if (ShareServer.isRunning()) "http://${ShareServer.localIpAddress()}:${ShareStore.settings.port}" else "",
                normalized
            )
        )
        val result = ShareClient.probe(normalized)
        ShareStore.publishStatus(
            ShareStatus(
                if (result.ok) LinkState.CONNECTED else result.state,
                if (result.ok) "已连接服务器" else result.message,
                if (result.ok) result.detail else result.detail,
                ShareStore.lastSyncAtMs,
                if (ShareServer.isRunning()) "http://${ShareServer.localIpAddress()}:${ShareStore.settings.port}" else "",
                normalized
            )
        )
        if (result.ok) requestSync(true)
        return result
    }

    /** @brief 断开连接（保留地址，便于重新连接） */
    fun disconnect() {
        ShareStore.updateSettings { it.autoConnect = false }
        ShareStore.everConnected = false
        ShareStore.publishStatus(
            ShareStatus(
                LinkState.IDLE, "已断开共享连接",
                "点击「连接」可重新接入同一份数据集",
                ShareStore.lastSyncAtMs,
                if (ShareServer.isRunning()) "http://${ShareServer.localIpAddress()}:${ShareStore.settings.port}" else "",
                ""
            )
        )
    }

    /** @brief 新建 / 切换 / 删除对象后立即刷新引擎与同步 */
    fun afterSubjectChanged() {
        applyRemoteConfig()
        ShareStore.flush()
        mainHandler.post { GuardService.requestTick() }
        requestSync(true)
    }

    fun refreshIdleStatus() {
        val running = ShareServer.isRunning()
        val local = ShareStore.settings
        if (running && local.peerUrl.isEmpty()) {
            ShareStore.publishStatus(
                ShareStatus(
                    LinkState.SERVER_RUNNING,
                    "本机服务器运行中（等待其他设备连接）",
                    "http://${ShareServer.localIpAddress()}:${local.port}",
                    ShareStore.lastSyncAtMs,
                    "http://${ShareServer.localIpAddress()}:${local.port}",
                    ""
                )
            )
        } else if (!running && local.peerUrl.isEmpty()) {
            ShareStore.publishStatus(
                ShareStatus(
                    LinkState.IDLE, "未启用局域网共享",
                    "可开启本机服务器，或连接局域网内另一台设备",
                    ShareStore.lastSyncAtMs, "", ""
                )
            )
        } else if (!running && local.peerUrl.contains(ShareServer.localIpAddress()) && ShareServer.localIpAddress().isNotEmpty()) {
            ShareStore.publishStatus(
                ShareStatus(
                    LinkState.SERVER_NOT_STARTED, "本地服务器未启动",
                    "目标地址指向本机，但本机服务器未开启",
                    ShareStore.lastSyncAtMs, "", local.peerUrl
                )
            )
        }
    }

    private fun startLoop() {
        if (looping) return
        looping = true
        loopThread = Thread({
            while (looping) {
                try {
                    if (ShareStore.settings.autoConnect && ShareStore.settings.peerUrl.isNotEmpty()) {
                        doSync()
                    }
                } catch (e: Exception) {
                    // 单轮同步异常不影响后续周期
                }
                try {
                    Thread.sleep(8_000L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "pg-share-loop").also { it.isDaemon = true; it.start() }
    }
}
