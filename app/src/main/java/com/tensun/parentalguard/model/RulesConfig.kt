package com.tensun.parentalguard.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** @brief 每日设备总使用时长规则 */
data class DailyTotalRule(
    var enabled: Boolean = false,
    var limitMinutes: Int = 120
)

/** @brief 单次连续使用 + 到点强制休息规则 */
data class SessionRule(
    var enabled: Boolean = false,
    var continuousMinutes: Int = 30,
    var restMinutes: Int = 10
)

/** @brief 应用白名单规则，packages 为空时仅允许系统桌面与管控自身 */
data class WhitelistRule(
    var enabled: Boolean = false,
    val packages: MutableSet<String> = LinkedHashSet()
)

/** @brief 针对单个应用的每日累计时长规则 */
data class AppLimitRule(
    var id: String = UUID.randomUUID().toString(),
    var packageName: String = "",
    var appLabel: String = "",
    var dailyMinutes: Int = 30,
    var enabled: Boolean = true
)

/** @brief 全部管控配置，序列化后保存在 SharedPreferences */
data class RulesConfig(
    var masterEnabled: Boolean = true,
    var dailyTotal: DailyTotalRule = DailyTotalRule(),
    var session: SessionRule = SessionRule(),
    var whitelist: WhitelistRule = WhitelistRule(),
    var appLimits: MutableList<AppLimitRule> = ArrayList(),
    /** 有声提示总开关：关闭后提示音、振动与语音全部静音，仅保留图形 */
    var soundEnabled: Boolean = true,
    /** 是否播放 APK 内置的中文真人语音，供不识字的儿童理解；关闭后改为普通提示音 */
    var voiceEnabled: Boolean = true,
    /** 拦截白名单外 / 超额应用时是否出声提醒；关闭后仍自动返回桌面 */
    var blockSoundEnabled: Boolean = true,
    /** 拦截 / 状态变化时是否弹出大图标短时图形提示 */
    var visualPromptEnabled: Boolean = true,
    /** 家长密码，空串表示不启用密码保护 */
    var parentPin: String = "1234"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("masterEnabled", masterEnabled)
        put("soundEnabled", soundEnabled)
        put("voiceEnabled", voiceEnabled)
        put("blockSoundEnabled", blockSoundEnabled)
        put("visualPromptEnabled", visualPromptEnabled)
        put("parentPin", parentPin)
        put("dailyTotal", JSONObject().apply {
            put("enabled", dailyTotal.enabled)
            put("limitMinutes", dailyTotal.limitMinutes)
        })
        put("session", JSONObject().apply {
            put("enabled", session.enabled)
            put("continuousMinutes", session.continuousMinutes)
            put("restMinutes", session.restMinutes)
        })
        put("whitelist", JSONObject().apply {
            put("enabled", whitelist.enabled)
            put("packages", JSONArray().also { arr -> whitelist.packages.forEach { arr.put(it) } })
        })
        put("appLimits", JSONArray().also { arr ->
            appLimits.forEach { r ->
                arr.put(JSONObject().apply {
                    put("id", r.id)
                    put("packageName", r.packageName)
                    put("appLabel", r.appLabel)
                    put("dailyMinutes", r.dailyMinutes)
                    put("enabled", r.enabled)
                })
            }
        })
    }

    companion object {
        const val SCHEMA = 1

        fun fromJson(o: JSONObject): RulesConfig {
            val cfg = RulesConfig()
            cfg.masterEnabled = o.optBoolean("masterEnabled", cfg.masterEnabled)
            cfg.soundEnabled = o.optBoolean("soundEnabled", cfg.soundEnabled)
            // 旧版本配置没有这些字段时采用默认值，保证升级后原有规则不变
            cfg.voiceEnabled = o.optBoolean("voiceEnabled", cfg.voiceEnabled)
            cfg.blockSoundEnabled = o.optBoolean("blockSoundEnabled", cfg.blockSoundEnabled)
            cfg.visualPromptEnabled = o.optBoolean("visualPromptEnabled", cfg.visualPromptEnabled)
            cfg.parentPin = o.optString("parentPin", cfg.parentPin)

            o.optJSONObject("dailyTotal")?.let {
                cfg.dailyTotal.enabled = it.optBoolean("enabled", cfg.dailyTotal.enabled)
                cfg.dailyTotal.limitMinutes = it.optInt("limitMinutes", cfg.dailyTotal.limitMinutes)
            }
            o.optJSONObject("session")?.let {
                cfg.session.enabled = it.optBoolean("enabled", cfg.session.enabled)
                cfg.session.continuousMinutes = it.optInt("continuousMinutes", cfg.session.continuousMinutes)
                cfg.session.restMinutes = it.optInt("restMinutes", cfg.session.restMinutes)
            }
            o.optJSONObject("whitelist")?.let {
                cfg.whitelist.enabled = it.optBoolean("enabled", cfg.whitelist.enabled)
                it.optJSONArray("packages")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val p = arr.optString(i, "")
                        if (p.isNotEmpty()) cfg.whitelist.packages.add(p)
                    }
                }
            }
            o.optJSONArray("appLimits")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    cfg.appLimits.add(
                        AppLimitRule(
                            id = e.optString("id", UUID.randomUUID().toString()),
                            packageName = e.optString("packageName", ""),
                            appLabel = e.optString("appLabel", ""),
                            dailyMinutes = e.optInt("dailyMinutes", 30),
                            enabled = e.optBoolean("enabled", true)
                        )
                    )
                }
            }
            return cfg
        }
    }
}
