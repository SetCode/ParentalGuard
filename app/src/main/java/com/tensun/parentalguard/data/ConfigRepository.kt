package com.tensun.parentalguard.data

import android.content.Context
import com.tensun.parentalguard.model.RulesConfig
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** @brief 管控配置仓库：SharedPreferences + 进程内缓存，配置修改后服务立即生效 */
object ConfigRepository {
    private const val PREF = "pg_config"
    private const val KEY = "rules_json"

    @Volatile
    private var cached: RulesConfig? = null
    private val listeners = CopyOnWriteArrayList<(RulesConfig) -> Unit>()

    fun get(ctx: Context): RulesConfig {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val raw = sp.getString(KEY, null)
            val loaded = if (raw.isNullOrBlank()) {
                RulesConfig()
            } else {
                try {
                    RulesConfig.fromJson(JSONObject(raw))
                } catch (e: Exception) {
                    RulesConfig()
                }
            }
            cached = loaded
            return loaded
        }
    }

    fun save(ctx: Context, cfg: RulesConfig) {
        cached = cfg
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, cfg.toJson().toString())
            .apply()
        listeners.forEach {
            try { it(cfg) } catch (e: Exception) { /* 忽略监听器异常 */ }
        }
    }

    /** @brief 就地修改配置并持久化 */
    fun update(ctx: Context, block: (RulesConfig) -> Unit) {
        synchronized(this) {
            val c = get(ctx)
            block(c)
            save(ctx, c)
        }
    }

    fun addListener(l: (RulesConfig) -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: (RulesConfig) -> Unit) {
        listeners.remove(l)
    }
}
