package com.tensun.parentalguard.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tensun.parentalguard.model.RulesConfig
import com.tensun.parentalguard.share.ShareSync
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * @brief 管控配置仓库：SharedPreferences + 进程内缓存，配置修改后服务立即生效
 *
 * 支持按「监控对象」切换独立数据分区：默认对象沿用原来的 pg_config（兼容已有配置），
 * 其余对象使用 pg_config_<id>。切换不会改动其他对象的数据。
 * 本地配置变更后异步通知共享层，避免在仓库锁内跨锁调用造成死锁。
 */
object ConfigRepository {
    private const val PREF = "pg_config"
    private const val KEY = "rules_json"

    private val notifyHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cached: RulesConfig? = null

    @Volatile
    private var scope: String = ""

    private val listeners = CopyOnWriteArrayList<(RulesConfig) -> Unit>()

    private fun prefName(): String =
        if (scope.isEmpty() || scope == "default") PREF else "${PREF}_$scope"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(prefName(), Context.MODE_PRIVATE)

    fun get(ctx: Context): RulesConfig {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val raw = sp(ctx).getString(KEY, null)
            val loaded = if (raw.isNullOrBlank()) {
                RulesConfig()
            } else {
                try {
                    RulesConfig.fromJson(JSONObject(raw))
                } catch (e: Exception) {
                    RulesConfig()
                }
            }
            // 三类名单必须包含管控自身，否则家长可能在开启白名单或删空名单后把自己锁在外面
            loaded.ensureSelf(ctx.packageName)
            cached = loaded
            return loaded
        }
    }

    /** @brief 写入当前分区并通知监听者；不触发共享回写 */
    private fun saveInternal(ctx: Context, cfg: RulesConfig) {
        cfg.ensureSelf(ctx.packageName)
        cached = cfg
        sp(ctx).edit().putString(KEY, cfg.toJson().toString()).apply()
        listeners.forEach {
            try { it(cfg) } catch (e: Exception) { /* 忽略监听器异常 */ }
        }
    }

    fun save(ctx: Context, cfg: RulesConfig) {
        synchronized(this) { saveInternal(ctx, cfg) }
        notifyShareChanged()
    }

    /** @brief 就地修改配置并持久化 */
    fun update(ctx: Context, block: (RulesConfig) -> Unit) {
        synchronized(this) {
            val c = get(ctx)
            block(c)
            saveInternal(ctx, c)
        }
        notifyShareChanged()
    }

    /** @brief 异步通知共享层，保证任何调用路径都不在仓库锁内跨锁 */
    private fun notifyShareChanged() {
        notifyHandler.post {
            try { ShareSync.onLocalConfigChanged() } catch (e: Exception) { /* 忽略 */ }
        }
    }

    /** @brief 本机完整配置 JSON，只用于本地，绝不写入共享数据集 */
    fun currentJson(ctx: Context): String = get(ctx).toJson().toString()

    /** @brief 局域网共享配置快照：已剔除「启用全部管控规则」与「应用白名单」 */
    fun currentShareJson(ctx: Context): String = get(ctx).toShareJson().toString()

    fun currentScope(): String = scope

    /**
     * @brief 应用服务器裁决后的共享配置，不回写共享层（避免同步环）
     *
     * 共享字段覆盖本机，但「启用全部管控规则 / 应用白名单」始终保持本机值，
     * 因此任何设备同步都不会改动其他设备的这两项设置。
     */
    fun applyShareJson(ctx: Context, json: String) {
        if (json.isBlank()) return
        val shared = try {
            RulesConfig.fromJson(JSONObject(json))
        } catch (e: Exception) {
            return
        }
        synchronized(this) {
            val local = get(ctx)
            local.copyLocalOnlyTo(shared)
            // toJson() 的键顺序固定，可直接做文本比对：8 秒一次的同步若内容未变，
            // 不重复写盘、不重复刷新界面，也不打断家长正在进行的编辑。
            if (shared.toJson().toString() != local.toJson().toString()) {
                saveInternal(ctx, shared)
            }
        }
    }

    /**
     * @brief 切换监控对象分区
     *
     * 新分区首次使用时载入该对象的共享配置，并沿用本机当前的仅本机字段，
     * 避免切换 / 新建对象时把本机的总开关和白名单重置掉。
     */
    fun switchScope(ctx: Context, id: String, shareJson: String) {
        synchronized(this) {
            val target = if (id == "default") "" else id
            if (scope == target) return
            val carry = get(ctx)
            scope = target
            cached = null
            val prefs = sp(ctx)
            if (prefs.getString(KEY, null).isNullOrBlank()) {
                val cfg = if (shareJson.isNotEmpty()) {
                    try { RulesConfig.fromJson(JSONObject(shareJson)) } catch (e: Exception) { RulesConfig() }
                } else {
                    RulesConfig()
                }
                carry.copyLocalOnlyTo(cfg)
                cfg.ensureSelf(ctx.packageName)
                prefs.edit().putString(KEY, cfg.toJson().toString()).apply()
            }
            cached = null
        }
    }

    fun addListener(l: (RulesConfig) -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: (RulesConfig) -> Unit) {
        listeners.remove(l)
    }
}
