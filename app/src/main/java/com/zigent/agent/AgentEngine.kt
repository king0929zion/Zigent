package com.zigent.agent

import android.content.Context
import com.zigent.accessibility.ZigentAccessibilityService
import com.zigent.agent.models.*
import com.zigent.ai.AiConfig
import com.zigent.ai.AiSettings
import com.zigent.shizuku.ShizukuManager
import com.zigent.utils.InstalledAppsHelper
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
    
    // 操作验证器
    // ActionVerifier（操作验证器）
    private val actionVerifier by lazy { ActionVerifier(context) }
    
    // 任务分解器
    private val taskDecomposer = TaskDecomposer()
    
    // Shizuku 管理器
    private val shizukuManager: ShizukuManager by lazy {
        ShizukuManager.getInstance(context)
    }
    
    // 回调
    var callback: AgentCallback? = null
    
    // 是否使用视觉模式（带截图）
    var useVisionMode: Boolean = true
    
    // 是否启用操作验证
    var enableActionVerification: Boolean = true
    
    // 是否启用任务分解
    var enableTaskDecomposition: Boolean = true

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
        // 允许在这些状态下启动新任务
        val canStart = _state.value in listOf(
            AgentState.IDLE, 
            AgentState.COMPLETED, 
            AgentState.FAILED,
            AgentState.WAITING_USER  // 允许从等待状态启动新任务
        )
        
        if (!canStart) {
            Logger.w("Agent is busy (${_state.value}), cannot start new task", TAG)
            return
        }
        
        if (actionDecider == null) {
            callback?.onTaskFailed("请先配置AI设置")
            return
        }
        
        // 取消之前的任务（如果有）
        executionJob?.cancel()
        executionJob = null
        
        // 重置状态
        _state.value = AgentState.IDLE
        
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
            // 0. 准备设备上下文（已安装应用列表 + 初始屏幕状态）
            _state.value = AgentState.ANALYZING
            callback?.onStateChanged(AgentState.ANALYZING)
            callback?.onProgress("正在获取设备信息...")
            
            prepareDeviceContext()
            
            // 1. 分析任务类型
            callback?.onProgress("正在分析任务...")
            
            val taskAnalysis = analyzeTask(task)
            Logger.i("Task analysis: type=${taskAnalysis.needsExecution}, isChat=${taskAnalysis.isSimpleChat}", TAG)
            
            // 支付/转账等敏感操作需要用户确认
            if (taskAnalysis.requiresUserConfirmation && taskAnalysis.needsExecution) {
                _state.value = AgentState.WAITING_USER
                callback?.onStateChanged(AgentState.WAITING_USER)
                callback?.onAskUser("检测到可能涉及支付/转账/下单等敏感操作，是否确认继续执行？")
                return
            }
            
            // 2. 根据任务类型选择执行模式
            if (taskAnalysis.isSimpleChat) {
                // 简单对话模式
                executeSimpleChatMode(task)
            } else {
                // 任务规划：始终先让 AI 规划步骤，便于复杂任务按顺序执行
                var preplannedTask: com.zigent.agent.DecomposedTask? = null
                try {
                    preplannedTask = taskDecomposer.decompose(task)
                    currentTask = currentTask?.copy(
                        steps = preplannedTask.subTasks.map { it.description }
                    )
                    val summary = taskDecomposer.generateTaskSummary(preplannedTask)
                    callback?.onProgress(summary)
                } catch (e: Exception) {
                    Logger.w("Task decomposition failed, fallback to inline decisions", TAG)
                }
                
                // 检查是否有执行能力
                val capabilities = getCapabilities()
                if (!capabilities.canExecuteActions) {
                    Logger.w("No execution capability available", TAG)
                    callback?.onProgress("无法执行操作，切换到对话模式")
                    executeSimpleChatMode(task)
                } else {
                    // 完整执行模式
                    executeFullMode(task, taskAnalysis, preplannedTask)
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
     * 准备设备上下文
     * 获取已安装应用列表和当前屏幕状态
     */
    private suspend fun prepareDeviceContext() = withContext(Dispatchers.IO) {
        val decider = actionDecider ?: return@withContext
        
        try {
            // 并行获取应用列表和屏幕状态
            coroutineScope {
                val appsDeferred = async {
                    InstalledAppsHelper.generateAppsContext(context)
                }
                val screenDeferred = async {
                    try {
                        val screen = screenAnalyzer.captureScreenState()
                        generateInitialScreenDescription(screen)
                    } catch (e: Exception) {
                        Logger.e("Failed to capture initial screen", e, TAG)
                        null
                    }
                }
                
                val appsContext = appsDeferred.await()
                val initialScreen = screenDeferred.await()
                
                // 设置设备上下文
                decider.setDeviceContext(
                    ActionDecider.DeviceContext(
                        installedAppsText = appsContext,
                        initialScreenState = initialScreen
                    )
                )
                
                Logger.i("Device context prepared: apps=${appsContext.length} chars, screen=${initialScreen?.length ?: 0} chars", TAG)
            }
        } catch (e: Exception) {
            Logger.e("Failed to prepare device context", e, TAG)
        }
    }
    
    /**
     * 生成初始屏幕描述
     */
    private fun generateInitialScreenDescription(screen: ScreenState): String {
        val sb = StringBuilder()
        
        sb.appendLine("当前位于: ${com.zigent.utils.AppUtils.getAppName(screen.packageName)}")
        screen.activityName?.let {
            sb.appendLine("页面: ${it.substringAfterLast(".")}")
        }
        
        // 主要可交互元素
        val clickables = screen.uiElements.filter { it.isClickable }.take(10)
        val editables = screen.uiElements.filter { it.isEditable }.take(5)
        
        if (clickables.isNotEmpty()) {
            sb.appendLine("可点击: ${clickables.map { it.text.ifEmpty { it.description }.take(15) }.filter { it.isNotEmpty() }.joinToString(", ")}")
        }
        if (editables.isNotEmpty()) {
            sb.appendLine("输入框: ${editables.size} 个")
        }
        
        return sb.toString()
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
    private suspend fun executeFullMode(
        task: String,
        analysis: TaskAnalysis,
        preplannedTask: DecomposedTask? = null
    ) {
        Logger.i("Executing full mode for: $task", TAG)
        
        _state.value = AgentState.PLANNING
        callback?.onStateChanged(AgentState.PLANNING)
        
        // 任务分解（如果启用）
        var decomposedTask: DecomposedTask? = preplannedTask
        if (enableTaskDecomposition && decomposedTask == null) {
            decomposedTask = taskDecomposer.decompose(task)
            Logger.i("Task decomposed: ${decomposedTask.subTasks.size} sub-tasks, complexity: ${decomposedTask.complexity}", TAG)
            
            // 显示任务分解概要
            callback?.onProgress("分析任务: ${decomposedTask.complexity.name}, 预计${decomposedTask.estimatedSteps}步")
            
            // 如果需要用户输入，询问
            if (decomposedTask.requiresUserInput) {
                Logger.i("Task requires user input", TAG)
                // 这里可以触发用户输入，但目前先继续执行
            }
        }
        
        // 如果需要打开应用，先打开（优先使用分解结果中的目标应用）
        val targetApp = decomposedTask?.targetApp ?: analysis.targetApp
        targetApp?.let { appName ->
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
        val maxConsecutiveErrors = 3  // 最多允许3次连续错误
        var lastActionWasVlm = false  // 上一步是否是 VLM 调用
        var lastVlmError: String? = null  // 上次 VLM 调用失败的错误信息
        
        // 检测连续相同操作（需要失败才计数）
        var lastActionKey: String? = null  // 上一个操作的唯一标识
        var sameActionFailCount = 0  // 连续相同操作失败计数
        val maxSameActionRetries = 3  // 最多允许连续 3 次相同操作失败
        
        while (stepCount < AiConfig.MAX_AGENT_STEPS) {
            // 检查是否被取消
            if (!coroutineContext.isActive) {
                Logger.i("Task cancelled", TAG)
                break
            }
            
            stepCount++
            Logger.d("=== Step $stepCount ===", TAG)
            callback?.onStepStarted(stepCount, "第${stepCount}步: 分析屏幕")
            callback?.onProgress("第${stepCount}步: 正在分析屏幕...")
            
            // 1. 采集屏幕状态（添加超时保护）
            val screenState = try {
                withTimeoutOrNull(10_000L) {
                    captureScreen()
                } ?: run {
                    Logger.w("Screen capture timeout", TAG)
                    callback?.onProgress("屏幕采集超时，使用简化信息...")
                    ScreenState(
                        packageName = "unknown",
                        activityName = null,
                        screenDescription = "屏幕采集超时",
                        uiElements = emptyList(),
                        screenshotBase64 = null
                    )
                }
            } catch (e: Exception) {
                Logger.e("Screen capture failed", e, TAG)
                callback?.onProgress("屏幕采集失败: ${e.message?.take(50)}")
                ScreenState(
                    packageName = "unknown",
                    activityName = null,
                    screenDescription = "无法获取屏幕",
                    uiElements = emptyList(),
                    screenshotBase64 = null
                )
            }
            
            // 2. AI决策
            callback?.onProgress("第${stepCount}步: AI正在分析...")

            val decision = if (screenState.uiElements.isEmpty() && actionDecider?.isVlmAvailable() == true && screenState.screenshotBase64 != null) {
                val describeAction = AgentAction(
                    type = ActionType.DESCRIBE_SCREEN,
                    description = "元素为空，获取屏幕视觉描述",
                    text = "元素为空，使用视觉分析"
                )
                callback?.onProgress("元素列表为空，先获取屏幕视觉描述")
                executionHistory.add(
                    AgentStep(
                        stepNumber = stepCount,
                        screenStateBefore = screenState.screenDescription,
                        action = describeAction,
                        screenStateAfter = null,
                        success = true
                    )
                )
                lastActionWasVlm = true
                AiDecision(
                    thought = "元素为空，先调用视觉描述后再决策",
                    action = describeAction
                )
            } else {
                makeDecision(task, screenState)
            }
            
            Logger.d("AI thought: ${decision.thought}", TAG)
            Logger.d("AI action: ${decision.action.type} - ${decision.action.description}", TAG)
            
            // 检测连续相同操作（只有失败时才计数）
            val currentActionKey = buildActionKey(decision.action)
            // 注：这里不立即检查，等执行后根据结果再判断
            
            // 3. 检查特殊操作类型
            when (decision.action.type) {
                ActionType.FINISHED -> {
                    _state.value = AgentState.COMPLETED
                    callback?.onStateChanged(AgentState.COMPLETED)
                    callback?.onTaskCompleted(decision.action.resultMessage ?: "任务完成")
                    Logger.i("Task completed: ${decision.action.resultMessage}", TAG)
                    // 延迟后重置为 IDLE
                    delay(1000)
                    _state.value = AgentState.IDLE
                    callback?.onStateChanged(AgentState.IDLE)
                    return
                }
                ActionType.FAILED -> {
                    _state.value = AgentState.FAILED
                    callback?.onStateChanged(AgentState.FAILED)
                    callback?.onTaskFailed(decision.action.resultMessage ?: "任务失败")
                    Logger.e("Task failed: ${decision.action.resultMessage}", TAG)
                    // 延迟后重置为 IDLE
                    delay(1000)
                    _state.value = AgentState.IDLE
                    callback?.onStateChanged(AgentState.IDLE)
                    return
                }
                ActionType.ASK_USER -> {
                    _state.value = AgentState.WAITING_USER
                    callback?.onStateChanged(AgentState.WAITING_USER)
                    callback?.onAskUser(decision.action.question ?: "需要您的确认")
                    // 不再 return，等待用户响应后继续或用户启动新任务
                    return
                }
                ActionType.DESCRIBE_SCREEN -> {
                    // 禁止连续调用 VLM
                    if (lastActionWasVlm) {
                        Logger.w("AI requested DESCRIBE_SCREEN consecutively, blocking", TAG)
                        callback?.onProgress("已获取过屏幕描述，继续执行...")
                        // 告诉 AI 不能连续调用 VLM，让它用已有信息决策
                        val errorDecision = actionDecider?.decide(
                            task, 
                            screenState, 
                            executionHistory, 
                            "[系统提示] 刚刚已经调用过 describe_screen，不能连续调用。请根据已有的屏幕元素信息执行下一步操作。"
                        )
                        if (errorDecision != null && errorDecision.action.type != ActionType.DESCRIBE_SCREEN) {
                            // 执行新决策
                            callback?.onProgress("执行: ${errorDecision.action.description}")
                            val result = actionExecutor.execute(errorDecision.action)
                            executionHistory.add(AgentStep(
                                stepNumber = stepCount,
                                screenStateBefore = screenState.screenDescription,
                                action = errorDecision.action,
                                screenStateAfter = null,
                                success = result.success,
                                errorMessage = result.errorMessage
                            ))
                            lastActionWasVlm = false
                        }
                        delay(AiConfig.STEP_DELAY)
                        continue
                    }
                    
                    callback?.onProgress("第${stepCount}步: 使用视觉模型分析屏幕...")
                    
                    try {
                        // 添加 VLM 调用超时保护
                        val vlmDescription = withTimeoutOrNull(45_000L) {
                            actionDecider?.describeScreen(
                                screenState.screenshotBase64,
                                decision.action.text  // focus
                            )
                        }
                        
                        if (vlmDescription != null) {
                            Logger.i("VLM description obtained, re-deciding...", TAG)
                            lastActionWasVlm = true
                            lastVlmError = null
                            
                            // 用 VLM 描述重新决策
                            val newDecision = actionDecider?.decide(task, screenState, executionHistory, vlmDescription)
                            if (newDecision != null && newDecision.action.type != ActionType.DESCRIBE_SCREEN) {
                                // 执行新决策
                                callback?.onProgress("执行: ${newDecision.action.description}")
                                val result = actionExecutor.execute(newDecision.action)
                                
                                executionHistory.add(AgentStep(
                                    stepNumber = stepCount,
                                    screenStateBefore = screenState.screenDescription,
                                    action = newDecision.action,
                                    screenStateAfter = null,
                                    success = result.success,
                                    errorMessage = result.errorMessage
                                ))
                                
                                if (result.success) {
                                    consecutiveErrors = 0
                                    callback?.onStepCompleted(stepCount, true, result.message)
                                    lastActionWasVlm = false  // 成功执行后重置
                                } else {
                                    consecutiveErrors++
                                    callback?.onStepCompleted(stepCount, false, result.errorMessage ?: "执行失败")
                                }
                            }
                        } else {
                            // VLM 调用失败，记录错误并告知 AI
                            lastVlmError = "VLM屏幕分析失败"
                            Logger.w("VLM description failed", TAG)
                            consecutiveErrors++
                        }
                    } catch (e: Exception) {
                        Logger.e("VLM call failed", e, TAG)
                        lastVlmError = "VLM调用异常: ${e.message}"
                        consecutiveErrors++
                    }
                    
                    delay(AiConfig.STEP_DELAY)
                    continue
                }
                else -> { /* 继续执行 */ }
            }
            
            // 4. 执行操作
            val actionDesc = decision.action.description.take(50)
            callback?.onProgress("第${stepCount}步: 执行 - $actionDesc")
            Logger.i("Executing action: ${decision.action.type} - $actionDesc", TAG)
            
            val result = try {
                withTimeoutOrNull(30_000L) {
                    actionExecutor.execute(decision.action)
                } ?: run {
                    Logger.w("Action execution timeout", TAG)
                    callback?.onProgress("操作执行超时")
                    ExecutionResult(false, "操作超时", "执行超时，请重试")
                }
            } catch (e: Exception) {
                Logger.e("Action execution failed", e, TAG)
                callback?.onProgress("操作执行失败: ${e.message?.take(50)}")
                ExecutionResult(false, "", e.message)
            }
            
            // 5. 操作验证（如果启用）
            var verificationResult: VerificationResult? = null
            var finalSuccess = result.success
            var finalErrorMessage = result.errorMessage
            
            if (enableActionVerification && result.success) {
                // 等待页面响应
                delay(AiConfig.ACTION_WAIT_TIME)
                
                // 获取执行后的屏幕状态
                val afterState = try {
                    captureScreen()
                } catch (e: Exception) {
                    Logger.e("Failed to capture screen after action", e, TAG)
                    null
                }
                
                if (afterState != null) {
                    verificationResult = actionVerifier.verify(decision.action, screenState, afterState)
                    
                    // 如果验证失败且置信度低，标记为失败
                    if (!verificationResult.success && verificationResult.confidence < 0.5f) {
                        finalSuccess = false
                        finalErrorMessage = verificationResult.message
                        Logger.w("Action verification failed: ${verificationResult.message}", TAG)
                        
                        // 给出建议
                        verificationResult.suggestion?.let { suggestion ->
                            Logger.i("Suggestion: $suggestion", TAG)
                        }
                    } else {
                        Logger.i("Action verified: ${verificationResult.message} (confidence: ${verificationResult.confidence})", TAG)
                    }
                }
            }
            
            // 6. 记录步骤
            val step = AgentStep(
                stepNumber = stepCount,
                screenStateBefore = screenState.screenDescription,
                action = decision.action,
                screenStateAfter = verificationResult?.message,
                success = finalSuccess,
                errorMessage = finalErrorMessage
            )
            executionHistory.add(step)
            
            // 7. 处理执行结果
            if (finalSuccess) {
                consecutiveErrors = 0
                // 成功后重置相同操作失败计数
                sameActionFailCount = 0
                lastActionKey = null
                val message = verificationResult?.message ?: result.message
                callback?.onStepCompleted(stepCount, true, message)
                lastActionWasVlm = false  // 成功执行非 VLM 操作后重置
            } else {
                consecutiveErrors++
                
                // 检查是否是相同操作失败
                if (currentActionKey == lastActionKey) {
                    sameActionFailCount++
                    Logger.w("Same action failed again: $currentActionKey (fail count: $sameActionFailCount)", TAG)
                    
                    if (sameActionFailCount >= maxSameActionRetries) {
                        Logger.e("Same action failed $sameActionFailCount times, aborting", TAG)
                        _state.value = AgentState.FAILED
                        callback?.onStateChanged(AgentState.FAILED)
                        callback?.onTaskFailed("相同操作连续失败 $sameActionFailCount 次，任务终止。操作: ${decision.action.description}")
                        delay(1000)
                        _state.value = AgentState.IDLE
                        callback?.onStateChanged(AgentState.IDLE)
                        return
                    }
                } else {
                    // 操作不同，重置计数
                    sameActionFailCount = 1
                    lastActionKey = currentActionKey
                }
                
                callback?.onStepCompleted(stepCount, false, finalErrorMessage ?: "执行失败")
                
                // 连续错误过多则终止
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    _state.value = AgentState.FAILED
                    callback?.onStateChanged(AgentState.FAILED)
                    callback?.onTaskFailed("连续执行失败 $consecutiveErrors 次，任务终止")
                    Logger.e("Too many consecutive errors ($consecutiveErrors), aborting", TAG)
                    delay(1000)
                    _state.value = AgentState.IDLE
                    callback?.onStateChanged(AgentState.IDLE)
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
     * 使用 LLM + 屏幕元素信息进行决策
     * 当 AI 需要视觉信息时，会调用 describe_screen 工具
     */
    private suspend fun makeDecision(task: String, screenState: ScreenState): AiDecision {
        val decider = actionDecider ?: throw IllegalStateException("ActionDecider not initialized")
        val planSteps = currentTask?.steps?.takeIf { it.isNotEmpty() }
        
        // 添加超时保护，防止 AI 调用卡死
        return try {
            withTimeoutOrNull(60_000L) {  // 60 秒超时
                decider.decide(task, screenState, executionHistory, planSteps = planSteps)
            } ?: run {
                Logger.e("AI decision timeout after 60 seconds", TAG)
                callback?.onProgress("AI 响应超时，正在重试...")
                AiDecision(
                    thought = "AI响应超时",
                    action = AgentAction(
                        type = ActionType.WAIT,
                        description = "等待重试",
                        waitTime = 2000L
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e("AI decision failed", e, TAG)
            callback?.onProgress("AI 决策失败: ${e.message}")
            AiDecision(
                thought = "AI调用异常: ${e.message}",
                action = AgentAction(
                    type = ActionType.FAILED,
                    description = "AI服务异常",
                    resultMessage = e.message
                )
            )
        }
    }

    /**
     * 构建操作的唯一标识，用于检测连续相同操作
     */
    private fun buildActionKey(action: AgentAction): String {
        return when (action.type) {
            ActionType.TAP, ActionType.DOUBLE_TAP, ActionType.LONG_PRESS -> 
                "${action.type}_${action.x}_${action.y}"
            ActionType.SWIPE, ActionType.SWIPE_UP, ActionType.SWIPE_DOWN, 
            ActionType.SWIPE_LEFT, ActionType.SWIPE_RIGHT -> 
                "${action.type}_${action.startX}_${action.startY}_${action.endX}_${action.endY}_${action.swipeDistance}"
            ActionType.INPUT_TEXT -> 
                "${action.type}_${action.text}"
            ActionType.OPEN_APP, ActionType.CLOSE_APP -> 
                "${action.type}_${action.packageName ?: action.appName}"
            ActionType.DESCRIBE_SCREEN -> 
                "${action.type}_${action.text ?: "default"}"
            else -> 
                "${action.type}_${action.description}"
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
     * 预览任务分解（不执行）
     * 用于在执行前显示任务将如何被分解
     */
    fun previewTaskDecomposition(task: String): DecomposedTask {
        return taskDecomposer.decompose(task)
    }
    
    /**
     * 获取任务分解概要文本
     */
    fun getTaskDecompositionSummary(task: String): String {
        val decomposed = taskDecomposer.decompose(task)
        return taskDecomposer.generateTaskSummary(decomposed)
    }

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
