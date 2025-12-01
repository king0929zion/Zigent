package com.zigent.ui.floating

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.zigent.R
import com.zigent.utils.Logger
import kotlin.math.abs

/**
 * 悬浮球视图
 * 实现类似iOS的悬浮球效果，支持拖拽、贴边、点击交互
 */
class FloatingBallView(context: Context) : View(context) {

    companion object {
        private const val TAG = "FloatingBallView"
        
        // 悬浮球尺寸
        const val BALL_SIZE = 56  // dp
        const val BALL_MARGIN = 8 // dp
        
        // 拖拽判定阈值
        private const val CLICK_THRESHOLD = 10
        
        // 贴边动画时长
        private const val STICK_ANIMATION_DURATION = 200L
        
        // 呼吸动画配置
        private const val BREATH_DURATION = 1500L
        private const val BREATH_SCALE_MIN = 0.9f
        private const val BREATH_SCALE_MAX = 1.1f
    }

    // 悬浮球尺寸（像素）
    private val ballSizePx: Int = (BALL_SIZE * resources.displayMetrics.density).toInt()
    private val ballMarginPx: Int = (BALL_MARGIN * resources.displayMetrics.density).toInt()
    
    // 画笔
    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        style = Paint.Style.FILL
    }
    
    // 颜色
    private val colorIdle = ContextCompat.getColor(context, R.color.floating_ball_default)
    private val colorListening = ContextCompat.getColor(context, R.color.floating_ball_listening)
    private val colorProcessing = ContextCompat.getColor(context, R.color.floating_ball_processing)
    private val colorExecuting = ContextCompat.getColor(context, R.color.floating_ball_executing)
    
    // 状态
    private var currentState: FloatingBallState = FloatingBallState.IDLE
    
    // 动画
    private var breathAnimator: ValueAnimator? = null
    private var ringAnimator: ValueAnimator? = null
    private var currentScale = 1f
    private var ringProgress = 0f
    
    // 触摸相关
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var initialX = 0
    private var initialY = 0
    private var isDragging = false
    
    // 窗口管理器和布局参数
    private var windowManager: WindowManager? = null
    var layoutParams: WindowManager.LayoutParams? = null
    
    // 回调
    var onClickListener: (() -> Unit)? = null
    
    init {
        // 设置视图尺寸
        minimumWidth = ballSizePx
        minimumHeight = ballSizePx
        
        // 开启硬件加速
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * 创建悬浮窗布局参数
     */
    fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        return WindowManager.LayoutParams(
            ballSizePx,
            ballSizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = resources.displayMetrics.heightPixels / 3
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
     * 更新悬浮球状态
     */
    fun setState(state: FloatingBallState) {
        if (currentState == state) return
        
        Logger.d("State changed: $currentState -> $state", TAG)
        currentState = state
        
        // 停止当前动画
        stopAllAnimations()
        
        // 更新颜色
        updateColors()
        
        // 根据状态启动对应动画
        when (state) {
            FloatingBallState.IDLE -> startBreathAnimation()
            FloatingBallState.LISTENING -> startPulseAnimation()
            FloatingBallState.PROCESSING -> startRingAnimation()
            FloatingBallState.EXECUTING -> startRingAnimation()
            FloatingBallState.SUCCESS -> showSuccessAnimation()
            FloatingBallState.ERROR -> showErrorAnimation()
        }
        
        invalidate()
    }

    /**
     * 更新颜色
     */
    private fun updateColors() {
        val color = when (currentState) {
            FloatingBallState.IDLE -> colorIdle
            FloatingBallState.LISTENING -> colorListening
            FloatingBallState.PROCESSING -> colorProcessing
            FloatingBallState.EXECUTING -> colorExecuting
            FloatingBallState.SUCCESS -> colorExecuting
            FloatingBallState.ERROR -> colorListening
        }
        mainPaint.color = color
        ringPaint.color = color
    }

    /**
     * 启动呼吸动画（空闲状态）
     */
    private fun startBreathAnimation() {
        breathAnimator = ValueAnimator.ofFloat(BREATH_SCALE_MIN, BREATH_SCALE_MAX).apply {
            duration = BREATH_DURATION
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                currentScale = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * 启动脉冲动画（聆听状态）
     */
    private fun startPulseAnimation() {
        breathAnimator = ValueAnimator.ofFloat(0.95f, 1.15f).apply {
            duration = 600L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                currentScale = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * 启动环形进度动画（处理/执行状态）
     */
    private fun startRingAnimation() {
        ringAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                ringProgress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
        currentScale = 1f
    }

    /**
     * 显示成功动画
     */
    private fun showSuccessAnimation() {
        currentScale = 1f
        val scaleUp = ObjectAnimator.ofFloat(this, "scaleX", 1f, 1.2f, 1f)
        val scaleUpY = ObjectAnimator.ofFloat(this, "scaleY", 1f, 1.2f, 1f)
        AnimatorSet().apply {
            playTogether(scaleUp, scaleUpY)
            duration = 300L
            start()
        }
        
        // 短暂显示后恢复空闲
        postDelayed({
            setState(FloatingBallState.IDLE)
        }, 1500L)
    }

    /**
     * 显示错误动画
     */
    private fun showErrorAnimation() {
        // 震动反馈
        vibrate()
        
        // 抖动动画
        val shake = ObjectAnimator.ofFloat(this, "translationX", 0f, -10f, 10f, -10f, 10f, 0f)
        shake.duration = 400L
        shake.start()
        
        // 短暂显示后恢复空闲
        postDelayed({
            setState(FloatingBallState.IDLE)
        }, 1500L)
    }

    /**
     * 震动反馈
     */
    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    /**
     * 停止所有动画
     */
    private fun stopAllAnimations() {
        breathAnimator?.cancel()
        breathAnimator = null
        ringAnimator?.cancel()
        ringAnimator = null
        currentScale = 1f
        ringProgress = 0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(ballSizePx, ballSizePx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (width / 2f - 4 * resources.displayMetrics.density) * currentScale
        
        // 绘制主圆
        canvas.drawCircle(centerX, centerY, radius, mainPaint)
        
        // 绘制进度环（处理/执行状态）
        if (currentState == FloatingBallState.PROCESSING || currentState == FloatingBallState.EXECUTING) {
            val ringRadius = radius + 4 * resources.displayMetrics.density
            val rect = RectF(
                centerX - ringRadius,
                centerY - ringRadius,
                centerX + ringRadius,
                centerY + ringRadius
            )
            ringPaint.alpha = 180
            canvas.drawArc(rect, -90f + ringProgress, 90f, false, ringPaint)
        }
        
        // 绘制中心图标
        drawCenterIcon(canvas, centerX, centerY, radius * 0.4f)
    }

    /**
     * 绘制中心图标
     */
    private fun drawCenterIcon(canvas: Canvas, centerX: Float, centerY: Float, iconSize: Float) {
        when (currentState) {
            FloatingBallState.IDLE -> {
                // 绘制 AI 图标（简化为三个点）
                val dotRadius = iconSize * 0.2f
                val spacing = iconSize * 0.5f
                canvas.drawCircle(centerX - spacing, centerY, dotRadius, iconPaint)
                canvas.drawCircle(centerX, centerY, dotRadius, iconPaint)
                canvas.drawCircle(centerX + spacing, centerY, dotRadius, iconPaint)
            }
            FloatingBallState.LISTENING -> {
                // 绘制麦克风图标（简化为圆形）
                canvas.drawCircle(centerX, centerY - iconSize * 0.2f, iconSize * 0.35f, iconPaint)
                val micWidth = iconSize * 0.2f
                canvas.drawRect(
                    centerX - micWidth,
                    centerY - iconSize * 0.2f,
                    centerX + micWidth,
                    centerY + iconSize * 0.4f,
                    iconPaint
                )
            }
            FloatingBallState.PROCESSING, FloatingBallState.EXECUTING -> {
                // 绘制齿轮图标（简化为圆形）
                canvas.drawCircle(centerX, centerY, iconSize * 0.3f, iconPaint)
            }
            FloatingBallState.SUCCESS -> {
                // 绘制对勾
                iconPaint.style = Paint.Style.STROKE
                iconPaint.strokeWidth = 3 * resources.displayMetrics.density
                canvas.drawLine(
                    centerX - iconSize * 0.4f, centerY,
                    centerX - iconSize * 0.1f, centerY + iconSize * 0.3f,
                    iconPaint
                )
                canvas.drawLine(
                    centerX - iconSize * 0.1f, centerY + iconSize * 0.3f,
                    centerX + iconSize * 0.4f, centerY - iconSize * 0.3f,
                    iconPaint
                )
                iconPaint.style = Paint.Style.FILL
            }
            FloatingBallState.ERROR -> {
                // 绘制叉号
                iconPaint.style = Paint.Style.STROKE
                iconPaint.strokeWidth = 3 * resources.displayMetrics.density
                canvas.drawLine(
                    centerX - iconSize * 0.3f, centerY - iconSize * 0.3f,
                    centerX + iconSize * 0.3f, centerY + iconSize * 0.3f,
                    iconPaint
                )
                canvas.drawLine(
                    centerX + iconSize * 0.3f, centerY - iconSize * 0.3f,
                    centerX - iconSize * 0.3f, centerY + iconSize * 0.3f,
                    iconPaint
                )
                iconPaint.style = Paint.Style.FILL
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                initialX = layoutParams?.x ?: 0
                initialY = layoutParams?.y ?: 0
                isDragging = false
                
                // 缩小效果
                animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()
                return true
            }
            
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - lastTouchX
                val deltaY = event.rawY - lastTouchY
                
                // 判断是否开始拖拽
                if (abs(deltaX) > CLICK_THRESHOLD || abs(deltaY) > CLICK_THRESHOLD) {
                    isDragging = true
                }
                
                if (isDragging) {
                    layoutParams?.let { params ->
                        params.x = (initialX + deltaX).toInt()
                        params.y = (initialY + deltaY).toInt()
                        windowManager?.updateViewLayout(this, params)
                    }
                }
                return true
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 恢复大小
                animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                
                if (!isDragging) {
                    // 点击事件
                    performClick()
                } else {
                    // 贴边吸附
                    stickToEdge()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        
        // 震动反馈
        vibrate()
        
        // 触发回调
        onClickListener?.invoke()
        
        return true
    }

    /**
     * 贴边吸附动画
     */
    private fun stickToEdge() {
        layoutParams?.let { params ->
            val screenWidth = resources.displayMetrics.widthPixels
            val centerX = params.x + ballSizePx / 2
            
            // 判断靠近哪边
            val targetX = if (centerX < screenWidth / 2) {
                ballMarginPx
            } else {
                screenWidth - ballSizePx - ballMarginPx
            }
            
            // 确保Y轴在有效范围内
            val screenHeight = resources.displayMetrics.heightPixels
            val statusBarHeight = getStatusBarHeight()
            val targetY = params.y.coerceIn(
                statusBarHeight + ballMarginPx,
                screenHeight - ballSizePx - ballMarginPx
            )
            
            // 动画移动
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = STICK_ANIMATION_DURATION
                interpolator = AccelerateDecelerateInterpolator()
                
                val startX = params.x
                val startY = params.y
                
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    params.x = (startX + (targetX - startX) * progress).toInt()
                    params.y = (startY + (targetY - startY) * progress).toInt()
                    windowManager?.updateViewLayout(this@FloatingBallView, params)
                }
                start()
            }
        }
    }

    /**
     * 获取状态栏高度
     */
    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            (24 * resources.displayMetrics.density).toInt()
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        stopAllAnimations()
        onClickListener = null
    }
}

