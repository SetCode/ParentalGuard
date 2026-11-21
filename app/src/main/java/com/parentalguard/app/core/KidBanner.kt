package com.parentalguard.app.core

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.parentalguard.app.R

/**
 * @brief 短时图形提示浮层：大图标 + 大字短句 + 鲜艳底色，约 2.4 秒自动消失
 *
 * 使用不可触摸、不抢焦点的悬浮窗，因此不会阻挡孩子操作桌面；没有悬浮窗权限时
 * 降级为系统 Toast，同时仍可通过语音播报让孩子听懂。
 */
object KidBanner {
    private const val DURATION_MS = 2400L
    private val handler = Handler(Looper.getMainLooper())
    private val dismiss = Runnable { hideOnMain() }

    private var attached = false
    private var root: View? = null
    private var wm: WindowManager? = null

    fun show(ctx: Context, cue: KidPrompt.Cue) {
        val app = ctx.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            handler.post {
                try {
                    Toast.makeText(app, cue.kidTitle, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    // 忽略系统 Toast 异常
                }
            }
            return
        }
        handler.removeCallbacks(dismiss)
        handler.post { showOnMain(app, cue) }
    }

    private fun showOnMain(app: Context, cue: KidPrompt.Cue) {
        hideOnMain()
        try {
            val v = LayoutInflater.from(app).inflate(R.layout.overlay_kid_banner, null)
            val card = v.findViewById<LinearLayout>(R.id.kidCard)
            val icon = v.findViewById<ImageView>(R.id.kidIcon)

            val density = app.resources.displayMetrics.density
            card.background = GradientDrawable().apply {
                setColor(cue.color)
                cornerRadius = 28f * density
            }
            icon.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33000000)
            }
            icon.setImageResource(cue.iconRes)
            v.findViewById<TextView>(R.id.kidTitle).text = cue.kidTitle
            v.findViewById<TextView>(R.id.kidSub).text = cue.speech

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }

            val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            w.addView(v, lp)
            wm = w
            root = v
            attached = true
            handler.postDelayed(dismiss, DURATION_MS)
        } catch (e: Exception) {
            try {
                Toast.makeText(app, cue.kidTitle, Toast.LENGTH_SHORT).show()
            } catch (e2: Exception) {
                // 静默降级，限制逻辑不受影响
            }
        }
    }

    private fun hideOnMain() {
        if (!attached) return
        try {
            root?.let { wm?.removeViewImmediate(it) }
        } catch (e: Exception) {
            // 视图可能已被系统移除
        } finally {
            root = null
            attached = false
        }
    }

    fun hide() {
        handler.removeCallbacks(dismiss)
        handler.post { hideOnMain() }
    }
}
