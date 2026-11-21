package com.parentalguard.app.core

import android.content.Context
import com.parentalguard.app.R
import com.parentalguard.app.model.RulesConfig

/**
 * @brief 面向儿童的提示编排：大图标 + 颜色 + 内置中文语音
 *
 * 全部提示都不依赖识字：孩子看到红色禁止符号 / 蓝色月亮和超大数字，
 * 同时听到随应用内置的真人中文语音；文字只作为家长侧的辅助说明。
 * 语音资源随 APK 打包，不依赖设备语音引擎。声音与图形均可由家长单独开关，
 * 开关不影响限制本身的执行。
 */
object KidPrompt {

    enum class Event {
        BLOCK_WHITELIST, BLOCK_APP_LIMIT, TIME_WINDOW,
        REST_START, RESTING, REST_END, DAILY_TOTAL, WARNING, TEST
    }

    /** @brief 一次提示所需的全部视觉与听觉素材 */
    data class Cue(
        val kidTitle: String,
        val iconRes: Int,
        val color: Int,
        /** 卡片副标题 / 家长辅助说明，同时是内置语音的文案 */
        val speech: String,
        /** res/raw 中的真人语音音频；0 表示该事件不朗读 */
        val voiceRes: Int,
        val sound: SoundAlert.Kind,
        val showBanner: Boolean
    )

    private const val THROTTLE_MS = 2500L

    @Volatile
    private var lastKey = ""

    @Volatile
    private var lastAt = 0L

    private val RED = 0xFFD64545L.toInt()
    private val ORANGE = 0xFFDE7C1BL.toInt()
    private val BLUE = 0xFF2D6AE0L.toInt()
    private val GREEN = 0xFF2E9E6BL.toInt()
    private val PURPLE = 0xFF7C53C8L.toInt()

    /** @brief 事件到「儿童短句 / 图标 / 颜色 / 内置语音」的映射 */
    fun cueOf(e: Event): Cue = when (e) {
        Event.BLOCK_WHITELIST -> Cue(
            "不能玩哦", R.drawable.kid_stop, RED,
            "这个应用不能玩哦，已经帮你回到桌面啦", R.raw.voice_block_whitelist,
            SoundAlert.Kind.BLOCK, true
        )
        Event.BLOCK_APP_LIMIT -> Cue(
            "今天玩够啦", R.drawable.kid_stop, ORANGE,
            "这个应用今天玩够啦，明天再玩吧", R.raw.voice_block_limit,
            SoundAlert.Kind.BLOCK, true
        )
        Event.REST_START -> Cue(
            "该休息啦", R.drawable.kid_rest, BLUE,
            "该休息啦，让眼睛歇一歇，休息好了再玩", R.raw.voice_rest_start,
            SoundAlert.Kind.REST_START, false
        )
        Event.RESTING -> Cue(
            "还在休息", R.drawable.kid_rest, BLUE,
            "还在休息哦，等倒计时结束再玩", R.raw.voice_resting,
            SoundAlert.Kind.LOCKED, false
        )
        Event.REST_END -> Cue(
            "可以玩啦", R.drawable.kid_ok, GREEN,
            "休息好啦，可以再玩一会儿", R.raw.voice_rest_end,
            SoundAlert.Kind.REST_END, true
        )
        Event.TIME_WINDOW -> Cue(
            "现在不能用", R.drawable.kid_clock, PURPLE,
            "现在是禁止使用的时间段，已经帮你回到桌面啦", R.raw.voice_block_whitelist,
            SoundAlert.Kind.LOCKED, false
        )
        Event.DAILY_TOTAL -> Cue(
            "今天用完啦", R.drawable.kid_clock, PURPLE,
            "今天的时间用完啦，明天再玩哦", R.raw.voice_daily_total,
            SoundAlert.Kind.LOCKED, false
        )
        Event.WARNING -> Cue(
            "快到时间啦", R.drawable.kid_clock, ORANGE,
            "还有一分钟就要休息啦，准备一下哦", R.raw.voice_warning,
            SoundAlert.Kind.WARNING, false
        )
        Event.TEST -> Cue(
            "能听见吗", R.drawable.kid_ok, BLUE,
            "这是提示音测试，小朋友听得见吗", R.raw.voice_test,
            SoundAlert.Kind.TEST, true
        )
    }

    /**
     * @brief 发出一次提示；相同 key 在 2.5 秒内不重复，避免窗口事件与心跳重复播报
     *
     * @param key 去重键，同一场景（如休息开始 / 休息中）请传入相同值
     */
    fun fire(ctx: Context, cfg: RulesConfig, e: Event, key: String = e.name) {
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastAt < THROTTLE_MS) return
        lastKey = key
        lastAt = now

        val cue = cueOf(e)
        if (cue.showBanner && cfg.visualPromptEnabled) KidBanner.show(ctx, cue)

        val isBlock = e == Event.BLOCK_WHITELIST || e == Event.BLOCK_APP_LIMIT
        val audible = cfg.soundEnabled && (!isBlock || cfg.blockSoundEnabled)
        if (!audible) return

        // 人声与提示音二选一，避免两者叠在一起变成噪音；振动始终保留
        val useVoice = cfg.voiceEnabled && cue.voiceRes != 0
        SoundAlert.play(ctx, cue.sound, cfg, playTone = !useVoice)
        if (useVoice) VoicePrompt.play(ctx, cue.voiceRes)
    }

    /** @brief 家长设置中的「试听提示」，不受节流影响 */
    fun test(ctx: Context, cfg: RulesConfig) {
        lastKey = ""
        lastAt = 0L
        fire(ctx, cfg, Event.TEST, "test_" + System.currentTimeMillis())
    }

    /**
     * @brief 时间重置时清空提示节流
     *
     * 重置前最后一次「时间用完 / 休息中」的播报会占用去重键 2.5 秒，
     * 若不清除，重置后短时间内再次触发同类提示会被误判为重复而静默。
     */
    fun clearThrottle() {
        lastKey = ""
        lastAt = 0L
    }
}
