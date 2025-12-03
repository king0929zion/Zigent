package com.zigent.ui.floating

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.zigent.R
import com.zigent.ZigentApp
import com.zigent.adb.AdbManager
import com.zigent.agent.ActionExecutor
import com.zigent.agent.AgentEngine
import com.zigent.agent.ScreenAnalyzer
import com.zigent.data.SettingsRepository
import com.zigent.ui.MainActivity
import com.zigent.utils.Logger
import com.zigent.utils.PermissionHelper
import com.zigent.voice.VoiceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * 悬浮球前台服务
 * 负责管理悬浮球的生命周期和交互
 */
class FloatingService : Service() {

    companion object {
        private const val TAG = "FloatingService"
        
        // 服务操作
        const val ACTION_START = "com.zigent.action.START_FLOATING"
        const val ACTION_STOP = "com.zigent.action.STOP_FLOATING"
        const val ACTION_UPDATE_STATE = "com.zigent.action.UPDATE_STATE"
        
        const val EXTRA_STATE = "extra_state"
        
        // 服务是否运行中
        @Volatile
        var isRunning = false
            private set
        
        // 服务实例（供外部访问交互控制器）
        @Volatile
        var instance: FloatingService? = null
            private set
        
        /**
         * 启动悬浮球服务
         */
        fun start(context: Context) {
            if (!PermissionHelper.canDrawOverlays(context)) {
                Logger.w("Cannot start floating service: no overlay permission", TAG)
                return
            }
            
            val intent = Intent(context, FloatingService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }
        
        /**
         * 停止悬浮球服务
         */
        fun stop(context: Context) {
            val intent = Intent(context, FloatingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        /**
         * 更新悬浮球状态
         */
        fun updateState(context: Context, state: FloatingBallState) {
            val intent = Intent(context, FloatingService::class.java).apply {
                action = ACTION_UPDATE_STATE
                putExtra(EXTRA_STATE, state.name)
            }
            context.startService(intent)
        }
    }

    // 窗口管理器
    private lateinit var windowManager: WindowManager
    
    // 悬浮球视图
    private var floatingBallView: FloatingBallView? = null
    
    // 悬浮文字面板
    private var textPanel: FloatingTextPanel? = null
    
    // 屏幕呼吸光效
    private var edgeGlowView: EdgeGlowOverlay? = null
    
    // 当前状态
    private var currentState: FloatingBallState = FloatingBallState.IDLE
    
    // 协程作用域
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // 依赖组件
    private lateinit var voiceManager: VoiceManager
    private lateinit var adbManager: AdbManager
    private lateinit var screenAnalyzer: ScreenAnalyzer
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var agentEngine: AgentEngine
    private lateinit var settingsRepository: SettingsRepository
    
    // 交互控制器
    private var interactionController: FloatingInteractionController? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Logger.i("FloatingService created", TAG)
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // 初始化所有依赖
        initializeDependencies()
    }
    
    /**
     * 初始化所有依赖组件
     */
    private fun initializeDependencies() {
        // 初始化设置仓库
        settingsRepository = SettingsRepository(this)
        
        // 初始化语音管理器
        voiceManager = VoiceManager(this)
        
        // 初始化ADB管理器
        adbManager = AdbManager(this)
        
        // 初始化屏幕分析器
        screenAnalyzer = ScreenAnalyzer(this, adbManager)
        
        // 初始化操作执行器
        actionExecutor = ActionExecutor(this, adbManager)
        
        // 初始化Agent引擎
        agentEngine = AgentEngine(this, screenAnalyzer, actionExecutor)
        
        // 初始化交互控制器
        interactionController = FloatingInteractionController(this, voiceManager, agentEngine).apply {
            callback = createInteractionCallback()
        }
        
        // 加载已保存的AI设置
        loadAiSettings()
    }
    
    /**
     * 从存储中加载AI设置并配置AgentEngine
     */
    private fun loadAiSettings() {
        serviceScope.launch {
            try {
                // 加载AI设置
                val aiSettings = settingsRepository.aiSettingsFlow.first()
                if (aiSettings.apiKey.isNotBlank()) {
                    agentEngine.configureAi(aiSettings)
                    interactionController?.configureAi(aiSettings)
                    
                    // 配置语音识别器使用相同的API Key（硅基流动）
                    voiceManager.configureSiliconFlow(aiSettings.apiKey)
                    
                    Logger.i("AI settings loaded: ${aiSettings.provider}", TAG)
                } else {
                    Logger.w("No API key configured", TAG)
                }
            } catch (e: Exception) {
                Logger.e("Failed to load settings", e, TAG)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundWithNotification()
                showFloatingBall()
                initializeVoice()
            }
            ACTION_STOP -> {
                hideFloatingBall()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE_STATE -> {
                val stateName = intent.getStringExtra(EXTRA_STATE)
                stateName?.let {
                    try {
                        val state = FloatingBallState.valueOf(it)
                        updateFloatingBallState(state)
                    } catch (e: IllegalArgumentException) {
                        Logger.e("Invalid state: $stateName", e, TAG)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hideFloatingBall()
        interactionController?.release()
        agentEngine.release()
        adbManager.release()
        serviceScope.cancel()
        isRunning = false
        instance = null
        Logger.i("FloatingService destroyed", TAG)
    }

    /**
     * 初始化语音服务
     */
    private fun initializeVoice() {
        voiceManager.initialize { success ->
            if (success) {
                Logger.i("Voice service initialized", TAG)
            } else {
                Logger.e("Failed to initialize voice service", null, TAG)
            }
        }
    }

    /**
     * 创建交互回调
     */
    private fun createInteractionCallback(): InteractionCallback {
        return object : InteractionCallback {
            override fun onPhaseChanged(phase: InteractionPhase) {
                // 更新悬浮球状态
                val ballState = when (phase) {
                    InteractionPhase.IDLE -> FloatingBallState.IDLE
                    InteractionPhase.VOICE_INPUT -> FloatingBallState.LISTENING
                    InteractionPhase.AI_PROCESSING -> FloatingBallState.PROCESSING
                    InteractionPhase.TASK_EXECUTING -> FloatingBallState.EXECUTING
                    InteractionPhase.WAITING_ANSWER -> FloatingBallState.PROCESSING  // 等待回答时显示为处理中
                    InteractionPhase.COMPLETED -> FloatingBallState.SUCCESS
                    InteractionPhase.ERROR -> FloatingBallState.ERROR
                }
                updateFloatingBallState(ballState)
                
                // 更新文字面板状态
                serviceScope.launch(Dispatchers.Main) {
                    when (phase) {
                        InteractionPhase.IDLE -> {
                            hideTextPanel()
                            hideEdgeGlow()
                        }
                        InteractionPhase.VOICE_INPUT -> {
                            showTextPanel()
                            textPanel?.setListeningMode()
                            hideEdgeGlow()
                        }
                        InteractionPhase.AI_PROCESSING -> {
                            textPanel?.setProcessingMode()
                            showEdgeGlow()
                        }
                        InteractionPhase.TASK_EXECUTING -> {
                            textPanel?.setExecutingMode()
                            showEdgeGlow()
                        }
                        InteractionPhase.WAITING_ANSWER -> {
                            // 等待用户回答 AI 问题，显示面板
                            showTextPanel()
                            textPanel?.setProcessingMode()
                            showEdgeGlow()
                        }
                        InteractionPhase.COMPLETED -> {
                            // 面板保持显示完成状态一段时间
                            hideEdgeGlow()
                        }
                        InteractionPhase.ERROR -> {
                            // 面板保持显示错误状态一段时间
                            hideEdgeGlow()
                        }
                    }
                }
            }

            override fun onVoiceResult(text: String) {
                Logger.d("Voice result: $text", TAG)
                // 用户语音结果不向面板展示，保持面板仅用于AI状态/问题
            }

            override fun onAiResponse(response: String) {
                Logger.d("AI response: $response", TAG)
                serviceScope.launch(Dispatchers.Main) {
                    showTextPanel()
                    textPanel?.updateQuestion(response)
                }
            }

            override fun onTaskProgress(progress: String) {
                Logger.d("Task progress: $progress", TAG)
                serviceScope.launch(Dispatchers.Main) {
                    showTextPanel()
                    textPanel?.updateStatus(title = "执行中", hint = progress.take(40))
                }
            }

            override fun onTaskCompleted(result: String) {
                Logger.d("Task completed: $result", TAG)
                serviceScope.launch(Dispatchers.Main) {
                    textPanel?.setCompletedMode(result)
                    // 延迟隐藏面板
                    delay(3000)
                    hideTextPanel()
                }
            }

            override fun onError(message: String) {
                Logger.e("Interaction error: $message", null, TAG)
                serviceScope.launch(Dispatchers.Main) {
                    textPanel?.setErrorMode(message)
                    // 延迟隐藏面板
                    delay(3000)
                    hideTextPanel()
                }
            }
            
            override fun onReasoning(reasoning: String) {
                // AI 推理过程（小字体展示，不朗读）
                serviceScope.launch(Dispatchers.Main) {
                    showTextPanel()
                    textPanel?.updateReasoning(reasoning)
                }
            }
        }
    }

    /**
     * 获取交互控制器
     */
    fun getInteractionController(): FloatingInteractionController? = interactionController

    /**
     * 启动前台服务并显示通知
     */
    private fun startForegroundWithNotification() {
        val notification = createNotification()
        startForeground(ZigentApp.NOTIFICATION_ID, notification)
        isRunning = true
    }

    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        // 点击通知跳转到主界面
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, ZigentApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 显示悬浮球
     */
    private fun showFloatingBall() {
        if (floatingBallView != null) {
            Logger.w("Floating ball already shown", TAG)
            return
        }
        
        try {
            floatingBallView = FloatingBallView(this).apply {
                // 创建布局参数
                val params = createLayoutParams()
                
                // 绑定窗口管理器
                attachToWindow(windowManager)
                
                // 设置点击回调
                onClickListener = {
                    handleFloatingBallClick()
                }
                
                // 添加到窗口
                windowManager.addView(this, params)
                
                // 设置初始状态
                setState(FloatingBallState.IDLE)
            }
            
            Logger.i("Floating ball shown", TAG)
        } catch (e: Exception) {
            Logger.e("Failed to show floating ball", e, TAG)
        }
        
        // 同步显示边缘光效
        showEdgeGlow()
    }

    /**
     * 隐藏悬浮球
     */
    private fun hideFloatingBall() {
        floatingBallView?.let { view ->
            try {
                view.release()
                windowManager.removeView(view)
                Logger.i("Floating ball hidden", TAG)
            } catch (e: Exception) {
                Logger.e("Failed to hide floating ball", e, TAG)
            }
        }
        floatingBallView = null
        
        // 同时隐藏文字面板
        hideTextPanel()
        hideEdgeGlow()
    }
    
    /**
     * 显示文字面板
     */
    private fun showTextPanel() {
        if (textPanel != null) return
        
        try {
            textPanel = FloatingTextPanel(this).apply {
                val params = createLayoutParams()
                attachToWindow(windowManager)
                windowManager.addView(this, params)
                show(mode = FloatingTextPanel.PanelMode.STATUS)
            }
            Logger.d("Text panel shown", TAG)
        } catch (e: Exception) {
            Logger.e("Failed to show text panel", e, TAG)
        }
    }
    
    /**
     * 隐藏文字面板
     */
    private fun hideTextPanel() {
        textPanel?.let { panel ->
            try {
                panel.hide()
                // 延迟移除视图，让动画完成
                serviceScope.launch(Dispatchers.Main) {
                    delay(300)
                    try {
                        panel.release()
                        windowManager.removeView(panel)
                    } catch (e: Exception) {
                        Logger.e("Failed to remove text panel", e, TAG)
                    }
                }
                Logger.d("Text panel hidden", TAG)
            } catch (e: Exception) {
                Logger.e("Failed to hide text panel", e, TAG)
            }
        }
        textPanel = null
    }
    
    /**
     * 显示屏幕边缘呼吸光效
     */
    private fun showEdgeGlow() {
        if (edgeGlowView != null) return
        try {
            edgeGlowView = EdgeGlowOverlay(this).apply {
                val params = createLayoutParams()
                attachToWindow(windowManager)
                windowManager.addView(this, params)
                start()
            }
        } catch (e: Exception) {
            Logger.e("Failed to show edge glow", e, TAG)
        }
    }
    
    /**
     * 隐藏屏幕边缘呼吸光效
     */
    private fun hideEdgeGlow() {
        edgeGlowView?.let { view ->
            try {
                view.stop()
                windowManager.removeView(view)
            } catch (e: Exception) {
                Logger.e("Failed to hide edge glow", e, TAG)
            }
        }
        edgeGlowView = null
    }

    /**
     * 更新悬浮球状态
     */
    private fun updateFloatingBallState(state: FloatingBallState) {
        currentState = state
        floatingBallView?.setState(state)
    }

    /**
     * 处理悬浮球点击事件
     */
    private fun handleFloatingBallClick() {
        Logger.d("Floating ball clicked, current state: $currentState", TAG)
        
        // 委托给交互控制器处理
        interactionController?.handleFloatingBallClick()
    }

    /**
     * 通知状态变化（发送广播）
     */
    private fun notifyStateChanged(state: FloatingBallState) {
        val intent = Intent("com.zigent.STATE_CHANGED").apply {
            putExtra("state", state.name)
        }
        sendBroadcast(intent)
    }
}
