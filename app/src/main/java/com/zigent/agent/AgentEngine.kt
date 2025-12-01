package com.zigent.agent

import android.content.Context
import com.zigent.agent.models.*
import com.zigent.ai.AiConfig
import com.zigent.ai.AiSettings
import com.zigent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent状态
 */
enum class AgentState {
    IDLE,           // 空闲
    PLANNING,       // 规划中
    EXECUTING,      // 执行中
    PAUSED,         // 暂停
    COMPLETED,      // 完成
    FAILED          // 失败
}

/**
 * Agent事件回调
 */
interface AgentCallback {
    fun onStateChanged(state: AgentState)
    fun onStepStarted(step: Int, description: String)
    fun onStepCompleted(step: Int, success: Boolean, message: String)
    fun onProgress(message: String)
    fun onTaskCompleted(result: String)
    fun onTaskFailed(error: String)
}

/**
 * Agent执行引擎
 * 核心控制循环：分析屏幕 -> AI决策 -> 执行操作 -> 检查结果 -> 循环
 */
@Singleton
class AgentEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screenAnalyzer: ScreenAnalyzer,
    private val actionExecutor: ActionExecutor
) {
    companion object {
        private const val TAG = "AgentEngine"
    }

    // 协程作用域
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // 当前执行的Job
    private var executionJob: Job? = null
    
    // Agent状态
    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()
    
    // 当前任务
    private var currentTask: AgentTask? = null
    
    // 执行历史
    private val executionHistory = mutableListOf<AgentStep>()
    
    // AI决策器
    private var actionDecider: ActionDecider? = null
    
    // AI设置
    private var aiSettings: AiSettings? = null
    
    // 回调
    var callback: AgentCallback? = null
    
    // 是否使用视觉模式（带截图）
    var useVisionMode: Boolean = true

    /**
     * 配置AI设置
     */
    fun configureAi(settings: AiSettings) {
        aiSettings = settings
        actionDecider = ActionDecider(settings)
        Logger.i("AI configured: ${settings.provider}", TAG)
    }

    /**
     * 开始执行任务
     * @param userInput 用户输入的任务描述
     */
    fun startTask(userInput: String) {
        if (_state.value != AgentState.IDLE) {
            Logger.w("Agent is busy, cannot start new task", TAG)
            return
        }
        
        if (actionDecider == null) {
            callback?.onTaskFailed("请先配置AI设置")
            return
        }
        
        // 创建任务
        currentTask = AgentTask(
            id = UUID.randomUUID().toString(),
            userInput = userInput,
            taskDescription = userInput,
            steps = emptyList(),
            status = TaskStatus.PENDING
        )
        
        executionHistory.clear()
        
        // 启动执行
        executionJob = engineScope.launch {
            executeTask(userInput)
        }
    }

    /**
     * 执行任务主循环
     */
    private suspend fun executeTask(task: String) {
        Logger.i("Starting task: $task", TAG)
        
        _state.value = AgentState.PLANNING
        callback?.onStateChanged(AgentState.PLANNING)
        callback?.onProgress("正在分析任务...")
        
        var stepCount = 0
        var consecutiveErrors = 0
        
        try {
            // 先尝试采集屏幕状态，如果失败则使用简单对话模式
            val initialScreenState = try {
                captureScreen()
            } catch (e: Exception) {
                Logger.w("Screen capture failed, using simple chat mode: ${e.message}", TAG)
                null
            }
            
            // 如果无法获取屏幕状态，使用简单对话模式
            if (initialScreenState == null || initialScreenState.uiElements.isEmpty()) {
                Logger.i("Using simple chat mode for task: $task", TAG)
                executeSimpleChatMode(task)
                return
            }
            
            _state.value = AgentState.EXECUTING
            callback?.onStateChanged(AgentState.EXECUTING)
            
            while (stepCount < AiConfig.MAX_AGENT_STEPS) {
                // 检查是否被取消
                if (!coroutineContext.isActive) {
                    Logger.i("Task cancelled", TAG)
                    break
                }
                
                stepCount++
                Logger.d("=== Step $stepCount ===", TAG)
                callback?.onStepStarted(stepCount, "分析屏幕...")
                
                // 1. 采集屏幕状态
                val screenState = captureScreen()
                
                // 2. AI决策
                callback?.onProgress("AI正在思考...")
                val decision = makeDecision(task, screenState)
                
                Logger.d("AI thought: ${decision.thought}", TAG)
                Logger.d("AI action: ${decision.action.type} - ${decision.action.description}", TAG)
                
                // 3. 检查是否完成/失败
                if (decision.action.type == ActionType.FINISHED) {
                    _state.value = AgentState.COMPLETED
                    callback?.onStateChanged(AgentState.COMPLETED)
                    callback?.onTaskCompleted(decision.action.resultMessage ?: "任务完成")
                    Logger.i("Task completed: ${decision.action.resultMessage}", TAG)
                    return
                }
                
                if (decision.action.type == ActionType.FAILED) {
                    _state.value = AgentState.FAILED
                    callback?.onStateChanged(AgentState.FAILED)
                    callback?.onTaskFailed(decision.action.resultMessage ?: "任务失败")
                    Logger.e("Task failed: ${decision.action.resultMessage}", TAG)
                    return
                }
                
                // 4. 执行操作
                callback?.onProgress("执行: ${decision.action.description}")
                val result = actionExecutor.execute(decision.action)
                
                // 5. 记录步骤
                val step = AgentStep(
                    stepNumber = stepCount,
                    screenStateBefore = screenState.screenDescription,
                    action = decision.action,
                    screenStateAfter = null,
                    success = result.success,
                    errorMessage = result.errorMessage
                )
                executionHistory.add(step)
                
                // 6. 处理执行结果
                if (result.success) {
                    consecutiveErrors = 0
                    callback?.onStepCompleted(stepCount, true, result.message)
                } else {
                    consecutiveErrors++
                    callback?.onStepCompleted(stepCount, false, result.errorMessage ?: "执行失败")
                    
                    // 连续错误过多则终止
                    if (consecutiveErrors >= 3) {
                        _state.value = AgentState.FAILED
                        callback?.onStateChanged(AgentState.FAILED)
                        callback?.onTaskFailed("连续执行失败，任务终止")
                        Logger.e("Too many consecutive errors, aborting", TAG)
                        return
                    }
                }
                
                // 7. 等待下一步
                delay(AiConfig.STEP_DELAY)
            }
            
            // 达到最大步数
            if (stepCount >= AiConfig.MAX_AGENT_STEPS) {
                _state.value = AgentState.FAILED
                callback?.onStateChanged(AgentState.FAILED)
                callback?.onTaskFailed("超过最大执行步数，任务终止")
                Logger.e("Max steps reached", TAG)
            }
            
        } catch (e: CancellationException) {
            Logger.i("Task cancelled by user", TAG)
            _state.value = AgentState.IDLE
            callback?.onStateChanged(AgentState.IDLE)
        } catch (e: Exception) {
            Logger.e("Task execution error", e, TAG)
            _state.value = AgentState.FAILED
            callback?.onStateChanged(AgentState.FAILED)
            callback?.onTaskFailed("执行出错: ${e.message}")
        }
    }

    /**
     * 简单对话模式 - 当无法获取屏幕状态时使用
     * 直接调用AI进行对话，不执行任何操作
     */
    private suspend fun executeSimpleChatMode(task: String) {
        Logger.i("Executing simple chat mode for: $task", TAG)
        
        _state.value = AgentState.EXECUTING
        callback?.onStateChanged(AgentState.EXECUTING)
        callback?.onProgress("正在思考...")
        
        try {
            val decider = actionDecider ?: throw IllegalStateException("ActionDecider not initialized")
            
            // 创建一个简单的对话请求
            val simplePrompt = """
用户说: "$task"

请直接回复用户，给出有帮助的回答。
如果用户要求执行手机操作（如打开应用、发送消息等），请告诉用户你目前无法控制手机，但可以提供指导。
回复要简洁友好。

请用以下JSON格式回复：
{
    "thought": "你的思考过程",
    "action": {
        "action": "FINISHED",
        "description": "回复用户",
        "message": "你的回复内容"
    }
}
""".trimIndent()
            
            // 创建一个空的屏幕状态
            val emptyScreenState = ScreenState(
                packageName = "unknown",
                activityName = null,
                screenDescription = "无法获取屏幕信息",
                uiElements = emptyList(),
                screenshotBase64 = null
            )
            
            // 调用AI
            val decision = decider.decide(task, emptyScreenState, emptyList())
            
            Logger.d("Simple chat response: ${decision.thought}", TAG)
            
            // 返回结果
            _state.value = AgentState.COMPLETED
            callback?.onStateChanged(AgentState.COMPLETED)
            
            val responseMessage = decision.action.resultMessage 
                ?: decision.thought.take(200)
                ?: "抱歉，我无法理解您的请求"
            
            callback?.onTaskCompleted(responseMessage)
            Logger.i("Simple chat completed: $responseMessage", TAG)
            
        } catch (e: Exception) {
            Logger.e("Simple chat mode failed", e, TAG)
            _state.value = AgentState.FAILED
            callback?.onStateChanged(AgentState.FAILED)
            callback?.onTaskFailed("AI响应失败: ${e.message}")
        }
    }

    /**
     * 采集屏幕状态
     */
    private suspend fun captureScreen(): ScreenState {
        return screenAnalyzer.captureScreenState()
    }

    /**
     * AI决策
     */
    private suspend fun makeDecision(task: String, screenState: ScreenState): AiDecision {
        val decider = actionDecider ?: throw IllegalStateException("ActionDecider not initialized")
        
        return if (useVisionMode && !screenState.screenshotBase64.isNullOrEmpty()) {
            decider.decideWithVision(task, screenState, executionHistory)
        } else {
            decider.decide(task, screenState, executionHistory)
        }
    }

    /**
     * 暂停任务
     */
    fun pauseTask() {
        if (_state.value == AgentState.EXECUTING) {
            _state.value = AgentState.PAUSED
            callback?.onStateChanged(AgentState.PAUSED)
            Logger.i("Task paused", TAG)
        }
    }

    /**
     * 恢复任务
     */
    fun resumeTask() {
        if (_state.value == AgentState.PAUSED) {
            _state.value = AgentState.EXECUTING
            callback?.onStateChanged(AgentState.EXECUTING)
            Logger.i("Task resumed", TAG)
        }
    }

    /**
     * 取消任务
     */
    fun cancelTask() {
        executionJob?.cancel()
        executionJob = null
        currentTask = null
        executionHistory.clear()
        _state.value = AgentState.IDLE
        callback?.onStateChanged(AgentState.IDLE)
        Logger.i("Task cancelled", TAG)
    }

    /**
     * 获取当前任务
     */
    fun getCurrentTask(): AgentTask? = currentTask

    /**
     * 获取执行历史
     */
    fun getExecutionHistory(): List<AgentStep> = executionHistory.toList()

    /**
     * 检查AI是否已配置
     */
    fun isAiConfigured(): Boolean = actionDecider != null

    /**
     * 测试AI连接
     */
    suspend fun testAiConnection(): Boolean {
        return actionDecider?.testConnection() ?: false
    }

    /**
     * 释放资源
     */
    fun release() {
        cancelTask()
        engineScope.cancel()
    }
}

