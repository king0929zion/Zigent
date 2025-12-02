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
import kotlin.math.sin

/**
 * 悬浮球视图
 * 可爱的大眼睛设计，支持拖拽、贴边、点击交互
 */
class FloatingBallView(context: Context) : View(context) {

    companion object {
        private const val TAG = "FloatingBallView"
        
        // 悬浮球尺寸
        const val BALL_SIZE = 60  // dp
        const val BALL_MARGIN = 8 // dp
        
        // 拖拽判定阈值
        private const val CLICK_THRESHOLD = 10
        
        // 贴边动画时长
        private const val STICK_ANIMATION_DURATION = 200L
        
        // 呼吸动画配置
        private const val BREATH_DURATION = 2000L
        
        // 自动隐藏配置
        private const val AUTO_HIDE_DELAY = 3000L  // 3秒无操作后隐藏
        private const val HIDDEN_VISIBLE_RATIO = 0.35f  // 隐藏时露出的比例
    }

    // 悬浮球尺寸（像素）
    private val ballSizePx: Int = (BALL_SIZE * resources.displayMetrics.density).toInt()
    private val ballMarginPx: Int = (BALL_MARGIN * resources.displayMetrics.density).toInt()
    
    // 画笔
    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val eyeWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val eyePupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A1A.toInt()
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
    private val colorError = ContextCompat.getColor(context, R.color.floating_ball_error)
    
    // 状态
    private var currentState: FloatingBallState = FloatingBallState.IDLE
    
    // 动画
    private var breathAnimator: ValueAnimator? = null
    private var ringAnimator: ValueAnimator? = null
    private var eyeAnimator: ValueAnimator? = null
    private var currentScale = 1f
    private var ringProgress = 0f
    private var eyeOffset = 0f // 眼睛动画偏移
    private var blinkProgress = 1f // 眨眼进度 (1=睁眼, 0=闭眼)
    
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
    
    // 自动隐藏相关
    private var isHidden = false
    private var autoHideRunnable: Runnable? = null
    var autoHideEnabled = true  // 是否启用自动隐藏
    private var isOnLeftSide = true  // 悬浮球是否在左侧
    
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
        
        // 状态变化时显示出来
        if (isHidden) {
            showFromEdge()
        }
        
        // 非空闲状态取消自动隐藏
        if (state != FloatingBallState.IDLE) {
            cancelAutoHide()
        } else {
            // 恢复空闲状态后，开始自动隐藏计时
            scheduleAutoHide()
        }
        
        // 停止当前动画
        stopAllAnimations()
        
        // 更新颜色
        updateColors()
        
        // 根据状态启动对应动画
        when (state) {
            FloatingBallState.IDLE -> startIdleAnimation()
            FloatingBallState.LISTENING -> startListeningAnimation()
            FloatingBallState.PROCESSING -> startProcessingAnimation()
            FloatingBallState.EXECUTING -> startExecutingAnimation()
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
            FloatingBallState.ERROR -> colorError
        }
        mainPaint.color = color
        ringPaint.color = color
    }

    /**
     * 空闲状态动画 - 缓慢眨眼
     */
    private fun startIdleAnimation() {
        // 眨眼动画
        breathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BREATH_DURATION
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                // 在动画末尾快速眨眼
                blinkProgress = if (progress > 0.9f) {
                    val blinkPhase = (progress - 0.9f) / 0.1f
                    if (blinkPhase < 0.5f) 1f - blinkPhase * 2 else (blinkPhase - 0.5f) * 2
                } else {
                    1f
                }
                invalidate()
            }
            start()
        }
        
        // 眼睛左右看动画
        eyeAnimator = ValueAnimator.ofFloat(-1f, 1f).apply {
            duration = 3000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                eyeOffset = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * 录音状态动画 - 脉冲
     */
    private fun startListeningAnimation() {
        blinkProgress = 1f
        eyeOffset = 0f
        
        breathAnimator = ValueAnimator.ofFloat(0.95f, 1.1f).apply {
            duration = 400L
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
     * 处理状态动画 - 环形进度
     */
    private fun startProcessingAnimation() {
        blinkProgress = 1f
        eyeOffset = 0f
        currentScale = 1f
        
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
    }

    /**
     * 执行状态动画 - 环形进度 + 微微跳动
     */
    private fun startExecutingAnimation() {
        blinkProgress = 1f
        eyeOffset = 0f
        
        ringAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                ringProgress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
        
        breathAnimator = ValueAnimator.ofFloat(1f, 1.05f).apply {
            duration = 600L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                currentScale = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * 显示成功动画
     */
    private fun showSuccessAnimation() {
        blinkProgress = 1f
        eyeOffset = 0f
        currentScale = 1f
        
        val scaleUp = ObjectAnimator.ofFloat(this, "scaleX", 1f, 1.3f, 1f)
        val scaleUpY = ObjectAnimator.ofFloat(this, "scaleY", 1f, 1.3f, 1f)
        AnimatorSet().apply {
            playTogether(scaleUp, scaleUpY)
            duration = 400L
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
        blinkProgress = 1f
        eyeOffset = 0f
        
        // 震动反馈
        vibrate()
        
        // 抖动动画
        val shake = ObjectAnimator.ofFloat(this, "translationX", 0f, -15f, 15f, -15f, 15f, -10f, 10f, 0f)
        shake.duration = 500L
        shake.start()
        
        // 短暂显示后恢复空闲
        postDelayed({
            setState(FloatingBallState.IDLE)
        }, 2000L)
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
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
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
        eyeAnimator?.cancel()
        eyeAnimator = null
        currentScale = 1f
        ringProgress = 0f
        blinkProgress = 1f
        eyeOffset = 0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(ballSizePx, ballSizePx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (width / 2f - 4 * resources.displayMetrics.density) * currentScale
        
        // 绘制主圆（背景）
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
            ringPaint.color = 0xAAFFFFFF.toInt()
            ringPaint.alpha = 200
            canvas.drawArc(rect, -90f + ringProgress, 90f, false, ringPaint)
        }
        
        // 根据状态绘制内容
        when (currentState) {
            FloatingBallState.IDLE, FloatingBallState.LISTENING -> {
                // 绘制大眼睛
                drawEyes(canvas, centerX, centerY, radius)
            }
            FloatingBallState.PROCESSING, FloatingBallState.EXECUTING -> {
                // 绘制小眼睛（专注工作的样子）
                drawWorkingEyes(canvas, centerX, centerY, radius)
            }
            FloatingBallState.SUCCESS -> {
                // 绘制开心的眼睛
                drawHappyEyes(canvas, centerX, centerY, radius)
            }
            FloatingBallState.ERROR -> {
                // 绘制X眼睛
                drawErrorEyes(canvas, centerX, centerY, radius)
            }
        }
    }

    /**
     * 绘制大眼睛（默认/录音状态）
     */
    private fun drawEyes(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val eyeSpacing = radius * 0.45f
        val eyeRadius = radius * 0.28f
        val pupilRadius = eyeRadius * 0.5f
        val eyeY = centerY - radius * 0.05f
        
        // 眨眼效果 - 调整眼白高度
        val eyeHeight = eyeRadius * 2 * blinkProgress
        
        // 左眼
        val leftEyeX = centerX - eyeSpacing
        if (blinkProgress > 0.1f) {
            // 眼白（椭圆形状模拟眨眼）
            canvas.save()
            canvas.clipRect(
                leftEyeX - eyeRadius,
                eyeY - eyeHeight / 2,
                leftEyeX + eyeRadius,
                eyeY + eyeHeight / 2
            )
            canvas.drawCircle(leftEyeX, eyeY, eyeRadius, eyeWhitePaint)
            canvas.restore()
            
            // 瞳孔（跟随eyeOffset移动）
            val pupilOffsetX = eyeOffset * eyeRadius * 0.3f
            canvas.drawCircle(leftEyeX + pupilOffsetX, eyeY, pupilRadius, eyePupilPaint)
        } else {
            // 完全闭眼 - 画一条线
            iconPaint.strokeWidth = 3 * resources.displayMetrics.density
            canvas.drawLine(leftEyeX - eyeRadius, eyeY, leftEyeX + eyeRadius, eyeY, eyeWhitePaint)
        }
        
        // 右眼
        val rightEyeX = centerX + eyeSpacing
        if (blinkProgress > 0.1f) {
            canvas.save()
            canvas.clipRect(
                rightEyeX - eyeRadius,
                eyeY - eyeHeight / 2,
                rightEyeX + eyeRadius,
                eyeY + eyeHeight / 2
            )
            canvas.drawCircle(rightEyeX, eyeY, eyeRadius, eyeWhitePaint)
            canvas.restore()
            
            val pupilOffsetX = eyeOffset * eyeRadius * 0.3f
            canvas.drawCircle(rightEyeX + pupilOffsetX, eyeY, pupilRadius, eyePupilPaint)
        } else {
            canvas.drawLine(rightEyeX - eyeRadius, eyeY, rightEyeX + eyeRadius, eyeY, eyeWhitePaint)
        }
    }

    /**
     * 绘制工作中的眼睛（专注）
     */
    private fun drawWorkingEyes(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val eyeSpacing = radius * 0.4f
        val eyeRadius = radius * 0.2f
        val eyeY = centerY
        
        // 左眼 - 小圆点
        canvas.drawCircle(centerX - eyeSpacing, eyeY, eyeRadius, eyeWhitePaint)
        
        // 右眼 - 小圆点
        canvas.drawCircle(centerX + eyeSpacing, eyeY, eyeRadius, eyeWhitePaint)
    }

    /**
     * 绘制开心的眼睛（^_^）
     */
    private fun drawHappyEyes(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val eyeSpacing = radius * 0.4f
        val eyeSize = radius * 0.25f
        val eyeY = centerY - radius * 0.05f
        
        iconPaint.color = 0xFFFFFFFF.toInt()
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = 4 * resources.displayMetrics.density
        iconPaint.strokeCap = Paint.Cap.ROUND
        
        // 左眼 - 弧形 ^
        val leftRect = RectF(
            centerX - eyeSpacing - eyeSize,
            eyeY - eyeSize,
            centerX - eyeSpacing + eyeSize,
            eyeY + eyeSize
        )
        canvas.drawArc(leftRect, 200f, 140f, false, iconPaint)
        
        // 右眼 - 弧形 ^
        val rightRect = RectF(
            centerX + eyeSpacing - eyeSize,
            eyeY - eyeSize,
            centerX + eyeSpacing + eyeSize,
            eyeY + eyeSize
        )
        canvas.drawArc(rightRect, 200f, 140f, false, iconPaint)
        
        iconPaint.style = Paint.Style.FILL
    }

    /**
     * 绘制错误的眼睛（X_X）
     */
    private fun drawErrorEyes(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val eyeSpacing = radius * 0.4f
        val eyeSize = radius * 0.2f
        val eyeY = centerY
        
        iconPaint.color = 0xFFFFFFFF.toInt()
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = 4 * resources.displayMetrics.density
        iconPaint.strokeCap = Paint.Cap.ROUND
        
        // 左眼 X
        canvas.drawLine(
            centerX - eyeSpacing - eyeSize, eyeY - eyeSize,
            centerX - eyeSpacing + eyeSize, eyeY + eyeSize,
            iconPaint
        )
        canvas.drawLine(
            centerX - eyeSpacing + eyeSize, eyeY - eyeSize,
            centerX - eyeSpacing - eyeSize, eyeY + eyeSize,
            iconPaint
        )
        
        // 右眼 X
        canvas.drawLine(
            centerX + eyeSpacing - eyeSize, eyeY - eyeSize,
            centerX + eyeSpacing + eyeSize, eyeY + eyeSize,
            iconPaint
        )
        canvas.drawLine(
            centerX + eyeSpacing + eyeSize, eyeY - eyeSize,
            centerX + eyeSpacing - eyeSize, eyeY + eyeSize,
            iconPaint
        )
        
        iconPaint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 如果隐藏状态，先显示出来
                if (isHidden) {
                    showFromEdge()
                    return true
                }
                
                // 取消自动隐藏
                cancelAutoHide()
                
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
            isOnLeftSide = centerX < screenWidth / 2
            val targetX = if (isOnLeftSide) {
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
            
            // 贴边后开始自动隐藏计时
            isHidden = false
            scheduleAutoHide()
        }
    }
    
    /**
     * 安排自动隐藏
     */
    private fun scheduleAutoHide() {
        if (!autoHideEnabled) return
        if (currentState != FloatingBallState.IDLE) return  // 非空闲状态不隐藏
        
        cancelAutoHide()
        autoHideRunnable = Runnable {
            hideToEdge()
        }
        postDelayed(autoHideRunnable, AUTO_HIDE_DELAY)
    }
    
    /**
     * 取消自动隐藏
     */
    private fun cancelAutoHide() {
        autoHideRunnable?.let { removeCallbacks(it) }
        autoHideRunnable = null
    }
    
    /**
     * 隐藏到边缘（只露出一部分）
     */
    private fun hideToEdge() {
        if (isHidden) return
        if (currentState != FloatingBallState.IDLE) return
        
        layoutParams?.let { params ->
            val screenWidth = resources.displayMetrics.widthPixels
            val hiddenOffset = (ballSizePx * (1 - HIDDEN_VISIBLE_RATIO)).toInt()
            
            val targetX = if (isOnLeftSide) {
                -hiddenOffset
            } else {
                screenWidth - ballSizePx + hiddenOffset
            }
            
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = STICK_ANIMATION_DURATION
                interpolator = AccelerateDecelerateInterpolator()
                
                val startX = params.x
                
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    params.x = (startX + (targetX - startX) * progress).toInt()
                    windowManager?.updateViewLayout(this@FloatingBallView, params)
                }
                
                doOnEnd {
                    isHidden = true
                    // 降低透明度
                    animate().alpha(0.6f).setDuration(100).start()
                }
                start()
            }
        }
    }
    
    /**
     * 从边缘显示出来
     */
    fun showFromEdge() {
        if (!isHidden) return
        
        // 恢复透明度
        animate().alpha(1f).setDuration(100).start()
        
        layoutParams?.let { params ->
            val screenWidth = resources.displayMetrics.widthPixels
            
            val targetX = if (isOnLeftSide) {
                ballMarginPx
            } else {
                screenWidth - ballSizePx - ballMarginPx
            }
            
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = STICK_ANIMATION_DURATION
                interpolator = AccelerateDecelerateInterpolator()
                
                val startX = params.x
                
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    params.x = (startX + (targetX - startX) * progress).toInt()
                    windowManager?.updateViewLayout(this@FloatingBallView, params)
                }
                
                doOnEnd {
                    isHidden = false
                    scheduleAutoHide()
                }
                start()
            }
        }
    }
    
    /**
     * 扩展方法：动画结束回调
     */
    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationEnd(animation: android.animation.Animator) { action() }
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })
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
