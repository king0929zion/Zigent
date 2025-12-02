package com.zigent.agent

import com.google.gson.Gson
import com.google.gson.JsonObject
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
 * - 主 LLM (DeepSeek-V3.2-Exp): 任务理解 + Function Calling
 * - 辅助 VLM (Qwen3-Omni-Captioner): 图片描述（当调用 describe_screen 时）
 * 
 * 工作流程：
 * 1. 收集屏幕元素信息（无障碍服务）
 * 2. 构建提示词发送给 LLM
 * 3. LLM 返回工具调用
 * 4. 如果是 describe_screen，调用 VLM 获取图片描述，再让 LLM 继续决策
 * 5. 返回最终决策
 */
class ActionDecider(
    private val aiSettings: AiSettings
) {
    companion object {
        private const val TAG = "ActionDecider"
    }

    private val aiClient = AiClient(aiSettings)
    private val gson = Gson()
    
    // VLM 图片描述缓存（避免重复调用）
    private var lastScreenDescription: String? = null
    private var lastScreenDescriptionTime: Long = 0
    private val DESCRIPTION_CACHE_TIMEOUT = 5000L  // 5秒缓存
    
    // 设备上下文（已安装应用、初始屏幕状态等）
    private var deviceContext: DeviceContext? = null
    
    // VLM 可用性状态
    private var vlmAvailable = true
    private var vlmFailureCount = 0
    private val VLM_MAX_FAILURES = 3  // 连续失败 3 次后禁用 VLM
    
    /**
     * 设备上下文信息
     */
    data class DeviceContext(
        val installedAppsText: String,     // 已安装应用列表文本
        val initialScreenState: String?    // 初始屏幕状态描述
    )
    
    /**
     * 设置设备上下文
     */
    fun setDeviceContext(context: DeviceContext) {
        deviceContext = context
        Logger.i("Device context set: apps=${context.installedAppsText.length} chars", TAG)
    }

    /**
     * 主决策入口
     * 使用 LLM + 屏幕元素信息进行决策
     */
    suspend fun decide(
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>,
        vlmDescription: String? = null,  // VLM 提供的额外屏幕描述
        planSteps: List<String>? = null  // 预先规划的步骤，用于减少遗忘
    ): AiDecision = withContext(Dispatchers.IO) {
        Logger.i("=== ActionDecider.decide ===", TAG)
        Logger.i("Task: $task", TAG)
        Logger.i("UI elements count: ${screenState.uiElements.size}", TAG)
        Logger.i("Has VLM description: ${vlmDescription != null}", TAG)
        
        // 构建提示词
        val prompt = buildPrompt(task, screenState, history, vlmDescription, planSteps)
        Logger.d("Prompt: ${prompt.take(1500)}...", TAG)
        
        // 调用 LLM 进行工具调用
        val result = aiClient.chatWithTools(
            prompt = prompt,
            tools = AgentTools.ALL_TOOLS,
            systemPrompt = AgentTools.SYSTEM_PROMPT
        )
        
        result.fold(
            onSuccess = { toolResult ->
                parseToolCallResult(toolResult, task, screenState, history)
            },
            onFailure = { error ->
                Logger.e("LLM decision failed", error, TAG)
                AiDecision(
                    thought = "AI调用失败: ${error.message}",
                    action = AgentAction(
                        type = ActionType.FAILED,
                        description = "AI服务异常",
                        resultMessage = error.message
                    )
                )
            }
        )
    }

    /**
     * 兼容旧接口 - 带图片决策
     */
    suspend fun decideWithVision(
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>
    ): AiDecision = decide(task, screenState, history)

    /**
     * 检查 VLM 是否可用
     */
    fun isVlmAvailable(): Boolean = vlmAvailable
    
    /**
     * 手动重置 VLM 可用状态
     */
    fun resetVlmAvailability() {
        vlmAvailable = true
        vlmFailureCount = 0
        Logger.i("VLM availability reset", TAG)
    }
    
    /**
     * 调用 VLM 获取屏幕描述
     * 如果 VLM 不可用，返回 null 并提示 Agent 使用元素列表
     */
    suspend fun describeScreen(
        imageBase64: String?,
        context: String? = null
    ): String? = withContext(Dispatchers.IO) {
        // 如果 VLM 已被禁用，返回降级提示
        if (!vlmAvailable) {
            Logger.w("VLM is disabled due to repeated failures, using element list only", TAG)
            return@withContext "[VLM不可用] 视觉模型暂时无法使用，请仅根据屏幕元素列表进行操作决策。"
        }
        
        if (imageBase64.isNullOrEmpty()) {
            Logger.w("No screenshot available for VLM", TAG)
            return@withContext null
        }
        
        // 检查缓存
        val now = System.currentTimeMillis()
        if (lastScreenDescription != null && (now - lastScreenDescriptionTime) < DESCRIPTION_CACHE_TIMEOUT) {
            Logger.i("Using cached VLM description", TAG)
            return@withContext lastScreenDescription
        }
        
        Logger.i("=== Calling VLM for screen description ===", TAG)
        
        val result = aiClient.describeImage(imageBase64, context)
        
        result.fold(
            onSuccess = { description ->
                // VLM 调用成功，重置失败计数
                vlmFailureCount = 0
                lastScreenDescription = description
                lastScreenDescriptionTime = now
                Logger.i("VLM description obtained: ${description.take(200)}...", TAG)
                description
            },
            onFailure = { error ->
                // VLM 调用失败，增加失败计数
                vlmFailureCount++
                Logger.e("VLM description failed (failure count: $vlmFailureCount)", error, TAG)
                
                // 连续失败达到阈值，禁用 VLM
                if (vlmFailureCount >= VLM_MAX_FAILURES) {
                    vlmAvailable = false
                    Logger.w("VLM disabled after $VLM_MAX_FAILURES consecutive failures", TAG)
                    return@withContext "[VLM不可用] 视觉模型连续失败，已切换到仅元素列表模式。请根据屏幕元素信息进行操作。"
                }
                
                null
            }
        )
    }

    /**
     * 简单对话模式
     * 不需要操作手机时使用
     */
    suspend fun simpleChat(task: String): String = withContext(Dispatchers.IO) {
        Logger.i("Simple chat: $task", TAG)
        
        val messages = listOf(
            com.zigent.ai.models.ChatMessage(
                com.zigent.ai.models.MessageRole.USER,
                task
            )
        )
        
        val result = aiClient.chat(
            messages = messages,
            systemPrompt = "你是Zigent，一个友好的AI助手。请简洁地回答用户的问题。"
        )
        
        result.fold(
            onSuccess = { it },
            onFailure = { "抱歉，我暂时无法回答这个问题。" }
        )
    }

    /**
     * 分析任务类型
     */
    suspend fun analyzeTask(task: String): TaskAnalysis = withContext(Dispatchers.IO) {
        Logger.d("Analyzing task: $task", TAG)
        
        // 简单规则判断
        val lowerTask = task.lowercase()
        
        // 检查是否是简单对话
        val isSimpleChat = lowerTask.length < 20 && (
            lowerTask.contains("你好") ||
            lowerTask.contains("谢谢") ||
            lowerTask.contains("再见") ||
            lowerTask.startsWith("?") ||
            lowerTask.startsWith("？") ||
            lowerTask.contains("什么是") ||
            lowerTask.contains("介绍一下")
        )
        
        // 检查目标应用（简单关键词匹配）
        val appKeywords = listOf(
            "微信", "wechat", "支付宝", "alipay", "淘宝", "taobao", 
            "京东", "jd", "抖音", "douyin", "快手", "b站", "bilibili",
            "微博", "weibo", "美团", "饿了么", "滴滴", "qq", "设置"
        )
        val targetApp = appKeywords.find { lowerTask.contains(it.lowercase()) }
        
        val sensitiveKeywords = listOf(
            "支付", "付款", "转账", "打款", "汇款",
            "扫码支付", "收款码", "付款码", "红包", "提现",
            "充值", "购买", "下单", "结算", "订单"
        )
        val requiresConfirmation = sensitiveKeywords.any { lowerTask.contains(it) }
        
        TaskAnalysis(
            originalTask = task,
            needsExecution = !isSimpleChat,
            isSimpleChat = isSimpleChat,
            targetApp = targetApp,
            requiresUserConfirmation = requiresConfirmation
        )
    }

    // ==================== 私有方法 ====================

    /**
     * 构建提示词
     */
    private fun buildPrompt(
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>,
        vlmDescription: String?,
        planSteps: List<String>?
    ): String {
        val sb = StringBuilder()
        
        // 设备上下文（首次执行时的应用列表和初始屏幕）
        deviceContext?.let { ctx ->
            // 已安装应用（帮助 AI 知道可以打开哪些应用）
            if (ctx.installedAppsText.isNotEmpty()) {
                sb.appendLine(ctx.installedAppsText)
                sb.appendLine()
            }
            
            // 初始屏幕状态（如果是第一步且有初始状态）
            if (history.isEmpty() && !ctx.initialScreenState.isNullOrBlank()) {
                sb.appendLine("## 初始屏幕状态")
                sb.appendLine(ctx.initialScreenState.take(500))
                sb.appendLine()
            }
        }
        
        // 任务描述
        sb.appendLine("## 用户任务")
        sb.appendLine(task)
        sb.appendLine()
        
        // 当前应用
        sb.appendLine("## 当前状态")
        sb.appendLine("应用: ${com.zigent.utils.AppUtils.getAppName(screenState.packageName)}")
        screenState.activityName?.let { 
            sb.appendLine("页面: ${it.substringAfterLast(".")}")
        }
        sb.appendLine()
        
        // 任务规划（若已有）
        if (!planSteps.isNullOrEmpty()) {
            sb.appendLine("## 任务规划（请按顺序执行，不要遗忘）")
            planSteps.forEachIndexed { index, step ->
                sb.appendLine("${index + 1}. $step")
            }
            sb.appendLine()
        }
        
        // 屏幕元素列表（主要信息源）
        sb.appendLine("## 屏幕元素")
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
                    val coords = "(${elem.bounds.centerX}, ${elem.bounds.centerY})"
                    sb.appendLine("$icon \"$content\" $coords")
                }
            }
            sb.appendLine()
            sb.appendLine("图例: 🔘可点击 📝可输入 📜可滚动 📄文本")
        } else {
            sb.appendLine("（未检测到可交互元素）")
        }
        sb.appendLine()
        
        // VLM 图片描述（如果有）
        if (!vlmDescription.isNullOrBlank()) {
            sb.appendLine("## 屏幕视觉描述 (VLM)")
            sb.appendLine(vlmDescription.take(500))
            sb.appendLine()
        }
        
        // 历史操作
        if (history.isNotEmpty()) {
            sb.appendLine("## 已执行步骤")
            history.takeLast(5).forEachIndexed { index, step ->
                val status = if (step.success) "✓" else "✗"
                sb.appendLine("${index + 1}. $status ${step.action.description}")
            }
            sb.appendLine()
        }
        
        // 指示
        sb.appendLine("## 请求")
        sb.appendLine("根据以上信息，调用合适的工具执行下一步操作。")
        
        return sb.toString()
    }

    /**
     * 解析工具调用结果
     */
    private fun parseToolCallResult(
        result: ToolCallResult,
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>
    ): AiDecision {
        Logger.i("=== Parsing Tool Result ===", TAG)
        Logger.i("hasToolCall: ${result.hasToolCall}, hasText: ${result.hasTextResponse}", TAG)
        
        // 优先处理工具调用
        if (result.hasToolCall && result.toolCall != null) {
            return parseToolCall(result.toolCall, result.reasoning)
        }
        
        // 处理文本响应
        if (result.hasTextResponse && !result.textResponse.isNullOrBlank()) {
            return parseTextResponse(result.textResponse, result.reasoning)
        }
        
        // 空响应
        Logger.w("Empty response from LLM", TAG)
        return AiDecision(
            thought = "AI返回空响应",
            action = AgentAction(
                type = ActionType.ASK_USER,
                description = "需要确认",
                question = "抱歉，我没有理解您的需求。请问您想让我做什么？"
            )
        )
    }

    /**
     * 解析工具调用
     */
    private fun parseToolCall(toolCall: ToolCall, reasoning: String?): AiDecision {
        val functionName = toolCall.function.name
        val arguments = try {
            gson.fromJson(toolCall.function.arguments, JsonObject::class.java)
        } catch (e: Exception) {
            Logger.e("Failed to parse arguments: ${toolCall.function.arguments}", e, TAG)
            JsonObject()
        }
        
        Logger.i("Tool: $functionName", TAG)
        Logger.d("Args: $arguments", TAG)
        
        val thought = reasoning ?: "执行: $functionName"
        val description = arguments.get("description")?.asString ?: functionName
        fun missingParam(param: String) = AiDecision(
            thought = "缺少必要参数: $param",
            action = AgentAction(
                type = ActionType.ASK_USER,
                description = "需要补充信息",
                question = "AI 生成的操作缺少参数 $param，请补充后重试。"
            )
        )
        
        val action = when (functionName) {
            // 点击
            "tap" -> AgentAction(
                type = ActionType.TAP,
                description = description,
                x = arguments.get("x")?.asInt ?: return missingParam("x"),
                y = arguments.get("y")?.asInt ?: return missingParam("y")
            )
            
            "long_press" -> AgentAction(
                type = ActionType.LONG_PRESS,
                description = description,
                x = arguments.get("x")?.asInt ?: return missingParam("x"),
                y = arguments.get("y")?.asInt ?: return missingParam("y"),
                duration = arguments.get("duration")?.asInt ?: 800
            )
            
            "double_tap" -> AgentAction(
                type = ActionType.DOUBLE_TAP,
                description = description,
                x = arguments.get("x")?.asInt ?: return missingParam("x"),
                y = arguments.get("y")?.asInt ?: return missingParam("y")
            )
            
            // 滑动
            "swipe_up" -> AgentAction(
                type = ActionType.SWIPE_UP,
                description = description,
                swipeDistance = arguments.get("distance")?.asInt ?: 50
            )
            
            "swipe_down" -> AgentAction(
                type = ActionType.SWIPE_DOWN,
                description = description,
                swipeDistance = arguments.get("distance")?.asInt ?: 50
            )
            
            "swipe_left" -> AgentAction(
                type = ActionType.SWIPE_LEFT,
                description = description,
                swipeDistance = arguments.get("distance")?.asInt ?: 30
            )
            
            "swipe_right" -> AgentAction(
                type = ActionType.SWIPE_RIGHT,
                description = description,
                swipeDistance = arguments.get("distance")?.asInt ?: 30
            )
            
            "swipe" -> AgentAction(
                type = ActionType.SWIPE,
                description = description,
                startX = arguments.get("start_x")?.asInt ?: return missingParam("start_x"),
                startY = arguments.get("start_y")?.asInt ?: return missingParam("start_y"),
                endX = arguments.get("end_x")?.asInt ?: return missingParam("end_x"),
                endY = arguments.get("end_y")?.asInt ?: return missingParam("end_y"),
                duration = arguments.get("duration")?.asInt ?: 300
            )
            
            "scroll" -> {
                val direction = arguments.get("direction")?.asString ?: "down"
                val scrollType = when (direction) {
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
            
            // 输入
            "input_text" -> AgentAction(
                type = ActionType.INPUT_TEXT,
                description = description,
                text = arguments.get("text")?.asString ?: return missingParam("text")
            )
            
            "clear_text" -> AgentAction(
                type = ActionType.CLEAR_TEXT,
                description = description
            )
            
            // 按键
            "press_back" -> AgentAction(
                type = ActionType.PRESS_BACK,
                description = description
            )
            
            "press_home" -> AgentAction(
                type = ActionType.PRESS_HOME,
                description = description
            )
            
            "press_recent" -> AgentAction(
                type = ActionType.PRESS_RECENT,
                description = description
            )
            
            "press_enter" -> AgentAction(
                type = ActionType.PRESS_ENTER,
                description = description
            )
            
            // 应用
            "open_app" -> AgentAction(
                type = ActionType.OPEN_APP,
                description = description,
                appName = arguments.get("app")?.asString ?: return missingParam("app")
            )
            
            "close_app" -> AgentAction(
                type = ActionType.CLOSE_APP,
                description = description,
                appName = arguments.get("app")?.asString ?: return missingParam("app")
            )
            
            // 视觉 - 需要调用 VLM
            "describe_screen" -> AgentAction(
                type = ActionType.DESCRIBE_SCREEN,
                description = description,
                text = arguments.get("focus")?.asString
            )
            
            // 等待
            "wait" -> AgentAction(
                type = ActionType.WAIT,
                description = description,
                waitTime = arguments.get("time")?.asLong ?: 2000L
            )
            
            // 状态
            "finished" -> AgentAction(
                type = ActionType.FINISHED,
                description = description,
                resultMessage = arguments.get("message")?.asString
            )
            
            "failed" -> AgentAction(
                type = ActionType.FAILED,
                description = description,
                resultMessage = arguments.get("message")?.asString
            )
            
            "ask_user" -> AgentAction(
                type = ActionType.ASK_USER,
                description = description,
                question = arguments.get("question")?.asString
            )
            
            else -> {
                Logger.w("Unknown tool: $functionName", TAG)
                AgentAction(
                    type = ActionType.ASK_USER,
                    description = "未知工具",
                    question = "抱歉，我不确定如何执行这个操作。请问您能更详细地描述吗？"
                )
            }
        }
        
        return AiDecision(thought = thought, action = action)
    }

    /**
     * 解析文本响应
     */
    private fun parseTextResponse(text: String, reasoning: String?): AiDecision {
        val thought = reasoning ?: text.take(100)
        val textLower = text.lowercase()
        
        Logger.d("Parsing text: ${text.take(200)}", TAG)
        
        // 检测是否是工具调用指令被当作文本输出
        // 例如: "tap 540 200" 或 "input_text xxx" 等
        val toolCallPattern = Regex(
            "(tap|click|input_text|swipe|scroll|press_back|press_home|open_app|long_press)\\s*[\\(（]?\\s*(\\d+)?",
            RegexOption.IGNORE_CASE
        )
        if (toolCallPattern.containsMatchIn(text)) {
            Logger.w("Detected tool-like text, asking AI to use proper tool call: $text", TAG)
            return AiDecision(
                thought = "AI 输出了工具调用文本而非正确的工具调用",
                action = AgentAction(
                    type = ActionType.WAIT,
                    description = "等待 AI 正确响应",
                    waitTime = 500L
                )
            )
        }
        
        // 检测包含坐标的文本（可能是错误的工具调用输出）
        val coordPattern = Regex("\\d{2,4}[,，\\s]+\\d{2,4}")
        if (coordPattern.containsMatchIn(text) && text.length < 100) {
            Logger.w("Detected coordinate-like text, might be malformed tool call: $text", TAG)
            return AiDecision(
                thought = "AI 输出了坐标文本而非正确的工具调用",
                action = AgentAction(
                    type = ActionType.WAIT,
                    description = "等待 AI 正确响应",
                    waitTime = 500L
                )
            )
        }
        
        // 检查是否是问题
        val isQuestion = text.contains("？") || text.contains("?") ||
                         textLower.contains("请问") || textLower.contains("请提供")
        
        if (isQuestion) {
            return AiDecision(
                thought = thought,
                action = AgentAction(
                    type = ActionType.ASK_USER,
                    description = "需要确认",
                    question = text.take(300)
                )
            )
        }
        
        // 检查完成
        if (textLower.contains("完成") && !textLower.contains("无法")) {
            return AiDecision(
                thought = thought,
                action = AgentAction(
                    type = ActionType.FINISHED,
                    description = "任务完成",
                    resultMessage = text.take(200)
                )
            )
        }
        
        // 检查失败
        if (textLower.contains("无法") || textLower.contains("失败")) {
            return AiDecision(
                thought = thought,
                action = AgentAction(
                    type = ActionType.FAILED,
                    description = "任务失败",
                    resultMessage = text.take(200)
                )
            )
        }
        
        // 默认当作需要确认
        return AiDecision(
            thought = thought,
            action = AgentAction(
                type = ActionType.ASK_USER,
                description = "AI回复",
                question = text.take(300)
            )
        )
    }

    /**
     * 测试 AI 连接
     */
    suspend fun testConnection(): Boolean {
        return aiClient.testConnection().getOrDefault(false)
    }
}
