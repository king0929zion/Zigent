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
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.zigent.R
import com.zigent.utils.Logger
import kotlin.math.min

/**
 * 悬浮文字面板
 * 显示语音识别结果、AI响应等状态信息
 */
class FloatingTextPanel(context: Context) : View(context) {

    companion object {
        private const val TAG = "FloatingTextPanel"
        
        // 面板尺寸（基准，实际按屏幕宽度 92% 自适应）
        const val PANEL_WIDTH = 340  // dp
        const val PANEL_HEIGHT = 180 // dp
        const val PANEL_MARGIN = 16  // dp
        const val CORNER_RADIUS = 20f // dp（更圆润）
        const val PADDING = 20 // dp（更大的内边距）
        const val LINE_SPACING = 6 // dp（行间距）
        
        // 动画时长
        private const val ANIMATION_DURATION = 200L
    }

    // 尺寸（像素）
    private val density = resources.displayMetrics.density
    private val screenWidthPx = resources.displayMetrics.widthPixels
    private val screenHeightPx = resources.displayMetrics.heightPixels
    private val panelWidthPx = min(
        (PANEL_WIDTH * density).toInt(),
        (screenWidthPx * 0.92f).toInt()
    )
    private val panelHeightPx = (PANEL_HEIGHT * density).toInt()
    private val panelMarginPx = (PANEL_MARGIN * density).toInt()
    private val cornerRadiusPx = CORNER_RADIUS * density
    private val paddingPx = (PADDING * density).toInt()
    private val lineSpacingPx = (LINE_SPACING * density)
    
    // 画笔
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.panel_background)
        setShadowLayer(12 * density, 0f, 6 * density, 0x55000000)
    }
    
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.panel_title)
        textSize = 17 * density  // 增大：14 -> 17
        isFakeBoldText = true
    }
    
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.panel_text)
        textSize = 18 * density  // 增大：16 -> 18
    }
    
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.panel_hint)
        textSize = 15 * density  // 增大：13 -> 15
    }
    
    private enum class PanelMode {
        INFO, QUESTION, STATUS
    }
    
    // 状态
    private var currentTitle = "语音输入"
    private var currentText = ""
    private var currentHint = "请开始说话..."
    private var mode: PanelMode = PanelMode.STATUS
    private var isVisible = false
    
    // 窗口管理
    private var windowManager: WindowManager? = null
    var layoutParams: WindowManager.LayoutParams? = null
    
    // 动画
    private var showAnimator: ValueAnimator? = null
    private var currentAlpha = 0f
    private val slideDistance = 14 * density

    init {
        // 设置初始透明
        alpha = 0f
        translationY = slideDistance
        scaleX = 0.96f
        scaleY = 0.96f
        // 阴影需要软件层
        setLayerType(LAYER_TYPE_SOFTWARE, backgroundPaint)
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
            WindowManager.LayoutParams.WRAP_CONTENT,
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
    fun show(title: String = "语音输入", hint: String = "请开始说话...", mode: PanelMode = PanelMode.INFO) {
        if (isVisible) return
        
        currentTitle = title
        currentText = ""
        currentHint = hint
        this.mode = mode
        isVisible = true
        
        Logger.d("Showing text panel: $title", TAG)
        
        // 显示动画
        showAnimator?.cancel()
        showAnimator = ValueAnimator.ofFloat(currentAlpha, 1f).apply {
            duration = ANIMATION_DURATION
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { animator ->
                currentAlpha = animator.animatedValue as Float
                alpha = currentAlpha
                translationY = slideDistance * (1f - currentAlpha)
                val scale = 0.96f + 0.04f * currentAlpha
                scaleX = scale
                scaleY = scale
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
                translationY = slideDistance * (1f - currentAlpha)
                val scale = 0.96f + 0.04f * currentAlpha
                scaleX = scale
                scaleY = scale
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
    fun updateQuestion(question: String) {
        mode = PanelMode.QUESTION
        currentTitle = "请确认"
        currentText = question
        currentHint = ""
        invalidate()
    }

    fun updateStatus(title: String, hint: String = "") {
        mode = PanelMode.STATUS
        currentTitle = title
        currentText = ""
        currentHint = hint
        invalidate()
    }

    fun updateInfo(text: String) {
        mode = PanelMode.INFO
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
        mode = PanelMode.STATUS
        currentTitle = "正在聆听"
        currentText = ""
        currentHint = "说完后点击悬浮球结束"
        invalidate()
    }

    /**
     * 设置为处理状态
     */
    fun setProcessingMode() {
        mode = PanelMode.STATUS
        currentTitle = "AI 处理中"
        currentText = ""
        currentHint = "请稍候..."
        invalidate()
    }

    /**
     * 设置为执行状态
     */
    fun setExecutingMode() {
        mode = PanelMode.STATUS
        currentTitle = "执行中"
        currentText = ""
        currentHint = "AI 正在操作"
        invalidate()
    }

    /**
     * 设置为完成状态
     */
    fun setCompletedMode(result: String) {
        mode = PanelMode.STATUS
        currentTitle = "完成"
        currentText = result
        currentHint = ""
        invalidate()
    }

    /**
     * 设置为错误状态
     */
    fun setErrorMode(error: String) {
        mode = PanelMode.STATUS
        currentTitle = "出错了"
        currentText = error
        currentHint = "点击悬浮球重试"
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val titleHeight = titlePaint.textSize
        val hintHeight = if (currentHint.isNotEmpty()) hintPaint.textSize + paddingPx * 0.5f else 0f
        val maxContentWidth = (panelWidthPx - paddingPx * 2).toFloat()
        val lineHeight = textPaint.textSize + lineSpacingPx
        val lines = if (currentText.isNotEmpty()) wrapText(currentText, maxContentWidth, textPaint) else emptyList()
        val contentLines = if (lines.isEmpty() && currentText.isNotEmpty()) 1 else lines.size
        val contentHeight = if (contentLines > 0) {
            contentLines * lineHeight + paddingPx * 0.6f
        } else 0f
        val desiredHeight = (paddingPx * 2) + titleHeight + contentHeight + hintHeight
        val maxHeight = (screenHeightPx * 0.4f).toInt()
        val minHeight = (140 * density).toInt()
        val finalHeight = desiredHeight.toInt().coerceIn(minHeight, maxHeight)
        setMeasuredDimension(panelWidthPx, finalHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // 绘制背景
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, backgroundPaint)
        
        // 绘制标题
        val titleY = paddingPx + titlePaint.textSize
        canvas.drawText(currentTitle, paddingPx.toFloat(), titleY, titlePaint)
        
        // 绘制主文字（智能多行换行）
        if (currentText.isNotEmpty()) {
            val maxWidth = (width - paddingPx * 2).toFloat()
            val lineSpacingPx = (LINE_SPACING * density)
            val lineHeight = textPaint.textSize + lineSpacingPx
            var textY = titleY + paddingPx * 0.8f + textPaint.textSize
            
            // 智能换行：按可用宽度分割文字
            val lines = wrapText(currentText, maxWidth, textPaint)
            
            // 计算最多可以显示几行（保留底部提示空间）
            val availableHeight = height - textY - paddingPx - hintPaint.textSize - paddingPx * 0.5f
            val maxLines = (availableHeight / lineHeight).toInt().coerceAtLeast(1)
            
            // 绘制文字行
            lines.take(maxLines).forEachIndexed { index, line ->
                val displayLine = if (index == maxLines - 1 && lines.size > maxLines) {
                    // 最后一行如果文字被截断，添加省略号
                    val ellipsis = "..."
                    val ellipsisWidth = textPaint.measureText(ellipsis)
                    var truncated = line
                    while (textPaint.measureText(truncated) + ellipsisWidth > maxWidth && truncated.isNotEmpty()) {
                        truncated = truncated.dropLast(1)
                    }
                    truncated + ellipsis
                } else {
                    line
                }
                
                canvas.drawText(displayLine, paddingPx.toFloat(), textY, textPaint)
                textY += lineHeight
            }
        }
        
        // 绘制提示文字（居中底部）
        if (currentHint.isNotEmpty()) {
            val hintY = height - paddingPx.toFloat()
            canvas.drawText(currentHint, paddingPx.toFloat(), hintY, hintPaint)
        }
    }
    
    /**
     * 智能文字换行：根据可用宽度将文字分割成多行
     */
    private fun wrapText(text: String, maxWidth: Float, paint: TextPaint): List<String> {
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()
        
        // 按字符遍历
        for (char in text) {
            val testLine = currentLine.toString() + char
            val testWidth = paint.measureText(testLine)
            
            if (testWidth > maxWidth && currentLine.isNotEmpty()) {
                // 当前行已满，开始新行
                lines.add(currentLine.toString())
                currentLine = StringBuilder(char.toString())
            } else {
                currentLine.append(char)
            }
        }
        
        // 添加最后一行
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
        
        return lines
    }

    /**
     * 释放资源
     */
    fun release() {
        showAnimator?.cancel()
        showAnimator = null
    }
}

