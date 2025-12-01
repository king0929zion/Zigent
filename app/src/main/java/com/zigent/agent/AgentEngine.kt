package com.zigent.agent

import android.content.Context
import com.zigent.accessibility.ZigentAccessibilityService
import com.zigent.agent.models.*
import com.zigent.ai.AiConfig
import com.zigent.ai.AiSettings
import com.zigent.shizuku.ShizukuManager
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
    ANALYZING,      // 分析任务中
    PLANNING,       // 规划中
    EXECUTING,      // 执行中
    WAITING_USER,   // 等待用户输入
    PAUSED,         // 暂停
    COMPLETED,      // 完成
    FAILED          // 失败
}

/**
 * 任务类型
 */
enum class TaskType {
    SIMPLE_CHAT,    // 简单对话（不需要操作手机）
    APP_OPERATION,  // 应用操作（打开应用、发消息等）
    SYSTEM_CONTROL, // 系统控制（设置、截图等）
    INFORMATION,    // 信息查询
    UNKNOWN         // 未知类型
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
    fun onAskUser(question: String)
}

/**
 * Agent能力状态
 */
data class AgentCapabilities(
    val hasAccessibility: Boolean,
    val hasShizuku: Boolean,
    val hasAdb: Boolean,
    val canTakeScreenshot: Boolean,
    val canInputText: Boolean
) {
    val canExecuteActions: Boolean get() = hasAccessibility || hasShizuku || hasAdb
    
    fun getDescription(): String {
        val capabilities = mutableListOf<String>()
        if (hasAccessibility) capabilities.add("无障碍")
        if (hasShizuku) capabilities.add("Shizuku")
        if (hasAdb) capabilities.add("ADB")
        return if (capabilities.isEmpty()) "无可用执行方式" else capabilities.joinToString(", ")
    }
}

/**
 * Agent执行引擎
 * 核心控制循环：分析任务 -> 采集屏幕 -> AI决策 -> 执行操作 -> 检查结果 -> 循环
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
    
    // 任务历史（保存最近的任务）
    private val taskHistory = mutableListOf<TaskHistoryItem>()
    private val maxHistorySize = 50
    
    // AI决策器
    private var actionDecider: ActionDecider? = null
    
    // AI设置
    private var aiSettings: AiSettings? = null
    
    // Shizuku 管理器
    private val shizukuManager: ShizukuManager by lazy {
        ShizukuManager.getInstance(context)
    }
    
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
     * 获取当前能力状态
     */
    fun getCapabilities(): AgentCapabilities {
        return AgentCapabilities(
            hasAccessibility = ZigentAccessibilityService.isServiceAvailable(),
            hasShizuku = shizukuManager.isAvailable(),
            hasAdb = false, // 普通 ADB 一般不可用
            canTakeScreenshot = shizukuManager.isAvailable(),
            canInputText = shizukuManager.isAvailable()
        )
    }

    /**
     * 开始执行任务
     * @param userInput 用户输入的任务描述
     */
    fun startTask(userInput: String) {
        if (_state.value != AgentState.IDLE && _state.value != AgentState.COMPLETED && _state.value != AgentState.FAILED) {
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
     * 执行任务主流程
     */
    private suspend fun executeTask(task: String) {
        Logger.i("Starting task: $task", TAG)
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. 分析任务类型
            _state.value = AgentState.ANALYZING
            callback?.onStateChanged(AgentState.ANALYZING)
            callback?.onProgress("正在分析任务...")
            
            val taskAnalysis = analyzeTask(task)
            Logger.i("Task analysis: type=${taskAnalysis.needsExecution}, isChat=${taskAnalysis.isSimpleChat}", TAG)
            
            // 2. 根据任务类型选择执行模式
            if (taskAnalysis.isSimpleChat) {
                // 简单对话模式
                executeSimpleChatMode(task)
            } else {
                // 检查是否有执行能力
                val capabilities = getCapabilities()
                if (!capabilities.canExecuteActions) {
                    Logger.w("No execution capability available", TAG)
                    callback?.onProgress("无法执行操作，切换到对话模式")
                    executeSimpleChatMode(task)
                } else {
                    // 完整执行模式
                    executeFullMode(task, taskAnalysis)
                }
            }
            
            // 保存任务历史
            saveTaskHistory(task, startTime)
            
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
     * 分析任务类型
     */
    private suspend fun analyzeTask(task: String): TaskAnalysis {
        val decider = actionDecider ?: return TaskAnalysis(task, false, true)
        
        return try {
            decider.analyzeTask(task)
        } catch (e: Exception) {
            Logger.e("Task analysis failed", e, TAG)
            // 默认当作需要执行的任务
            TaskAnalysis(task, true, false)
        }
    }

    /**
     * 完整执行模式 - 支持屏幕操作
     */
    private suspend fun executeFullMode(task: String, analysis: TaskAnalysis) {
        Logger.i("Executing full mode for: $task", TAG)
        
        _state.value = AgentState.PLANNING
        callback?.onStateChanged(AgentState.PLANNING)
        
        // 如果需要打开应用，先打开
        analysis.targetApp?.let { appName ->
            callback?.onProgress("正在打开 $appName...")
            val openAction = AgentAction(
                type = ActionType.OPEN_APP,
                description = "打开 $appName",
                appName = appName
            )
            val result = actionExecutor.execute(openAction)
            if (!result.success) {
                Logger.w("Failed to open app: $appName", TAG)
            }
            delay(1500) // 等待应用启动
        }
        
        _state.value = AgentState.EXECUTING
        callback?.onStateChanged(AgentState.EXECUTING)
        
        var stepCount = 0
        var consecutiveErrors = 0
        
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
            val screenState = try {
                captureScreen()
            } catch (e: Exception) {
                Logger.e("Screen capture failed", e, TAG)
                ScreenState(
                    packageName = "unknown",
                    activityName = null,
                    screenDescription = "无法获取屏幕",
                    uiElements = emptyList(),
                    screenshotBase64 = null
                )
            }
            
            // 2. AI决策
            callback?.onProgress("AI正在思考...")
            val decision = makeDecision(task, screenState)
            
            Logger.d("AI thought: ${decision.thought}", TAG)
            Logger.d("AI action: ${decision.action.type} - ${decision.action.description}", TAG)
            
            // 3. 检查特殊操作类型
            when (decision.action.type) {
                ActionType.FINISHED -> {
                    _state.value = AgentState.COMPLETED
                    callback?.onStateChanged(AgentState.COMPLETED)
                    callback?.onTaskCompleted(decision.action.resultMessage ?: "任务完成")
                    Logger.i("Task completed: ${decision.action.resultMessage}", TAG)
                    return
                }
                ActionType.FAILED -> {
                    _state.value = AgentState.FAILED
                    callback?.onStateChanged(AgentState.FAILED)
                    callback?.onTaskFailed(decision.action.resultMessage ?: "任务失败")
                    Logger.e("Task failed: ${decision.action.resultMessage}", TAG)
                    return
                }
                ActionType.ASK_USER -> {
                    _state.value = AgentState.WAITING_USER
                    callback?.onStateChanged(AgentState.WAITING_USER)
                    callback?.onAskUser(decision.action.question ?: "需要您的确认")
                    return
                }
                else -> { /* 继续执行 */ }
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
    }

    /**
     * 简单对话模式 - 当不需要操作手机或无法操作时使用
     */
    private suspend fun executeSimpleChatMode(task: String) {
        Logger.i("Executing simple chat mode for: $task", TAG)
        
        _state.value = AgentState.EXECUTING
        callback?.onStateChanged(AgentState.EXECUTING)
        callback?.onProgress("正在思考...")
        
        try {
            val decider = actionDecider ?: throw IllegalStateException("ActionDecider not initialized")
            
            // 直接使用简单对话
            val response = decider.simpleChat(task)
            
            Logger.d("Simple chat response: $response", TAG)
            
            // 返回结果
            _state.value = AgentState.COMPLETED
            callback?.onStateChanged(AgentState.COMPLETED)
            callback?.onTaskCompleted(response)
            Logger.i("Simple chat completed", TAG)
            
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
     * 保存任务历史
     */
    private fun saveTaskHistory(task: String, startTime: Long) {
        val historyItem = TaskHistoryItem(
            id = UUID.randomUUID().toString(),
            task = task,
            status = _state.value,
            startTime = startTime,
            endTime = System.currentTimeMillis(),
            steps = executionHistory.size
        )
        
        taskHistory.add(0, historyItem) // 添加到开头
        
        // 限制历史记录数量
        while (taskHistory.size > maxHistorySize) {
            taskHistory.removeAt(taskHistory.size - 1)
        }
    }

    /**
     * 回答用户问题（用于 ASK_USER 状态后继续）
     */
    fun answerQuestion(answer: String) {
        if (_state.value != AgentState.WAITING_USER) return
        
        // 将用户答案作为新任务的一部分继续执行
        val originalTask = currentTask?.userInput ?: return
        val newTask = "$originalTask\n用户回答: $answer"
        
        _state.value = AgentState.IDLE
        startTask(newTask)
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
     * 获取任务历史
     */
    fun getTaskHistory(): List<TaskHistoryItem> = taskHistory.toList()

    /**
     * 清除任务历史
     */
    fun clearTaskHistory() {
        taskHistory.clear()
    }

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

/**
 * 任务历史记录
 */
data class TaskHistoryItem(
    val id: String,
    val task: String,
    val status: AgentState,
    val startTime: Long,
    val endTime: Long,
    val steps: Int
) {
    val duration: Long get() = endTime - startTime
    val isSuccess: Boolean get() = status == AgentState.COMPLETED
}
