package com.parentalguard.app.core

import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.parentalguard.app.model.RulesConfig

/** @brief 到点 / 锁定 / 提醒的声音与振动提示 */
object SoundAlert {
    enum class Kind { WARNING, LOCKED, REST_START, REST_END, BLOCK, TEST }

    /**
     * @brief 播放提示音并振动
     * @param playTone 为 false 时只振动（与人声语音互斥，避免声音叠加）
     */
    fun play(ctx: Context, kind: Kind, cfg: RulesConfig, playTone: Boolean = true) {
        if (!cfg.soundEnabled) return
        try {
            if (playTone) {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val ringtone = RingtoneManager.getRingtone(ctx.applicationContext, uri)
                if (ringtone != null) {
                    if (Build.VERSION.SDK_INT >= 28) ringtone.isLooping = false
                    if (!ringtone.isPlaying) ringtone.play()
                }
            }
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                val duration = when (kind) {
                    Kind.WARNING, Kind.BLOCK -> 180L
                    Kind.TEST -> 120L
                    else -> 350L
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    vib.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(duration)
                }
            }
        } catch (e: Exception) {
            // 无声音权限 / 静音模式下静默失败，不影响限制逻辑
        }
    }
}
