package com.zigent.agent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.zigent.agent.models.*
import com.zigent.ai.AiClient
import com.zigent.ai.AiSettings
import com.zigent.ai.models.ToolCall
import com.zigent.ai.models.ToolCallResult
import com.zigent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 操作决策器
 * 
 * 双模型架构：
 * - 主 LLM: 任务理解 + Function Calling
 * - 辅助 VLM: 图片描述（当调用 describe_screen 时）
 * 
 * 职责：
 * 1. 构建符合规范的提示词
 * 2. 调用 LLM 进行工具调用
 * 3. 严格解析工具调用结果
 * 4. 验证参数完整性
 * 5. 返回标准化的 AiDecision
 */
class ActionDecider(
    private val aiSettings: AiSettings
) {
    companion object {
        private const val TAG = "ActionDecider"
        
        // 工具名称常量，避免拼写错误
        object Tools {
            const val TAP = "tap"
            const val LONG_PRESS = "long_press"
            const val DOUBLE_TAP = "double_tap"
            const val SWIPE_UP = "swipe_up"
            const val SWIPE_DOWN = "swipe_down"
            const val SWIPE_LEFT = "swipe_left"
            const val SWIPE_RIGHT = "swipe_right"
            const val SWIPE = "swipe"
            const val SCROLL = "scroll"
            const val INPUT_TEXT = "input_text"
            const val CLEAR_TEXT = "clear_text"
            const val PRESS_BACK = "press_back"
            const val PRESS_HOME = "press_home"
            const val PRESS_RECENT = "press_recent"
            const val PRESS_ENTER = "press_enter"
            const val OPEN_APP = "open_app"
            const val CLOSE_APP = "close_app"
            const val DESCRIBE_SCREEN = "describe_screen"
            const val WAIT = "wait"
            const val FINISHED = "finished"
            const val FAILED = "failed"
            const val ASK_USER = "ask_user"
        }
    }

    private val aiClient = AiClient(aiSettings)
    private val gson = Gson()
    
    // VLM 图片描述缓存
    private var lastScreenDescription: String? = null
    private var lastScreenDescriptionTime: Long = 0
    private val DESCRIPTION_CACHE_TIMEOUT = 5000L
    
    // 设备上下文
    private var deviceContext: DeviceContext? = null
    
    // VLM 可用性状态
    private var vlmAvailable = true
    private var vlmFailureCount = 0
    private val VLM_MAX_FAILURES = 3
    
    data class DeviceContext(
        val installedAppsText: String,
        val initialScreenState: String?
    )
    
    fun setDeviceContext(context: DeviceContext) {
        deviceContext = context
        Logger.i("Device context set: apps=${context.installedAppsText.length} chars", TAG)
    }

    /**
     * 主决策入口
     * 
     * @param task 用户任务描述
     * @param screenState 当前屏幕状态
     * @param history 执行历史
     * @param vlmDescription VLM 提供的屏幕描述（可选）
     * @return AiDecision 决策结果
     */
    suspend fun decide(
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>,
        vlmDescription: String? = null
    ): AiDecision = withContext(Dispatchers.IO) {
        Logger.i("=== ActionDecider.decide ===", TAG)
        Logger.d("Task: $task", TAG)
        Logger.d("Elements: ${screenState.uiElements.size}, VLM: ${vlmDescription != null}", TAG)
        
        val prompt = buildPrompt(task, screenState, history, vlmDescription)
        
        val result = aiClient.chatWithTools(
            prompt = prompt,
            tools = AgentTools.ALL_TOOLS,
            systemPrompt = AgentTools.SYSTEM_PROMPT
        )
        
        result.fold(
            onSuccess = { toolResult -> parseToolCallResult(toolResult) },
            onFailure = { error -> handleError(error) }
        )
    }

    /**
     * 兼容旧接口
     */
    suspend fun decideWithVision(
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>
    ): AiDecision = decide(task, screenState, history)

    fun isVlmAvailable(): Boolean = vlmAvailable
    
    fun resetVlmAvailability() {
        vlmAvailable = true
        vlmFailureCount = 0
    }
    
    /**
     * 调用 VLM 获取屏幕描述
     */
    suspend fun describeScreen(
        imageBase64: String?,
        context: String? = null
    ): String? = withContext(Dispatchers.IO) {
        if (!vlmAvailable) {
            return@withContext "[VLM不可用] 请根据屏幕元素列表进行操作。"
        }
        
        if (imageBase64.isNullOrEmpty()) {
            return@withContext null
        }
        
        // 缓存检查
        val now = System.currentTimeMillis()
        if (lastScreenDescription != null && (now - lastScreenDescriptionTime) < DESCRIPTION_CACHE_TIMEOUT) {
            return@withContext lastScreenDescription
        }
        
        val result = aiClient.describeImage(imageBase64, context)
        
        result.fold(
            onSuccess = { description ->
                vlmFailureCount = 0
                lastScreenDescription = description
                lastScreenDescriptionTime = now
                description
            },
            onFailure = { error ->
                vlmFailureCount++
                if (vlmFailureCount >= VLM_MAX_FAILURES) {
                    vlmAvailable = false
                    "[VLM不可用] 视觉模型连续失败，请使用元素列表。"
                } else {
                    null
                }
            }
        )
    }

    /**
     * 简单对话模式
     */
    suspend fun simpleChat(task: String): String = withContext(Dispatchers.IO) {
        val messages = listOf(
            com.zigent.ai.models.ChatMessage(
                com.zigent.ai.models.MessageRole.USER,
                task
            )
        )
        
        aiClient.chat(
            messages = messages,
            systemPrompt = "你是Zigent，一个友好的AI助手。请简洁地回答用户的问题。"
        ).getOrDefault("抱歉，我暂时无法回答这个问题。")
    }

    /**
     * 分析任务类型
     */
    suspend fun analyzeTask(task: String): TaskAnalysis = withContext(Dispatchers.IO) {
        val lowerTask = task.lowercase()
        
        val isSimpleChat = lowerTask.length < 20 && (
            lowerTask.contains("你好") ||
            lowerTask.contains("谢谢") ||
            lowerTask.contains("再见") ||
            lowerTask.startsWith("?") ||
            lowerTask.startsWith("？")
        )
        
        val appKeywords = listOf(
            "微信", "支付宝", "淘宝", "京东", "抖音", "快手", 
            "b站", "微博", "美团", "饿了么", "滴滴", "qq", "设置"
        )
        val targetApp = appKeywords.find { lowerTask.contains(it) }
        
        TaskAnalysis(
            originalTask = task,
            needsExecution = !isSimpleChat,
            isSimpleChat = isSimpleChat,
            targetApp = targetApp
        )
    }

    suspend fun testConnection(): Boolean {
        return aiClient.testConnection().getOrDefault(false)
    }

    // ==================== 私有方法 ====================

    /**
     * 构建提示词
     */
    private fun buildPrompt(
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>,
        vlmDescription: String?
    ): String {
        return buildString {
            // 设备上下文
            deviceContext?.let { ctx ->
                if (ctx.installedAppsText.isNotEmpty()) {
                    appendLine(ctx.installedAppsText)
                    appendLine()
                }
                if (history.isEmpty() && !ctx.initialScreenState.isNullOrBlank()) {
                    appendLine("## 初始屏幕状态")
                    appendLine(ctx.initialScreenState.take(500))
                    appendLine()
                }
            }
            
            // 任务
            appendLine("## 用户任务")
            appendLine(task)
            appendLine()
            
            // 当前状态
            appendLine("## 当前状态")
            appendLine("应用: ${com.zigent.utils.AppUtils.getAppName(screenState.packageName)}")
            screenState.activityName?.let { 
                appendLine("页面: ${it.substringAfterLast(".")}")
            }
            appendLine()
            
            // 屏幕元素
            appendLine("## 屏幕元素")
            if (screenState.uiElements.isNotEmpty()) {
                screenState.uiElements.take(30).forEach { elem ->
                    val content = elem.text.ifEmpty { elem.description }.take(40)
                    if (content.isNotEmpty() || elem.isClickable || elem.isEditable || elem.isScrollable) {
                        val icon = when {
                            elem.isEditable -> "📝"
                            elem.isClickable -> "🔘"
                            elem.isScrollable -> "📜"
                            else -> "📄"
                        }
                        appendLine("$icon \"$content\" (${elem.bounds.centerX}, ${elem.bounds.centerY})")
                    }
                }
                appendLine()
                appendLine("图例: 🔘可点击 📝可输入 📜可滚动 📄文本")
            } else {
                appendLine("（未检测到可交互元素）")
            }
            appendLine()
            
            // VLM 描述
            if (!vlmDescription.isNullOrBlank()) {
                appendLine("## 屏幕视觉描述")
                appendLine(vlmDescription.take(500))
                appendLine()
            }
            
            // 历史
            if (history.isNotEmpty()) {
                appendLine("## 已执行步骤")
                history.takeLast(5).forEachIndexed { index, step ->
                    val status = if (step.success) "✓" else "✗"
                    appendLine("${index + 1}. $status ${step.action.description}")
                }
                appendLine()
            }
            
            appendLine("## 请求")
            appendLine("调用合适的工具执行下一步操作。")
        }
    }

    /**
     * 解析工具调用结果
     */
    private fun parseToolCallResult(result: ToolCallResult): AiDecision {
        Logger.i("=== Parsing Tool Result ===", TAG)
        
        return when (result) {
            is ToolCallResult.Success -> {
                Logger.i("✅ Tool call: ${result.toolCall.function.name}", TAG)
                parseToolCall(result.toolCall, result.reasoning)
            }
            is ToolCallResult.TextOnly -> {
                Logger.i("📝 Text response", TAG)
                parseTextResponse(result.text, result.reasoning)
            }
            is ToolCallResult.Empty -> {
                Logger.w("⚠️ Empty response", TAG)
                createAskUserDecision("抱歉，我没有理解您的需求。请问您想让我做什么？")
            }
            is ToolCallResult.Error -> {
                Logger.e("❌ Error: ${result.error}", TAG)
                handleError(result.exception ?: Exception(result.error))
            }
        }
    }

    /**
     * 解析工具调用 - 严格模式
     */
    private fun parseToolCall(toolCall: ToolCall, reasoning: String?): AiDecision {
        val functionName = toolCall.function.name
        val arguments = parseArguments(toolCall.function.arguments)
        
        if (arguments == null) {
            Logger.e("Failed to parse arguments for $functionName", TAG)
            return createErrorDecision("工具参数解析失败")
        }
        
        Logger.d("Tool: $functionName, Args: $arguments", TAG)
        
        val thought = reasoning ?: "执行: $functionName"
        val description = arguments.getString("description") ?: functionName
        
        val action = when (functionName) {
            // === 点击操作 ===
            Tools.TAP -> {
                val x = arguments.getInt("x")
                val y = arguments.getInt("y")
                if (x == null || y == null) {
                    return createErrorDecision("tap 缺少必需参数 x 或 y")
                }
                AgentAction(
                    type = ActionType.TAP,
                    description = description,
                    x = x,
                    y = y
                )
            }
            
            Tools.LONG_PRESS -> {
                val x = arguments.getInt("x")
                val y = arguments.getInt("y")
                if (x == null || y == null) {
                    return createErrorDecision("long_press 缺少必需参数 x 或 y")
                }
                AgentAction(
                    type = ActionType.LONG_PRESS,
                    description = description,
                    x = x,
                    y = y,
                    duration = arguments.getInt("duration") ?: 800
                )
            }
            
            Tools.DOUBLE_TAP -> {
                val x = arguments.getInt("x")
                val y = arguments.getInt("y")
                if (x == null || y == null) {
                    return createErrorDecision("double_tap 缺少必需参数 x 或 y")
                }
                AgentAction(
                    type = ActionType.DOUBLE_TAP,
                    description = description,
                    x = x,
                    y = y
                )
            }
            
            // === 滑动操作 ===
            Tools.SWIPE_UP -> AgentAction(
                type = ActionType.SWIPE_UP,
                description = description,
                swipeDistance = arguments.getInt("distance") ?: 50
            )
            
            Tools.SWIPE_DOWN -> AgentAction(
                type = ActionType.SWIPE_DOWN,
                description = description,
                swipeDistance = arguments.getInt("distance") ?: 50
            )
            
            Tools.SWIPE_LEFT -> AgentAction(
                type = ActionType.SWIPE_LEFT,
                description = description,
                swipeDistance = arguments.getInt("distance") ?: 30
            )
            
            Tools.SWIPE_RIGHT -> AgentAction(
                type = ActionType.SWIPE_RIGHT,
                description = description,
                swipeDistance = arguments.getInt("distance") ?: 30
            )
            
            Tools.SWIPE -> {
                val startX = arguments.getInt("start_x")
                val startY = arguments.getInt("start_y")
                val endX = arguments.getInt("end_x")
                val endY = arguments.getInt("end_y")
                if (startX == null || startY == null || endX == null || endY == null) {
                    return createErrorDecision("swipe 缺少必需坐标参数")
                }
                AgentAction(
                    type = ActionType.SWIPE,
                    description = description,
                    startX = startX,
                    startY = startY,
                    endX = endX,
                    endY = endY,
                    duration = arguments.getInt("duration") ?: 300
                )
            }
            
            Tools.SCROLL -> {
                val direction = arguments.getString("direction") ?: "down"
                val scrollType = when (direction.lowercase()) {
                    "up" -> ActionType.SWIPE_UP
                    "down" -> ActionType.SWIPE_DOWN
                    "left" -> ActionType.SWIPE_LEFT
                    "right" -> ActionType.SWIPE_RIGHT
                    else -> ActionType.SWIPE_DOWN
                }
                AgentAction(
                    type = scrollType,
                    description = description,
                    swipeDistance = 40
                )
            }
            
            // === 输入操作 ===
            Tools.INPUT_TEXT -> {
                val text = arguments.getString("text")
                if (text.isNullOrEmpty()) {
                    return createErrorDecision("input_text 缺少 text 参数")
                }
                AgentAction(
                    type = ActionType.INPUT_TEXT,
                    description = description,
                    text = text
                )
            }
            
            Tools.CLEAR_TEXT -> AgentAction(
                type = ActionType.CLEAR_TEXT,
                description = description
            )
            
            // === 按键操作 ===
            Tools.PRESS_BACK -> AgentAction(
                type = ActionType.PRESS_BACK,
                description = description
            )
            
            Tools.PRESS_HOME -> AgentAction(
                type = ActionType.PRESS_HOME,
                description = description
            )
            
            Tools.PRESS_RECENT -> AgentAction(
                type = ActionType.PRESS_RECENT,
                description = description
            )
            
            Tools.PRESS_ENTER -> AgentAction(
                type = ActionType.PRESS_ENTER,
                description = description
            )
            
            // === 应用操作 ===
            Tools.OPEN_APP -> {
                val appName = arguments.getString("app")
                if (appName.isNullOrEmpty()) {
                    return createErrorDecision("open_app 缺少 app 参数")
                }
                AgentAction(
                    type = ActionType.OPEN_APP,
                    description = description,
                    appName = appName
                )
            }
            
            Tools.CLOSE_APP -> {
                val appName = arguments.getString("app")
                if (appName.isNullOrEmpty()) {
                    return createErrorDecision("close_app 缺少 app 参数")
                }
                AgentAction(
                    type = ActionType.CLOSE_APP,
                    description = description,
                    appName = appName
                )
            }
            
            // === 视觉操作 ===
            Tools.DESCRIBE_SCREEN -> AgentAction(
                type = ActionType.DESCRIBE_SCREEN,
                description = description,
                text = arguments.getString("focus")
            )
            
            // === 等待操作 ===
            Tools.WAIT -> AgentAction(
                type = ActionType.WAIT,
                description = description,
                waitTime = arguments.getLong("time") ?: 2000L
            )
            
            // === 任务状态 ===
            Tools.FINISHED -> AgentAction(
                type = ActionType.FINISHED,
                description = "任务完成",
                resultMessage = arguments.getString("message") ?: "任务已完成"
            )
            
            Tools.FAILED -> AgentAction(
                type = ActionType.FAILED,
                description = "任务失败",
                resultMessage = arguments.getString("message") ?: "任务执行失败"
            )
            
            Tools.ASK_USER -> {
                val question = arguments.getString("question")
                if (question.isNullOrEmpty()) {
                    return createErrorDecision("ask_user 缺少 question 参数")
                }
                AgentAction(
                    type = ActionType.ASK_USER,
                    description = "询问用户",
                    question = question
                )
            }
            
            else -> {
                Logger.w("Unknown tool: $functionName", TAG)
                return createAskUserDecision("不支持的操作：$functionName")
            }
        }
        
        return AiDecision(thought = thought, action = action)
    }

    /**
     * 解析参数 JSON
     */
    private fun parseArguments(argumentsJson: String): JsonObject? {
        return try {
            gson.fromJson(argumentsJson, JsonObject::class.java)
        } catch (e: JsonSyntaxException) {
            Logger.e("JSON parse error: ${e.message}", TAG)
            null
        } catch (e: Exception) {
            Logger.e("Unexpected parse error: ${e.message}", TAG)
            null
        }
    }

    /**
     * 解析文本响应 - 只处理合法的文本交互
     */
    private fun parseTextResponse(text: String, reasoning: String?): AiDecision {
        val thought = reasoning ?: text.take(100)
        val textLower = text.lowercase()
        
        Logger.d("Parsing text response: ${text.take(200)}", TAG)
        
        // 检查是否是问题
        if (text.contains("？") || text.contains("?") ||
            textLower.contains("请问") || textLower.contains("请提供") ||
            textLower.contains("需要") || textLower.contains("确认")) {
            return AiDecision(
                thought = thought,
                action = AgentAction(
                    type = ActionType.ASK_USER,
                    description = "需要确认",
                    question = text.take(300)
                )
            )
        }
        
        // 检查完成状态
        if (textLower.contains("已完成") || textLower.contains("完成了") ||
            (textLower.contains("完成") && !textLower.contains("无法"))) {
            return AiDecision(
                thought = thought,
                action = AgentAction(
                    type = ActionType.FINISHED,
                    description = "任务完成",
                    resultMessage = text.take(200)
                )
            )
        }
        
        // 检查失败状态
        if (textLower.contains("无法") || textLower.contains("失败") ||
            textLower.contains("不能") || textLower.contains("错误")) {
            return AiDecision(
                thought = thought,
                action = AgentAction(
                    type = ActionType.FAILED,
                    description = "任务失败",
                    resultMessage = text.take(200)
                )
            )
        }
        
        // 默认作为 AI 回复返回给用户
        return createAskUserDecision(text.take(300), thought)
    }

    /**
     * 处理错误
     */
    private fun handleError(error: Throwable): AiDecision {
        Logger.e("Decision error: ${error.message}", error, TAG)
        return AiDecision(
            thought = "AI 调用失败: ${error.message}",
            action = AgentAction(
                type = ActionType.FAILED,
                description = "AI 服务异常",
                resultMessage = error.message ?: "未知错误"
            )
        )
    }

    /**
     * 创建询问用户的决策
     */
    private fun createAskUserDecision(question: String, thought: String? = null): AiDecision {
        return AiDecision(
            thought = thought ?: "需要用户确认",
            action = AgentAction(
                type = ActionType.ASK_USER,
                description = "询问用户",
                question = question
            )
        )
    }

    /**
     * 创建错误决策
     */
    private fun createErrorDecision(message: String): AiDecision {
        Logger.e("Creating error decision: $message", TAG)
        return AiDecision(
            thought = "参数错误: $message",
            action = AgentAction(
                type = ActionType.ASK_USER,
                description = "参数错误",
                question = "操作参数不完整，请重新描述您的需求：$message"
            )
        )
    }

    // ==================== JsonObject 扩展方法 ====================
    
    private fun JsonObject.getString(key: String): String? {
        return try {
            get(key)?.asString?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun JsonObject.getInt(key: String): Int? {
        return try {
            get(key)?.asInt
        } catch (e: Exception) {
            null
        }
    }
    
    private fun JsonObject.getLong(key: String): Long? {
        return try {
            get(key)?.asLong
        } catch (e: Exception) {
            null
        }
    }
}
