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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

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
                    AgentState.ANALYZING -> InteractionPhase.AI_PROCESSING
                    AgentState.PLANNING -> InteractionPhase.AI_PROCESSING
                    AgentState.EXECUTING -> InteractionPhase.TASK_EXECUTING
                    AgentState.WAITING_USER -> InteractionPhase.AI_PROCESSING
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
            
            override fun onAskUser(question: String) {
                // 当AI需要询问用户时，通过TTS朗读问题
                Logger.i("AI asking user: $question", TAG)
                callback?.onTaskProgress("AI询问: $question")
                voiceManager.speak(question) {
                    // 朗读完成后可以开始新的语音输入
                }
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
        Logger.i("=== finishVoiceInput() called ===", TAG)
        Logger.d("Current _recognizedText: '${_recognizedText.value}'", TAG)
        Logger.d("Current lastRecognizedText: '${voiceManager.lastRecognizedText.value}'", TAG)
        
        // 先停止TTS（如果还在播报提示语）
        if (voiceManager.isSpeaking()) {
            Logger.d("Stopping TTS first", TAG)
            voiceManager.stopSpeaking()
        }

        // 检查是否已有识别到的文本（实时更新的）
        val existingText = currentRecognizedText()
        if (existingText.isNotBlank()) {
            Logger.i("=== Using existing recognized text: $existingText ===", TAG)
            // 停止录音但不等待，直接使用已有文本
            voiceManager.cancelListening()
            _recognizedText.value = existingText
            callback?.onVoiceResult(existingText)
            startAiProcessing(existingText)
            return
        }

        // 没有已识别的文本，停止录音并等待识别结果
        Logger.i("Stopping voice listening and waiting for recognition...", TAG)
        
        // 更新状态为识别中
        _phase.value = InteractionPhase.AI_PROCESSING
        callback?.onPhaseChanged(InteractionPhase.AI_PROCESSING)
        callback?.onTaskProgress("正在识别语音...")
        
        voiceManager.stopListening()

        scope.launch {
            Logger.d("Waiting for recognized text...", TAG)
            // 增加超时时间到 10 秒，因为 API 调用需要时间
            val finalText = waitForRecognizedText(timeoutMs = 10000L)
            Logger.i("waitForRecognizedText returned: '$finalText'", TAG)

            if (finalText.isNotBlank()) {
                Logger.i("=== Processing recognized text: $finalText ===", TAG)
                _recognizedText.value = finalText
                callback?.onVoiceResult(finalText)
                startAiProcessing(finalText)
            } else {
                Logger.w("=== No text recognized after waiting! ===", TAG)
                Logger.d("Final _recognizedText: '${_recognizedText.value}'", TAG)
                Logger.d("Final lastRecognizedText: '${voiceManager.lastRecognizedText.value}'", TAG)
                _phase.value = InteractionPhase.ERROR
                callback?.onError("没有检测到语音")
                // 确保完全停止后再提示用户
                voiceManager.cancelListening()
                voiceManager.speak("没有检测到语音，请重试") {
                    scope.launch {
                        delay(1000)
                        reset()
                    }
                }
            }
        }
    }

    /**
     * 处理语音识别结果
     * 注意：这里只更新识别文本，不自动触发AI处理
     * AI处理由用户点击悬浮球结束语音输入时触发
     */
    private fun handleVoiceResult(result: VoiceInteractionResult) {
        Logger.d("Voice result received: success=${result.success}, text='${result.text}', error='${result.errorMessage}'", TAG)
        
        if (result.success && result.text.isNotBlank()) {
            // 只更新识别文本，不自动触发AI处理
            _recognizedText.value = result.text
            callback?.onVoiceResult(result.text)
            Logger.i("Voice recognized (waiting for user to finish): ${result.text}", TAG)
            
            // 不自动触发AI处理！等待用户点击悬浮球
        } else if (result.errorMessage.isNotBlank()) {
            // 识别出错，记录日志但不自动处理
            Logger.w("Voice recognition error: ${result.errorMessage}", TAG)
            // 不自动处理错误，让用户可以继续尝试或点击悬浮球结束
        }
    }

    /**
     * 等待语音识别结果，给最终文本一个短暂的落地时间
     */
    private suspend fun waitForRecognizedText(timeoutMs: Long = 10000L): String {
        val existing = currentRecognizedText()
        if (existing.isNotBlank()) {
            Logger.d("Found existing text: '$existing'", TAG)
            return existing
        }

        Logger.d("No existing text, waiting for recognition result (timeout: ${timeoutMs}ms)...", TAG)
        
        // 等待新结果落地，每 500ms 检查一次
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // 检查是否有新文本
            val text = voiceManager.lastRecognizedText.value
            if (text.isNotBlank()) {
                Logger.d("Got recognition result: '$text'", TAG)
                return text
            }
            
            // 检查本地缓存
            val cached = _recognizedText.value
            if (cached.isNotBlank()) {
                Logger.d("Got cached result: '$cached'", TAG)
                return cached
            }
            
            // 检查识别是否完成（不再录音也不在上传）
            val voiceState = voiceManager.state.value
            if (voiceState == com.zigent.voice.VoiceInteractionState.IDLE ||
                voiceState == com.zigent.voice.VoiceInteractionState.ERROR) {
                // 识别已完成，最后再检查一次
                delay(200)
                val finalText = currentRecognizedText()
                if (finalText.isNotBlank()) {
                    Logger.d("Got final text after state change: '$finalText'", TAG)
                    return finalText
                }
                Logger.d("Recognition ended with no result, state: $voiceState", TAG)
                break
            }
            
            delay(300)
        }

        // 超时后最后检查一次
        val finalCheck = currentRecognizedText()
        Logger.d("Final check after timeout: '$finalCheck'", TAG)
        return finalCheck
    }

    /**
     * 获取当前最新的识别文本（本地缓存优先）
     */
    private fun currentRecognizedText(): String {
        return _recognizedText.value.ifBlank { voiceManager.lastRecognizedText.value }
    }

    /**
     * 开始AI处理
     */
    private fun startAiProcessing(userInput: String) {
        Logger.i("Starting AI processing: $userInput", TAG)
        
        _phase.value = InteractionPhase.AI_PROCESSING
        callback?.onPhaseChanged(InteractionPhase.AI_PROCESSING)
        
        // 检查AI是否已配置
        if (!agentEngine.isAiConfigured()) {
            Logger.e("AI not configured when trying to process", null, TAG)
            failTask("AI未配置，请在设置中配置AI密钥")
            return
        }
        
        // 播报确认
        voiceManager.speak("收到：$userInput，正在为您处理") {
            // 播报完成后启动Agent执行任务
            Logger.i("Starting agent task for: $userInput", TAG)
            agentEngine.startTask(userInput)
        }
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

