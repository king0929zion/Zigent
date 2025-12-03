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
    WAITING_ANSWER,     // 等待用户回答 AI 的问题
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

    // 保存 AI 的问题，用于在用户回答后继续
    private var pendingAiQuestion: String? = null

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
                    AgentState.WAITING_USER -> InteractionPhase.WAITING_ANSWER  // 改为等待回答状态
                    AgentState.PAUSED -> InteractionPhase.TASK_EXECUTING
                    AgentState.COMPLETED -> InteractionPhase.COMPLETED
                    AgentState.FAILED -> InteractionPhase.ERROR
                }
                _phase.value = phase
                callback?.onPhaseChanged(phase)
            }

            override fun onStepStarted(step: Int, description: String) {
                Logger.i("Step $step started: $description", TAG)
                callback?.onTaskProgress(description)
            }

            override fun onStepCompleted(step: Int, success: Boolean, message: String) {
                val status = if (success) "✓" else "✗"
                Logger.d("Step $step $status: $message", TAG)
                if (!success) {
                    callback?.onTaskProgress("第${step}步失败: ${message.take(30)}")
                }
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
                // 当AI需要询问用户时
                Logger.i("AI asking user: $question", TAG)
                pendingAiQuestion = question
                callback?.onAiResponse(question)
                
                // 通过TTS朗读问题，并提示用户点击悬浮球回答
                voiceManager.speak("$question。请点击悬浮球回答。") {
                    // 朗读完成后等待用户点击
                    Logger.d("AI question spoken, waiting for user to click floating ball", TAG)
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
            InteractionPhase.WAITING_ANSWER -> {
                // 等待用户回答 AI 问题 -> 开始语音输入回答
                Logger.i("User clicked to answer AI question", TAG)
                startVoiceInputForAnswer()
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
     * 开始语音输入来回答 AI 的问题
     */
    private fun startVoiceInputForAnswer() {
        Logger.i("Starting voice input to answer AI question", TAG)
        
        // 标记为回答模式
        isAnsweringAiQuestion = true
        
        _phase.value = InteractionPhase.VOICE_INPUT
        _recognizedText.value = ""
        callback?.onPhaseChanged(InteractionPhase.VOICE_INPUT)
        
        // 停止当前正在播放的 TTS
        if (voiceManager.isSpeaking()) {
            voiceManager.stopSpeaking()
        }
        
        // 开始语音识别（简短提示后开始）
        voiceManager.speak("请回答") {
            voiceManager.startListening()
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
    
    // 是否正在回答 AI 的问题
    private var isAnsweringAiQuestion = false

    /**
     * 完成语音输入并处理结果
     * 用户再次点击悬浮球时调用
     */
    private fun finishVoiceInput() {
        Logger.i("=== finishVoiceInput() called ===", TAG)
        Logger.d("Current _recognizedText: '${_recognizedText.value}'", TAG)
        Logger.d("Current lastRecognizedText: '${voiceManager.lastRecognizedText.value}'", TAG)
        Logger.d("isAnsweringAiQuestion: $isAnsweringAiQuestion", TAG)
        
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
            processRecognizedText(existingText)
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
                processRecognizedText(finalText)
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
                        // 如果是回答模式，返回等待状态而不是 IDLE
                        if (isAnsweringAiQuestion) {
                            _phase.value = InteractionPhase.WAITING_ANSWER
                            callback?.onPhaseChanged(InteractionPhase.WAITING_ANSWER)
                            callback?.onTaskProgress("AI询问: ${pendingAiQuestion}")
                        } else {
                            reset()
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 处理识别到的文本
     * 根据当前是新任务还是回答 AI 问题，选择不同的处理方式
     */
    private fun processRecognizedText(text: String) {
        if (isAnsweringAiQuestion && pendingAiQuestion != null) {
            // 回答 AI 的问题
            Logger.i("Answering AI question with: $text", TAG)
            isAnsweringAiQuestion = false
            pendingAiQuestion = null
            
            _phase.value = InteractionPhase.AI_PROCESSING
            callback?.onPhaseChanged(InteractionPhase.AI_PROCESSING)
            
            voiceManager.speak("收到您的回答：$text") {
                // 调用 AgentEngine 的回答方法
                agentEngine.answerQuestion(text)
            }
        } else {
            // 新任务
            startAiProcessing(text)
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
        isAnsweringAiQuestion = false
        pendingAiQuestion = null
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
            InteractionPhase.WAITING_ANSWER -> FloatingBallState.PROCESSING  // 等待回答时显示为处理中
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
