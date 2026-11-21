package com.parentalguard.app.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * @brief 内置离线中文语音播报：直接播放随 APK 打包的真人语音 mp3
 *
 * 旧实现依赖系统 TextToSpeech 引擎，设备上没有引擎 / 没有中文语音包时完全哑火，
 * 且异步初始化存在竞态（回调早于实例赋值会永久停在「检测中」）。
 * 现在改为 res/raw 中的固定短句音频，装上即可朗读：不联网、不需要任何语音引擎、
 * 不需要额外权限；播放时若媒体音量过低会临时调高，播完自动恢复。
 */
object VoicePrompt {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前正在播放的实例，所有操作都主线程串行执行 */
    private var player: MediaPlayer? = null

    /** 为让提示被听见而临时改动前的媒体音量，-1 表示未改动 */
    private var savedVolume = -1
    private var audio: AudioManager? = null

    /** @brief 播放一段内置语音；同一时刻只保留最后一条，避免多事件叠加成噪音 */
    fun play(ctx: Context, resId: Int) {
        if (resId == 0) return
        val app = ctx.applicationContext
        mainHandler.post { doPlay(app, resId) }
    }

    /** @brief 当前媒体音量描述，供家长界面排查「听不见」问题 */
    fun volumeText(ctx: Context): String = try {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            "媒体音量：无法读取"
        } else {
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val low = max > 0 && cur * 2 < max
            "媒体音量：$cur / $max" + if (low) "（偏低，播报时会自动临时调高）" else ""
        }
    } catch (e: Exception) {
        "媒体音量：无法读取"
    }

    /** @brief 停止当前播报并恢复音量，供设置页退出时使用 */
    fun stop() {
        mainHandler.post { finish() }
    }

    private fun doPlay(app: Context, resId: Int) {
        // 先收尾上一条：恢复音量后再重新判定，避免把「调高后的音量」误记为原值
        finish()
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ensureAudible(am)
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val mp = MediaPlayer.create(app, resId, attrs, 0)
            if (mp == null) {
                finish()
                return
            }
            player = mp
            mp.setOnCompletionListener { finish() }
            mp.setOnErrorListener { _, _, _ ->
                finish()
                true
            }
            mp.start()
        } catch (e: Exception) {
            // 音频焦点被抢占 / 设备处于勿扰等情形下静默降级，不影响限制执行
            finish()
        }
    }

    /** @brief 媒体音量低于 60% 时临时提高，保证孩子听得见 */
    private fun ensureAudible(am: AudioManager?) {
        if (am == null) return
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = ((max * 6) / 10).coerceAtLeast(1)
            if (cur < target) {
                savedVolume = cur
                audio = am
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
        } catch (e: Exception) {
            // 勿扰模式或厂商限制下不允许改音量，保持原样继续播报
            savedVolume = -1
            audio = null
        }
    }

    /** @brief 释放播放器并把媒体音量还原 */
    private fun finish() {
        if (savedVolume >= 0) {
            try {
                audio?.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
            } catch (e: Exception) {
                // 还原失败不再重试，避免反复写系统设置
            }
            savedVolume = -1
            audio = null
        }
        player?.let { p ->
            player = null
            try {
                if (p.isPlaying) p.stop()
            } catch (e: Exception) {
                // 播放器已完成时 stop 会抛异常，忽略
            }
            try {
                p.release()
            } catch (e: Exception) {
                // 释放失败不影响后续播放
            }
        }
    }
}
