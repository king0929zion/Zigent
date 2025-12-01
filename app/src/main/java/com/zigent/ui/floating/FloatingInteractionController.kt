package com.zigent.ui.floating

import android.content.Context
import com.zigent.agent.AgentCallback
import com.zigent.agent.AgentEngine
import com.zigent.agent.AgentState
import com.zigent.ai.AiSettings
import com.zigent.utils.Logger
import com.zigent.voice.VoiceInteractionResult
import com.zigent.voice.VoiceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 交互阶段
 */
enum class InteractionPhase {
    IDLE,               // 空闲
    VOICE_INPUT,        // 语音输入中
    AI_PROCESSING,      // AI处理中
    TASK_EXECUTING,     // 任务执行中
    COMPLETED,          // 完成
    ERROR               // 错误
}

/**
 * 交互事件回调
 */
interface InteractionCallback {
    fun onPhaseChanged(phase: InteractionPhase)
    fun onVoiceResult(text: String)
    fun onAiResponse(response: String)
    fun onTaskProgress(progress: String)
    fun onTaskCompleted(result: String)
    fun onError(message: String)
}

/**
 * 悬浮球交互控制器
 * 协调语音输入 -> AI处理 -> 任务执行的完整流程
 */
class FloatingInteractionController(
    private val context: Context,
    private val voiceManager: VoiceManager,
    private val agentEngine: AgentEngine
) {
    companion object {
        private const val TAG = "InteractionController"
    }

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // 当前交互阶段
    private val _phase = MutableStateFlow(InteractionPhase.IDLE)
    val phase: StateFlow<InteractionPhase> = _phase.asStateFlow()
    
    // 识别到的文本
    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()
    
    // 回调
    var callback: InteractionCallback? = null

    init {
        // 初始化语音管理器
        voiceManager.initialize()
        
        // 设置语音识别结果回调
        voiceManager.onRecognitionResult = { result ->
            handleVoiceResult(result)
        }
        
        // 设置Agent回调
        agentEngine.callback = createAgentCallback()
    }

    /**
     * 创建Agent回调
     */
    private fun createAgentCallback(): AgentCallback {
        return object : AgentCallback {
            override fun onStateChanged(state: AgentState) {
                val phase = when (state) {
                    AgentState.IDLE -> InteractionPhase.IDLE
                    AgentState.PLANNING -> InteractionPhase.AI_PROCESSING
                    AgentState.EXECUTING -> InteractionPhase.TASK_EXECUTING
                    AgentState.PAUSED -> InteractionPhase.TASK_EXECUTING
                    AgentState.COMPLETED -> InteractionPhase.COMPLETED
                    AgentState.FAILED -> InteractionPhase.ERROR
                }
                _phase.value = phase
                callback?.onPhaseChanged(phase)
            }

            override fun onStepStarted(step: Int, description: String) {
                callback?.onTaskProgress("步骤$step: $description")
            }

            override fun onStepCompleted(step: Int, success: Boolean, message: String) {
                val status = if (success) "完成" else "失败"
                Logger.d("Step $step $status: $message", TAG)
            }

            override fun onProgress(message: String) {
                callback?.onTaskProgress(message)
            }

            override fun onTaskCompleted(result: String) {
                completeTask(result)
            }

            override fun onTaskFailed(error: String) {
                failTask(error)
            }
        }
    }

    /**
     * 配置AI设置
     */
    fun configureAi(settings: AiSettings) {
        agentEngine.configureAi(settings)
        Logger.i("AI configured in InteractionController", TAG)
    }

    /**
     * 检查AI是否已配置
     */
    fun isAiConfigured(): Boolean = agentEngine.isAiConfigured()

    /**
     * 处理悬浮球点击事件
     * 根据当前阶段决定下一步操作
     */
    fun handleFloatingBallClick() {
        Logger.d("Floating ball clicked, current phase: ${_phase.value}", TAG)
        
        when (_phase.value) {
            InteractionPhase.IDLE -> {
                // 检查AI是否已配置
                if (!isAiConfigured()) {
                    callback?.onError("请先在设置中配置AI")
                    voiceManager.speak("请先打开应用设置AI")
                    return
                }
                // 空闲 -> 开始语音输入
                startVoiceInput()
            }
            InteractionPhase.VOICE_INPUT -> {
                // 语音输入中 -> 结束语音输入并处理结果
                finishVoiceInput()
            }
            InteractionPhase.AI_PROCESSING, InteractionPhase.TASK_EXECUTING -> {
                // 处理中/执行中 -> 取消任务
                Logger.d("Cancelling task", TAG)
                voiceManager.speak("正在取消任务")
                cancel()
            }
            InteractionPhase.COMPLETED, InteractionPhase.ERROR -> {
                // 完成/错误 -> 重置为空闲
                reset()
            }
        }
    }

    /**
     * 开始语音输入
     */
    fun startVoiceInput() {
        Logger.i("Starting voice input", TAG)
        
        _phase.value = InteractionPhase.VOICE_INPUT
        _recognizedText.value = ""
        callback?.onPhaseChanged(InteractionPhase.VOICE_INPUT)
        
        // 播报提示音后开始识别
        voiceManager.speak("请说出您的需求") {
            voiceManager.startListening()
        }
    }

    /**
     * 停止语音输入
     */
    fun stopVoiceInput() {
        Logger.i("Stopping voice input", TAG)
        voiceManager.stopListening()
    }
    
    /**
     * 完成语音输入并处理结果
     * 用户再次点击悬浮球时调用
     */
    private fun finishVoiceInput() {
        Logger.i("Finishing voice input", TAG)
        
        // 先停止TTS（如果还在播报提示语）
        if (voiceManager.isSpeaking()) {
            voiceManager.stopSpeaking()
        }
        
        // 获取当前已识别的文本
        val currentText = _recognizedText.value.ifBlank { 
            voiceManager.lastRecognizedText.value 
        }
        
        // 取消语音识别
        voiceManager.cancelListening()
        
        if (currentText.isNotBlank()) {
            // 有识别到文本，直接处理
            Logger.i("Processing recognized text: $currentText", TAG)
            callback?.onVoiceResult(currentText)
            startAiProcessing(currentText)
        } else {
            // 没有识别到文本
            Logger.w("No text recognized", TAG)
            _phase.value = InteractionPhase.ERROR
            callback?.onError("没有检测到语音")
            voiceManager.speak("没有检测到语音，请重试") {
                scope.launch {
                    delay(1000)
                    reset()
                }
            }
        }
    }

    /**
     * 处理语音识别结果
     */
    private fun handleVoiceResult(result: VoiceInteractionResult) {
        if (result.success && result.text.isNotBlank()) {
            _recognizedText.value = result.text
            callback?.onVoiceResult(result.text)
            
            Logger.i("Voice input: ${result.text}", TAG)
            
            // 进入AI处理阶段
            startAiProcessing(result.text)
        } else {
            // 识别失败
            Logger.w("Voice recognition failed: ${result.errorMessage}", TAG)
            
            if (result.errorMessage.contains("无法识别") || result.errorMessage.contains("超时")) {
                _phase.value = InteractionPhase.ERROR
                callback?.onError("没有检测到语音，请重试")
                voiceManager.speak("没有检测到语音，请点击悬浮球重试")
                
                scope.launch {
                    delay(2000)
                    reset()
                }
            } else {
                _phase.value = InteractionPhase.ERROR
                callback?.onError(result.errorMessage)
                
                scope.launch {
                    delay(2000)
                    reset()
                }
            }
        }
    }

    /**
     * 开始AI处理
     */
    private fun startAiProcessing(userInput: String) {
        Logger.i("Starting AI processing: $userInput", TAG)
        
        _phase.value = InteractionPhase.AI_PROCESSING
        callback?.onPhaseChanged(InteractionPhase.AI_PROCESSING)
        
        // 播报确认
        voiceManager.speak("收到，正在为您处理")
        
        // 启动Agent执行任务
        agentEngine.startTask(userInput)
    }

    /**
     * 更新任务执行进度
     */
    fun updateProgress(progress: String) {
        _phase.value = InteractionPhase.TASK_EXECUTING
        callback?.onPhaseChanged(InteractionPhase.TASK_EXECUTING)
        callback?.onTaskProgress(progress)
    }

    /**
     * 任务完成
     */
    fun completeTask(result: String) {
        _phase.value = InteractionPhase.COMPLETED
        callback?.onPhaseChanged(InteractionPhase.COMPLETED)
        callback?.onTaskCompleted(result)
        
        voiceManager.speak(result) {
            scope.launch {
                delay(2000)
                reset()
            }
        }
    }

    /**
     * 任务失败
     */
    fun failTask(error: String) {
        _phase.value = InteractionPhase.ERROR
        callback?.onPhaseChanged(InteractionPhase.ERROR)
        callback?.onError(error)
        
        voiceManager.speak("任务执行失败：$error") {
            scope.launch {
                delay(2000)
                reset()
            }
        }
    }

    /**
     * 重置为空闲状态
     */
    fun reset() {
        Logger.d("Resetting to idle", TAG)
        _phase.value = InteractionPhase.IDLE
        _recognizedText.value = ""
        callback?.onPhaseChanged(InteractionPhase.IDLE)
    }

    /**
     * 取消当前操作
     */
    fun cancel() {
        voiceManager.cancelListening()
        voiceManager.stopSpeaking()
        agentEngine.cancelTask()
        reset()
    }

    /**
     * 获取当前悬浮球状态
     */
    fun getFloatingBallState(): FloatingBallState {
        return when (_phase.value) {
            InteractionPhase.IDLE -> FloatingBallState.IDLE
            InteractionPhase.VOICE_INPUT -> FloatingBallState.LISTENING
            InteractionPhase.AI_PROCESSING -> FloatingBallState.PROCESSING
            InteractionPhase.TASK_EXECUTING -> FloatingBallState.EXECUTING
            InteractionPhase.COMPLETED -> FloatingBallState.SUCCESS
            InteractionPhase.ERROR -> FloatingBallState.ERROR
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        voiceManager.release()
        agentEngine.release()
        scope.cancel()
    }
}

