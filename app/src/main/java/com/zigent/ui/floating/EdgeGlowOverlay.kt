package com.zigent.ui.floating

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.withSave

/**
 * 屏幕边缘淡淡的呼吸流动光效
 */
class EdgeGlowOverlay(context: Context) : View(context) {

    companion object {
        private const val ANIMATION_DURATION = 2600L
        private const val EDGE_THICKNESS_DP = 18f
    }

    private val density = resources.displayMetrics.density
    private val edgeThickness = EDGE_THICKNESS_DP * density
    private var animator: ValueAnimator? = null
    private var progress = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var windowManager: WindowManager? = null
    var layoutParams: WindowManager.LayoutParams? = null

    fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also { layoutParams = it }
    }

    fun attachToWindow(wm: WindowManager) {
        windowManager = wm
    }

    fun start() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0.85f, 1.05f, 0.85f).apply {
            duration = ANIMATION_DURATION
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 透明度随呼吸变化
        val alpha = (50 * progress).toInt().coerceIn(15, 70)
        paint.alpha = alpha

        // 顶部
        paint.shader = LinearGradient(
            0f, 0f, 0f, edgeThickness,
            intArrayOf(0x00FFFFFF, 0x55FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, edgeThickness, paint)

        // 底部
        canvas.withSave {
            rotate(180f, w / 2f, h / 2f)
            canvas.drawRect(0f, 0f, w, edgeThickness, paint)
        }

        // 左侧
        paint.shader = LinearGradient(
            0f, 0f, edgeThickness, 0f,
            intArrayOf(0x00FFFFFF, 0x55FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, edgeThickness, h, paint)

        // 右侧
        canvas.withSave {
            rotate(180f, w / 2f, h / 2f)
            canvas.drawRect(0f, 0f, edgeThickness, h, paint)
        }
    }
}

