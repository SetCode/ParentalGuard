package com.tensun.parentalguard.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.tensun.parentalguard.R
import com.tensun.parentalguard.core.ForegroundTracker
import com.tensun.parentalguard.core.StateBus
import com.tensun.parentalguard.ui.MainActivity

/**
 * @brief 锁定遮罩展示内容
 *
 * kidTitle / iconRes / accentColor 面向儿童（大图标、短句、颜色、超大数字），
 * title / reasons / extra 为家长可见的辅助文字说明。
 */
data class LockUi(
    val title: String,
    val kidTitle: String,
    val iconRes: Int,
    val accentColor: Int,
    val reasons: List<String>,
    val countdown: String,
    val countdownLabel: String,
    val extra: String,
    val progress: Int,
    val showHome: Boolean,
    val showCountdown: Boolean
)

/** @brief 基于 TYPE_APPLICATION_OVERLAY 的全屏限制遮罩 */
class LockOverlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var attached = false

    private var ivIcon: ImageView? = null
    private var tvKidTitle: TextView? = null
    private var tvTitle: TextView? = null
    private var tvReason: TextView? = null
    private var tvCountdownLabel: TextView? = null
    private var tvCountdown: TextView? = null
    private var tvExtra: TextView? = null
    private var progress: ProgressBar? = null
    private var btnHome: Button? = null

    fun show(ui: LockUi) {
        if (!Settings.canDrawOverlays(ctx)) {
            StateBus.overlayError = "未授予悬浮窗权限，无法展示锁定遮罩"
            return
        }
        if (!attached) {
            if (!attach()) return
        }
        ivIcon?.setImageResource(ui.iconRes)
        tvKidTitle?.text = ui.kidTitle
        tvKidTitle?.setTextColor(ui.accentColor)
        tvTitle?.text = ui.title
        tvReason?.text = ui.reasons.joinToString("\n") { "• $it" }
        tvCountdownLabel?.text = ui.countdownLabel
        tvCountdown?.text = ui.countdown
        tvCountdown?.setTextColor(ui.accentColor)
        val cdVisibility = if (ui.showCountdown) View.VISIBLE else View.GONE
        tvCountdownLabel?.visibility = cdVisibility
        tvCountdown?.visibility = cdVisibility
        tvExtra?.text = ui.extra
        progress?.progress = ui.progress
        progress?.visibility = if (ui.showCountdown && ui.progress > 0) View.VISIBLE else View.GONE
        btnHome?.visibility = if (ui.showHome) View.VISIBLE else View.GONE
    }

    private fun attach(): Boolean {
        return try {
            val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_lock, null)
            v.isFocusableInTouchMode = true
            v.requestFocus()
            @Suppress("DEPRECATION")
            v.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            // 吞掉返回键，避免儿童直接退出遮罩
            v.setOnKeyListener { _, keyCode, _ ->
                keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU
            }

            ivIcon = v.findViewById(R.id.overlayIcon)
            tvKidTitle = v.findViewById(R.id.overlayKidTitle)
            tvTitle = v.findViewById(R.id.overlayTitle)
            tvReason = v.findViewById(R.id.overlayReason)
            tvCountdownLabel = v.findViewById(R.id.overlayCountdownLabel)
            tvCountdown = v.findViewById(R.id.overlayCountdown)
            tvExtra = v.findViewById(R.id.overlayExtra)
            progress = v.findViewById(R.id.overlayProgress)
            btnHome = v.findViewById(R.id.btnGoHome)

            // 图标统一放在深色半透明圆底上，白线条图形在任何底色上都清晰
            ivIcon?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33000000)
            }

            btnHome?.setOnClickListener { goHome() }
            v.findViewById<Button>(R.id.btnParentEntry).setOnClickListener {
                // 先标记前台为家长界面，避免心跳在界面切换间隙重新弹出遮罩；
                // MainActivity 自身有密码门，儿童无法通过此入口修改配置。
                ForegroundTracker.update(ctx.packageName, "com.tensun.parentalguard.ui.MainActivity")
                hide()
                openParentSettings()
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                dimAmount = 0.92f
            }
            wm.addView(v, lp)
            root = v
            attached = true
            StateBus.overlayError = ""
            true
        } catch (e: Exception) {
            StateBus.overlayError = "锁定遮罩创建失败：${e.message}"
            false
        }
    }

    private fun goHome() {
        if (!GuardAccessibilityService.goHome()) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // 无无障碍权限时由系统的返回桌面按钮兜底
            }
        }
    }

    private fun openParentSettings() {
        try {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            )
        } catch (e: Exception) {
            StateBus.overlayError = "打开家长设置失败：${e.message}"
        }
    }

    fun hide() {
        if (!attached) return
        try {
            root?.let { wm.removeViewImmediate(it) }
        } catch (e: Exception) {
            // 视图可能已被系统移除
        } finally {
            root = null
            attached = false
            ivIcon = null
            tvKidTitle = null
            tvTitle = null
            tvReason = null
            tvCountdownLabel = null
            tvCountdown = null
            tvExtra = null
            progress = null
            btnHome = null
        }
    }
}
