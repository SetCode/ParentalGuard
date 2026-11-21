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

/**
 * @brief 单纯的应用集合规则（休息放行 / 免计时）
 *
 * 没有独立开关：集合里有哪些应用，就对这些应用生效；管控自身始终由
 * [RulesConfig.ensureSelf] 强制加入，家长无法移除。
 */
data class AppPackageSet(
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
    /** 强制休息期间仍可玩的名单：命中即豁免「强制休息」这一条规则 */
    var restWhitelist: AppPackageSet = AppPackageSet(),
    /** 免计时名单：这些应用在前台时不累计任何时长（每日总量、连续时长、单应用时长） */
    var noCountWhitelist: AppPackageSet = AppPackageSet(),
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
    /**
     * @brief 三类名单都强制包含管控自身，家长无法移除
     *
     * 白名单关闭时本应用本来就放行，但一旦家长开启白名单、或把休息放行/免计时名单删空，
     * 就存在「把自己锁在设置页之外」的风险；这里在配置加载与写入两处都做强制补齐。
     */
    fun ensureSelf(selfPackage: String) {
        if (selfPackage.isEmpty()) return
        whitelist.packages.add(selfPackage)
        restWhitelist.packages.add(selfPackage)
        noCountWhitelist.packages.add(selfPackage)
    }

    /** @brief 强制休息期间是否允许该应用继续使用（只豁免休息这一条规则） */
    fun restAllows(pkg: String): Boolean = restWhitelist.packages.contains(pkg)

    /** @brief 该应用在前台时是否计入使用时长 */
    fun countsUsage(pkg: String): Boolean = !noCountWhitelist.packages.contains(pkg)

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
        put("restWhitelist", JSONObject().apply {
            put("packages", JSONArray().also { arr -> restWhitelist.packages.forEach { arr.put(it) } })
        })
        put("noCountWhitelist", JSONObject().apply {
            put("packages", JSONArray().also { arr -> noCountWhitelist.packages.forEach { arr.put(it) } })
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

    /** @brief 生成局域网共享用的配置快照：剔除「仅本机生效」的字段 */
    fun toShareJson(): JSONObject = shareSnapshot(toJson())

    /** @brief 把「仅本机生效」的字段从本机配置复制到目标配置 */
    fun copyLocalOnlyTo(target: RulesConfig) {
        target.masterEnabled = masterEnabled
        target.whitelist.enabled = whitelist.enabled
        target.whitelist.packages.clear()
        target.whitelist.packages.addAll(whitelist.packages)
        target.restWhitelist.packages.clear()
        target.restWhitelist.packages.addAll(restWhitelist.packages)
        target.noCountWhitelist.packages.clear()
        target.noCountWhitelist.packages.addAll(noCountWhitelist.packages)
    }

    companion object {
        const val SCHEMA = 1

        /** 仅本机生效、不参与局域网共享的字段：总开关 + 三类名单（各设备可装应用不同） */
        val LOCAL_ONLY_KEYS = arrayOf("masterEnabled", "whitelist", "restWhitelist", "noCountWhitelist")

        private const val SHARE_MARK = "localFieldsOmitted"

        /** @brief 从任意配置 JSON 生成共享快照，用于上行上报与旧数据集归一化 */
        fun shareSnapshot(full: JSONObject): JSONObject {
            val o = JSONObject(full.toString())
            LOCAL_ONLY_KEYS.forEach { o.remove(it) }
            o.put(SHARE_MARK, true)
            return o
        }

        /** @brief 剥离历史共享数据集里残留的仅本机字段，返回可直接存库的规范化文本 */
        fun normalizeShareJson(json: String): String {
            if (json.isBlank()) return ""
            return try {
                val o = JSONObject(json)
                if (o.optBoolean(SHARE_MARK, false)) json else shareSnapshot(o).toString()
            } catch (e: Exception) {
                ""
            }
        }

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
            // 旧配置没有这两个名单时保持为空，由 ensureSelf 补上管控自身
            o.optJSONObject("restWhitelist")?.optJSONArray("packages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i, "")
                    if (p.isNotEmpty()) cfg.restWhitelist.packages.add(p)
                }
            }
            o.optJSONObject("noCountWhitelist")?.optJSONArray("packages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i, "")
                    if (p.isNotEmpty()) cfg.noCountWhitelist.packages.add(p)
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
