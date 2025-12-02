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

    /**
     * 主决策入口
     * 使用 LLM + 屏幕元素信息进行决策
     */
    suspend fun decide(
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>,
        vlmDescription: String? = null  // VLM 提供的额外屏幕描述
    ): AiDecision = withContext(Dispatchers.IO) {
        Logger.i("=== ActionDecider.decide ===", TAG)
        Logger.i("Task: $task", TAG)
        Logger.i("UI elements count: ${screenState.uiElements.size}", TAG)
        Logger.i("Has VLM description: ${vlmDescription != null}", TAG)
        
        // 构建提示词
        val prompt = buildPrompt(task, screenState, history, vlmDescription)
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
     * 调用 VLM 获取屏幕描述
     */
    suspend fun describeScreen(
        imageBase64: String?,
        context: String? = null
    ): String? = withContext(Dispatchers.IO) {
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
                lastScreenDescription = description
                lastScreenDescriptionTime = now
                Logger.i("VLM description obtained: ${description.take(200)}...", TAG)
                description
            },
            onFailure = { error ->
                Logger.e("VLM description failed", error, TAG)
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
        
        // 检查目标应用
        val targetApp = APP_KEYWORDS.entries.find { (keyword, _) ->
            lowerTask.contains(keyword)
        }?.value
        
        TaskAnalysis(
            originalTask = task,
            needsExecution = !isSimpleChat,
            isSimpleChat = isSimpleChat,
            targetApp = targetApp
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
        vlmDescription: String?
    ): String {
        val sb = StringBuilder()
        
        // 任务描述
        sb.appendLine("## 用户任务")
        sb.appendLine(task)
        sb.appendLine()
        
        // 当前应用
        sb.appendLine("## 当前状态")
        sb.appendLine("应用: ${getAppName(screenState.packageName)}")
        screenState.activityName?.let { 
            sb.appendLine("页面: ${it.substringAfterLast(".")}")
        }
        sb.appendLine()
        
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
        
        val action = when (functionName) {
            // 点击
            "tap" -> AgentAction(
                type = ActionType.TAP,
                description = description,
                x = arguments.get("x")?.asInt,
                y = arguments.get("y")?.asInt
            )
            
            "long_press" -> AgentAction(
                type = ActionType.LONG_PRESS,
                description = description,
                x = arguments.get("x")?.asInt,
                y = arguments.get("y")?.asInt,
                duration = arguments.get("duration")?.asInt ?: 800
            )
            
            "double_tap" -> AgentAction(
                type = ActionType.DOUBLE_TAP,
                description = description,
                x = arguments.get("x")?.asInt,
                y = arguments.get("y")?.asInt
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
                startX = arguments.get("start_x")?.asInt,
                startY = arguments.get("start_y")?.asInt,
                endX = arguments.get("end_x")?.asInt,
                endY = arguments.get("end_y")?.asInt,
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
                text = arguments.get("text")?.asString ?: ""
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
                appName = arguments.get("app")?.asString
            )
            
            "close_app" -> AgentAction(
                type = ActionType.CLOSE_APP,
                description = description,
                appName = arguments.get("app")?.asString
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
     * 获取应用显示名称
     */
    private fun getAppName(packageName: String): String {
        val lowerPackage = packageName.lowercase()
        return when {
            lowerPackage.contains("wechat") || lowerPackage.contains("mm") -> "微信"
            lowerPackage.contains("alipay") -> "支付宝"
            lowerPackage.contains("taobao") -> "淘宝"
            lowerPackage.contains("jd") -> "京东"
            lowerPackage.contains("douyin") || lowerPackage.contains("tiktok") -> "抖音"
            lowerPackage.contains("kuaishou") -> "快手"
            lowerPackage.contains("bilibili") -> "B站"
            lowerPackage.contains("weibo") -> "微博"
            lowerPackage.contains("meituan") -> "美团"
            lowerPackage.contains("eleme") -> "饿了么"
            lowerPackage.contains("didi") -> "滴滴"
            lowerPackage.contains("baidu") -> "百度"
            lowerPackage.contains("qq") -> "QQ"
            lowerPackage.contains("chrome") -> "Chrome"
            lowerPackage.contains("settings") -> "设置"
            lowerPackage.contains("launcher") -> "桌面"
            lowerPackage.contains("dialer") || lowerPackage.contains("phone") -> "电话"
            lowerPackage.contains("contacts") -> "联系人"
            lowerPackage.contains("messaging") || lowerPackage.contains("mms") -> "短信"
            lowerPackage.contains("camera") -> "相机"
            lowerPackage.contains("gallery") || lowerPackage.contains("photos") -> "相册"
            lowerPackage.contains("calendar") -> "日历"
            lowerPackage.contains("clock") || lowerPackage.contains("alarm") -> "时钟"
            lowerPackage.contains("calculator") -> "计算器"
            lowerPackage.contains("filemanager") || lowerPackage.contains("files") -> "文件管理"
            else -> packageName.substringAfterLast(".")
        }
    }

    companion object {
        /**
         * 应用关键词映射
         */
        val APP_KEYWORDS = mapOf(
            "微信" to "微信",
            "wechat" to "微信",
            "支付宝" to "支付宝",
            "alipay" to "支付宝",
            "淘宝" to "淘宝",
            "taobao" to "淘宝",
            "京东" to "京东",
            "jd" to "京东",
            "抖音" to "抖音",
            "douyin" to "抖音",
            "tiktok" to "抖音",
            "快手" to "快手",
            "b站" to "哔哩哔哩",
            "bilibili" to "哔哩哔哩",
            "微博" to "微博",
            "weibo" to "微博",
            "美团" to "美团",
            "meituan" to "美团",
            "饿了么" to "饿了么",
            "滴滴" to "滴滴出行",
            "百度" to "百度",
            "qq" to "QQ",
            "设置" to "设置",
            "settings" to "设置",
            "相机" to "相机",
            "camera" to "相机",
            "相册" to "相册",
            "photos" to "相册",
            "gallery" to "相册",
            "日历" to "日历",
            "calendar" to "日历",
            "时钟" to "时钟",
            "闹钟" to "时钟",
            "计算器" to "计算器",
            "calculator" to "计算器"
        )
    }
}
