package com.zigent.ui.floating

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.zigent.R
import com.zigent.utils.Logger

/**
 * 悬浮文字面板
 * 显示语音识别结果、AI响应等状态信息
 */
class FloatingTextPanel(context: Context) : View(context) {

    companion object {
        private const val TAG = "FloatingTextPanel"
        
        // 面板尺寸
        const val PANEL_WIDTH = 280  // dp
        const val PANEL_HEIGHT = 120 // dp
        const val PANEL_MARGIN = 16  // dp
        const val CORNER_RADIUS = 16f // dp
        const val PADDING = 16 // dp
        
        // 动画时长
        private const val ANIMATION_DURATION = 200L
    }

    // 尺寸（像素）
    private val density = resources.displayMetrics.density
    private val panelWidthPx = (PANEL_WIDTH * density).toInt()
    private val panelHeightPx = (PANEL_HEIGHT * density).toInt()
    private val panelMarginPx = (PANEL_MARGIN * density).toInt()
    private val cornerRadiusPx = CORNER_RADIUS * density
    private val paddingPx = (PADDING * density).toInt()
    
    // 画笔
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.panel_background)
    }
    
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.panel_title)
        textSize = 14 * density
        isFakeBoldText = true
    }
    
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.panel_text)
        textSize = 16 * density
    }
    
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.panel_hint)
        textSize = 13 * density
    }
    
    // 状态
    private var currentTitle = "语音输入"
    private var currentText = ""
    private var currentHint = "请开始说话..."
    private var isVisible = false
    
    // 窗口管理
    private var windowManager: WindowManager? = null
    var layoutParams: WindowManager.LayoutParams? = null
    
    // 动画
    private var showAnimator: ValueAnimator? = null
    private var currentAlpha = 0f
    
    init {
        // 设置初始透明
        alpha = 0f
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * 创建布局参数
     */
    fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        return WindowManager.LayoutParams(
            panelWidthPx,
            panelHeightPx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            y = panelMarginPx + 100 // 距离底部一定距离
        }.also {
            layoutParams = it
        }
    }

    /**
     * 绑定窗口管理器
     */
    fun attachToWindow(wm: WindowManager) {
        windowManager = wm
    }

    /**
     * 显示面板
     */
    fun show(title: String = "语音输入", hint: String = "请开始说话...") {
        if (isVisible) return
        
        currentTitle = title
        currentText = ""
        currentHint = hint
        isVisible = true
        
        Logger.d("Showing text panel: $title", TAG)
        
        // 显示动画
        showAnimator?.cancel()
        showAnimator = ValueAnimator.ofFloat(currentAlpha, 1f).apply {
            duration = ANIMATION_DURATION
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                currentAlpha = animator.animatedValue as Float
                alpha = currentAlpha
            }
            start()
        }
        
        invalidate()
    }

    /**
     * 隐藏面板
     */
    fun hide() {
        if (!isVisible) return
        
        Logger.d("Hiding text panel", TAG)
        
        showAnimator?.cancel()
        showAnimator = ValueAnimator.ofFloat(currentAlpha, 0f).apply {
            duration = ANIMATION_DURATION
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                currentAlpha = animator.animatedValue as Float
                alpha = currentAlpha
                if (currentAlpha == 0f) {
                    isVisible = false
                }
            }
            start()
        }
    }

    /**
     * 更新显示文字
     */
    fun updateText(text: String) {
        currentText = text
        invalidate()
    }

    /**
     * 更新标题
     */
    fun updateTitle(title: String) {
        currentTitle = title
        invalidate()
    }

    /**
     * 更新提示
     */
    fun updateHint(hint: String) {
        currentHint = hint
        invalidate()
    }

    /**
     * 设置为监听状态
     */
    fun setListeningMode() {
        currentTitle = "🎙️ 正在聆听"
        currentHint = "说完后点击悬浮球结束"
        invalidate()
    }

    /**
     * 设置为处理状态
     */
    fun setProcessingMode() {
        currentTitle = "🤖 AI处理中"
        currentHint = "请稍候..."
        invalidate()
    }

    /**
     * 设置为执行状态
     */
    fun setExecutingMode() {
        currentTitle = "⚡ 执行中"
        currentHint = "AI正在操作..."
        invalidate()
    }

    /**
     * 设置为完成状态
     */
    fun setCompletedMode(result: String) {
        currentTitle = "✅ 完成"
        currentText = result
        currentHint = ""
        invalidate()
    }

    /**
     * 设置为错误状态
     */
    fun setErrorMode(error: String) {
        currentTitle = "❌ 错误"
        currentText = error
        currentHint = "点击悬浮球重试"
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(panelWidthPx, panelHeightPx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // 绘制背景
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, backgroundPaint)
        
        // 绘制标题
        val titleY = paddingPx + titlePaint.textSize
        canvas.drawText(currentTitle, paddingPx.toFloat(), titleY, titlePaint)
        
        // 绘制主文字（支持多行）
        if (currentText.isNotEmpty()) {
            val textY = titleY + paddingPx + textPaint.textSize
            val maxWidth = width - paddingPx * 2
            
            // 简单的文字截断
            val displayText = if (textPaint.measureText(currentText) > maxWidth * 2) {
                val endIndex = currentText.length.coerceAtMost(50)
                currentText.substring(0, endIndex) + "..."
            } else {
                currentText
            }
            
            // 绘制第一行
            val firstLine = displayText.take(20)
            canvas.drawText(firstLine, paddingPx.toFloat(), textY, textPaint)
            
            // 如果有第二行
            if (displayText.length > 20) {
                val secondLine = displayText.drop(20).take(25)
                canvas.drawText(secondLine, paddingPx.toFloat(), textY + textPaint.textSize + 4 * density, textPaint)
            }
        }
        
        // 绘制提示文字
        if (currentHint.isNotEmpty()) {
            val hintY = height - paddingPx.toFloat()
            canvas.drawText(currentHint, paddingPx.toFloat(), hintY, hintPaint)
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        showAnimator?.cancel()
        showAnimator = null
    }
}

