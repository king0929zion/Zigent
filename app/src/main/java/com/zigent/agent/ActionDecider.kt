package com.zigent.agent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zigent.agent.models.*
import com.zigent.ai.AiClient
import com.zigent.ai.AiSettings
import com.zigent.ai.models.ChatMessage
import com.zigent.ai.models.MessageRole
import com.zigent.ai.models.ToolCall
import com.zigent.ai.models.ToolCallResult
import com.zigent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 操作决策器
 * 调用AI分析屏幕状态并决定下一步操作
 * 支持 Function Calling（工具调用）模式
 */
class ActionDecider(
    private val aiSettings: AiSettings
) {
    companion object {
        private const val TAG = "ActionDecider"
    }

    private val aiClient = AiClient(aiSettings)
    private val gson = Gson()
    
    // 是否使用 Function Calling 模式
    var useFunctionCalling: Boolean = true
    
    // Function Calling 连续失败计数
    private var fcFailCount = 0
    private val MAX_FC_FAILS = 2

    /**
     * 决定下一步操作（带图片，使用多模态AI）
     */
    suspend fun decideWithVision(
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>
    ): AiDecision = withContext(Dispatchers.IO) {
        
        // 优先使用 Function Calling 模式
        if (useFunctionCalling) {
            return@withContext decideWithFunctionCalling(task, screenState, history)
        }
        
        val prompt = PromptBuilder.buildVisionActionPrompt(task, screenState, history)
        
        Logger.d("Vision prompt: ${prompt.take(500)}...", TAG)
        
        val imageBase64 = screenState.screenshotBase64
        
        val result = if (!imageBase64.isNullOrEmpty()) {
            aiClient.chatWithImage(
                prompt = prompt,
                imageBase64 = imageBase64,
                systemPrompt = PromptBuilder.SYSTEM_PROMPT
            )
        } else {
            // 没有截图，使用纯文本模式
            decide(task, screenState, history)
            return@withContext decide(task, screenState, history)
        }
        
        result.fold(
            onSuccess = { response ->
                parseAiResponse(response)
            },
            onFailure = { error ->
                Logger.e("AI decision failed", error, TAG)
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
     * 决定下一步操作（纯文本模式）
     */
    suspend fun decide(
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>
    ): AiDecision = withContext(Dispatchers.IO) {
        
        // 优先使用 Function Calling 模式
        if (useFunctionCalling) {
            return@withContext decideWithFunctionCalling(task, screenState, history)
        }
        
        val prompt = PromptBuilder.buildActionPrompt(task, screenState, history)
        
        Logger.d("Text prompt: ${prompt.take(500)}...", TAG)
        
        val messages = listOf(
            ChatMessage(MessageRole.USER, prompt)
        )
        
        val result = aiClient.chat(messages, PromptBuilder.SYSTEM_PROMPT)
        
        result.fold(
            onSuccess = { response ->
                parseAiResponse(response)
            },
            onFailure = { error ->
                Logger.e("AI decision failed", error, TAG)
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
     * 使用 Function Calling 决定下一步操作（支持多模态）
     */
    suspend fun decideWithFunctionCalling(
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>
    ): AiDecision = withContext(Dispatchers.IO) {
        
        val prompt = buildFunctionCallingPrompt(task, screenState, history)
        val imageBase64 = screenState.screenshotBase64
        
        Logger.d("Function calling prompt: ${prompt.take(500)}...", TAG)
        Logger.d("Has screenshot: ${!imageBase64.isNullOrEmpty()}", TAG)
        
        // 使用带图片的 Function Calling（如果有截图）
        val result = aiClient.chatWithToolsAndImage(
            prompt = prompt,
            imageBase64 = imageBase64,
            tools = AgentTools.ALL_TOOLS,
            systemPrompt = AgentTools.SYSTEM_PROMPT
        )
        
        result.fold(
            onSuccess = { callResult ->
                val decision = parseToolCallResult(callResult, task, screenState, history)
                // 如果解析成功，重置失败计数
                if (decision.action.type != ActionType.FAILED) {
                    fcFailCount = 0
                }
                decision
            },
            onFailure = { error ->
                Logger.e("Function calling failed", error, TAG)
                handleFunctionCallingFailure(task, screenState, history)
            }
        )
    }

    /**
     * 处理 Function Calling 失败
     */
    private suspend fun handleFunctionCallingFailure(
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>
    ): AiDecision {
        fcFailCount++
        Logger.w("Function calling fail count: $fcFailCount", TAG)
        
        if (fcFailCount >= MAX_FC_FAILS) {
            Logger.w("Too many FC failures, switching to text mode", TAG)
            useFunctionCalling = false
            fcFailCount = 0
        }
        
        // 降级到普通模式
        return decide(task, screenState, history)
    }

    /**
     * 解析工具调用结果
     */
    private suspend fun parseToolCallResult(
        result: ToolCallResult,
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>
    ): AiDecision {
        // 优先处理工具调用
        if (result.hasToolCall && result.toolCall != null) {
            Logger.i("Got tool call: ${result.toolCall.function.name}", TAG)
            return parseToolCall(result.toolCall, result.reasoning)
        }
        
        // 处理纯文本响应
        if (result.hasTextResponse && !result.textResponse.isNullOrBlank()) {
            Logger.i("Got text response, parsing...", TAG)
            return parseTextResponse(result.textResponse, result.reasoning)
        }
        
        // 空响应 - 自动降级重试
        Logger.w("Empty response from Function Calling, falling back", TAG)
        fcFailCount++
        
        if (fcFailCount >= MAX_FC_FAILS) {
            useFunctionCalling = false
            fcFailCount = 0
        }
        
        // 降级重试
        return decide(task, screenState, history)
    }

    /**
     * 解析文本响应（当AI没有使用工具时）
     */
    private fun parseTextResponse(text: String, reasoning: String?): AiDecision {
        val thought = reasoning ?: text.take(100)
        
        // 尝试从文本中推断操作
        val textLower = text.lowercase()
        
        // 检查是否表示完成
        if (textLower.contains("完成") || textLower.contains("finished") || textLower.contains("成功")) {
            return AiDecision(
                thought = thought,
                action = AgentAction(
                    type = ActionType.FINISHED,
                    description = "任务完成",
                    resultMessage = text.take(200)
                )
            )
        }
        
        // 检查是否表示失败
        if (textLower.contains("失败") || textLower.contains("无法") || textLower.contains("failed")) {
            return AiDecision(
                thought = thought,
                action = AgentAction(
                    type = ActionType.FAILED,
                    description = "任务失败",
                    resultMessage = text.take(200)
                )
            )
        }
        
        // 检查是否是询问
        if (text.contains("？") || text.contains("?")) {
            return AiDecision(
                thought = thought,
                action = AgentAction(
                    type = ActionType.ASK_USER,
                    description = "需要确认",
                    question = text.take(200)
                )
            )
        }
        
        // 默认：尝试解析为 JSON（兼容旧模式）
        return parseAiResponse(text)
    }

    /**
     * 构建 Function Calling 的提示词
     */
    private fun buildFunctionCallingPrompt(
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>
    ): String {
        val sb = StringBuilder()
        
        sb.appendLine("## 任务")
        sb.appendLine(task)
        sb.appendLine()
        
        sb.appendLine("## 当前状态")
        sb.appendLine("应用：${screenState.packageName}")
        screenState.activityName?.let { 
            sb.appendLine("页面：${it.substringAfterLast(".")}")
        }
        sb.appendLine()
        
        // 屏幕元素列表
        if (screenState.uiElements.isNotEmpty()) {
            sb.appendLine("## 屏幕元素")
            sb.appendLine("| # | 类型 | 内容 | 坐标 |")
            sb.appendLine("|---|------|------|------|")
            
            var validIndex = 0
            screenState.uiElements.forEach { elem ->
                val content = elem.text.ifEmpty { elem.description }.take(25)
                if (content.isNotEmpty() || elem.isClickable || elem.isEditable) {
                    val type = when {
                        elem.isEditable -> "输入框"
                        elem.isScrollable -> "列表"
                        elem.isClickable -> "按钮"
                        else -> "文本"
                    }
                    val coords = "(${elem.bounds.centerX}, ${elem.bounds.centerY})"
                    sb.appendLine("| $validIndex | $type | $content | $coords |")
                    validIndex++
                    if (validIndex >= 20) return@forEach
                }
            }
            sb.appendLine()
        }
        
        // 历史操作
        if (history.isNotEmpty()) {
            sb.appendLine("## 已执行操作")
            history.takeLast(5).forEachIndexed { index, step ->
                val status = if (step.success) "✓" else "✗"
                sb.appendLine("${index + 1}. $status ${step.action.description}")
            }
            sb.appendLine()
        }
        
        // 明确指示
        sb.appendLine("## 请求")
        sb.appendLine("分析当前屏幕，调用合适的工具执行下一步操作。")
        
        return sb.toString()
    }

    /**
     * 解析工具调用
     */
    private fun parseToolCall(toolCall: ToolCall, reasoning: String? = null): AiDecision {
        val functionName = toolCall.function.name
        val arguments = try {
            gson.fromJson(toolCall.function.arguments, JsonObject::class.java)
        } catch (e: Exception) {
            Logger.e("Failed to parse tool arguments: ${toolCall.function.arguments}", e, TAG)
            JsonObject()
        }
        
        Logger.i("Parsing tool call: $functionName with args: $arguments", TAG)
        val thought = reasoning ?: "执行: $functionName"
        
        val description = arguments.get("description")?.asString ?: functionName
        
        val action = when (functionName) {
            // 点击操作
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
            
            // 滑动操作
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
            
            // 输入操作
            "input_text" -> AgentAction(
                type = ActionType.INPUT_TEXT,
                description = description,
                text = arguments.get("text")?.asString ?: ""
            )
            "clear_text" -> AgentAction(
                type = ActionType.CLEAR_TEXT,
                description = description
            )
            
            // 按键操作
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
            
            // 应用操作
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
            
            // 等待
            "wait" -> AgentAction(
                type = ActionType.WAIT,
                description = description,
                waitTime = arguments.get("time")?.asLong ?: 2000L
            )
            
            // 任务状态
            "finished" -> AgentAction(
                type = ActionType.FINISHED,
                description = "任务完成",
                resultMessage = arguments.get("message")?.asString ?: "任务已完成"
            )
            "failed" -> AgentAction(
                type = ActionType.FAILED,
                description = "任务失败",
                resultMessage = arguments.get("message")?.asString ?: "任务失败"
            )
            "ask_user" -> AgentAction(
                type = ActionType.ASK_USER,
                description = "询问用户",
                question = arguments.get("question")?.asString ?: "需要更多信息"
            )
            
            // 滚动操作
            "scroll" -> {
                val direction = arguments.get("direction")?.asString ?: "down"
                val scrollDir = when (direction) {
                    "up" -> ScrollDirection.UP
                    "down" -> ScrollDirection.DOWN
                    "left" -> ScrollDirection.LEFT
                    "right" -> ScrollDirection.RIGHT
                    else -> ScrollDirection.DOWN
                }
                AgentAction(
                    type = ActionType.SCROLL,
                    description = description,
                    scrollDirection = scrollDir,
                    scrollCount = arguments.get("count")?.asInt ?: 1
                )
            }
            
            // 按回车
            "press_enter" -> AgentAction(
                type = ActionType.PRESS_KEY,
                description = description,
                keyCode = 66  // KEYCODE_ENTER
            )
            
            else -> AgentAction(
                type = ActionType.FAILED,
                description = "未知操作: $functionName",
                resultMessage = "不支持的操作类型: $functionName"
            )
        }
        
        return AiDecision(
            thought = thought,
            action = action
        )
    }

    /**
     * 解析AI响应
     */
    private fun parseAiResponse(response: String): AiDecision {
        Logger.d("Raw AI response: ${response.take(300)}", TAG)
        
        // 清洗响应
        val cleanedResponse = cleanResponse(response)
        Logger.d("Cleaned response: ${cleanedResponse.take(300)}", TAG)
        
        try {
            // 提取JSON部分
            val jsonStr = extractJson(cleanedResponse)
            val jsonObject = JsonParser.parseString(jsonStr).asJsonObject
            
            val thought = jsonObject.get("thought")?.asString ?: ""
            
            // 尝试多种方式获取 action
            val actionObj = jsonObject.getAsJsonObject("action")
                ?: jsonObject // 如果没有 action 字段，可能整个就是 action
            
            if (actionObj != null) {
                val action = parseAction(actionObj)
                return AiDecision(thought = thought, action = action)
            }
            
        } catch (e: Exception) {
            Logger.e("Failed to parse AI response: ${e.message}", e, TAG)
        }
        
        // 尝试直接解析为 action JSON
        try {
            val jsonStr = extractJson(response)
            val jsonObject = JsonParser.parseString(jsonStr).asJsonObject
            
            // 检查是否有 action 字段（表示这是一个 action 对象）
            if (jsonObject.has("action") || jsonObject.has("type")) {
                val action = parseAction(jsonObject)
                return AiDecision(thought = "", action = action)
            }
        } catch (e: Exception) {
            Logger.d("Direct action parse also failed", TAG)
        }
        
        // 尝试简单解析
        return trySimpleParse(response)
    }

    /**
     * 清洗AI响应，去除不必要的内容
     */
    private fun cleanResponse(response: String): String {
        var cleaned = response.trim()
        
        // 去除思考过程标记
        cleaned = cleaned.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        cleaned = cleaned.replace(Regex("<thinking>.*?</thinking>", RegexOption.DOT_MATCHES_ALL), "")
        
        // 去除代码块标记
        cleaned = cleaned.replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("```\\s*$", RegexOption.MULTILINE), "")
        cleaned = cleaned.replace("```", "")
        
        // 去除开头的解释文字（到第一个{为止）
        val firstBrace = cleaned.indexOf('{')
        if (firstBrace > 0) {
            val lastBrace = cleaned.lastIndexOf('}')
            if (lastBrace > firstBrace) {
                cleaned = cleaned.substring(firstBrace, lastBrace + 1)
            }
        }
        
        // 去除多余的空白
        cleaned = cleaned.trim()
        
        return cleaned
    }
    
    /**
     * 从响应中提取JSON
     */
    private fun extractJson(response: String): String {
        // 如果已经是JSON，直接返回
        val trimmed = response.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }
        
        // 尝试找到JSON代码块
        val codeBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        codeBlockRegex.find(response)?.let {
            return it.groupValues[1].trim()
        }
        
        // 尝试找到JSON对象（从第一个{到最后一个}）
        val firstBrace = response.indexOf('{')
        val lastBrace = response.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace > firstBrace) {
            return response.substring(firstBrace, lastBrace + 1)
        }
        
        return response
    }

    /**
     * 解析操作对象
     */
    private fun parseAction(actionObj: JsonObject): AgentAction {
        val actionTypeStr = actionObj.get("action")?.asString?.uppercase()
            ?: actionObj.get("type")?.asString?.uppercase()
            ?: "FAILED"
        
        val actionType = try {
            ActionType.valueOf(actionTypeStr)
        } catch (e: Exception) {
            // 尝试映射常见的别名
            mapActionAlias(actionTypeStr)
        }
        
        val description = actionObj.get("description")?.asString ?: actionTypeStr
        
        return when (actionType) {
            // 基础触摸操作
            ActionType.TAP -> AgentAction(
                type = ActionType.TAP,
                description = description,
                x = actionObj.get("x")?.asInt,
                y = actionObj.get("y")?.asInt,
                elementDescription = actionObj.get("element")?.asString
            )
            
            ActionType.DOUBLE_TAP -> AgentAction(
                type = ActionType.DOUBLE_TAP,
                description = description,
                x = actionObj.get("x")?.asInt,
                y = actionObj.get("y")?.asInt
            )
            
            ActionType.LONG_PRESS -> AgentAction(
                type = ActionType.LONG_PRESS,
                description = description,
                x = actionObj.get("x")?.asInt,
                y = actionObj.get("y")?.asInt,
                duration = actionObj.get("duration")?.asInt ?: 800
            )
            
            ActionType.SWIPE -> AgentAction(
                type = ActionType.SWIPE,
                description = description,
                startX = actionObj.get("startX")?.asInt,
                startY = actionObj.get("startY")?.asInt,
                endX = actionObj.get("endX")?.asInt,
                endY = actionObj.get("endY")?.asInt,
                duration = actionObj.get("duration")?.asInt ?: 300
            )
            
            // 方向滑动
            ActionType.SWIPE_UP -> AgentAction(
                type = ActionType.SWIPE_UP,
                description = description,
                swipeDistance = actionObj.get("distance")?.asInt ?: 50,
                duration = actionObj.get("duration")?.asInt ?: 300
            )
            
            ActionType.SWIPE_DOWN -> AgentAction(
                type = ActionType.SWIPE_DOWN,
                description = description,
                swipeDistance = actionObj.get("distance")?.asInt ?: 50,
                duration = actionObj.get("duration")?.asInt ?: 300
            )
            
            ActionType.SWIPE_LEFT -> AgentAction(
                type = ActionType.SWIPE_LEFT,
                description = description,
                swipeDistance = actionObj.get("distance")?.asInt ?: 30,
                duration = actionObj.get("duration")?.asInt ?: 300
            )
            
            ActionType.SWIPE_RIGHT -> AgentAction(
                type = ActionType.SWIPE_RIGHT,
                description = description,
                swipeDistance = actionObj.get("distance")?.asInt ?: 30,
                duration = actionObj.get("duration")?.asInt ?: 300
            )
            
            // 滚动操作
            ActionType.SCROLL -> AgentAction(
                type = ActionType.SCROLL,
                description = description,
                scrollDirection = parseScrollDirection(actionObj.get("direction")?.asString),
                scrollCount = actionObj.get("count")?.asInt ?: 1
            )
            
            ActionType.SCROLL_TO_TOP -> AgentAction(
                type = ActionType.SCROLL_TO_TOP,
                description = description
            )
            
            ActionType.SCROLL_TO_BOTTOM -> AgentAction(
                type = ActionType.SCROLL_TO_BOTTOM,
                description = description
            )
            
            // 输入操作
            ActionType.INPUT_TEXT -> AgentAction(
                type = ActionType.INPUT_TEXT,
                description = description,
                text = actionObj.get("text")?.asString ?: "",
                x = actionObj.get("x")?.asInt,
                y = actionObj.get("y")?.asInt
            )
            
            ActionType.CLEAR_TEXT -> AgentAction(
                type = ActionType.CLEAR_TEXT,
                description = description
            )
            
            // 按键操作
            ActionType.PRESS_KEY -> AgentAction(
                type = ActionType.PRESS_KEY,
                description = description,
                keyName = actionObj.get("key")?.asString,
                keyCode = actionObj.get("keyCode")?.asInt
            )
            
            ActionType.PRESS_BACK -> AgentAction(
                type = ActionType.PRESS_BACK,
                description = description
            )
            
            ActionType.PRESS_HOME -> AgentAction(
                type = ActionType.PRESS_HOME,
                description = description
            )
            
            ActionType.PRESS_RECENT -> AgentAction(
                type = ActionType.PRESS_RECENT,
                description = description
            )
            
            // 应用操作
            ActionType.OPEN_APP -> AgentAction(
                type = ActionType.OPEN_APP,
                description = description,
                packageName = actionObj.get("package")?.asString,
                appName = actionObj.get("app")?.asString
            )
            
            ActionType.CLOSE_APP -> AgentAction(
                type = ActionType.CLOSE_APP,
                description = description,
                packageName = actionObj.get("package")?.asString,
                appName = actionObj.get("app")?.asString
            )
            
            ActionType.OPEN_URL -> AgentAction(
                type = ActionType.OPEN_URL,
                description = description,
                url = actionObj.get("url")?.asString
            )
            
            ActionType.OPEN_SETTINGS -> AgentAction(
                type = ActionType.OPEN_SETTINGS,
                description = description,
                text = actionObj.get("setting")?.asString
            )
            
            // 系统操作
            ActionType.TAKE_SCREENSHOT -> AgentAction(
                type = ActionType.TAKE_SCREENSHOT,
                description = description
            )
            
            ActionType.COPY_TEXT -> AgentAction(
                type = ActionType.COPY_TEXT,
                description = description
            )
            
            ActionType.PASTE_TEXT -> AgentAction(
                type = ActionType.PASTE_TEXT,
                description = description
            )
            
            // 通知操作
            ActionType.OPEN_NOTIFICATION -> AgentAction(
                type = ActionType.OPEN_NOTIFICATION,
                description = description
            )
            
            ActionType.CLEAR_NOTIFICATION -> AgentAction(
                type = ActionType.CLEAR_NOTIFICATION,
                description = description
            )
            
            // 等待操作
            ActionType.WAIT -> AgentAction(
                type = ActionType.WAIT,
                description = description,
                waitTime = actionObj.get("time")?.asLong ?: 1000L
            )
            
            ActionType.WAIT_FOR_ELEMENT -> AgentAction(
                type = ActionType.WAIT_FOR_ELEMENT,
                description = description,
                waitForText = actionObj.get("text")?.asString,
                timeout = actionObj.get("timeout")?.asLong ?: 10000L
            )
            
            // 任务状态
            ActionType.FINISHED -> AgentAction(
                type = ActionType.FINISHED,
                description = description,
                resultMessage = actionObj.get("message")?.asString ?: "任务完成"
            )
            
            ActionType.FAILED -> AgentAction(
                type = ActionType.FAILED,
                description = description,
                resultMessage = actionObj.get("message")?.asString ?: "任务失败"
            )
            
            ActionType.ASK_USER -> AgentAction(
                type = ActionType.ASK_USER,
                description = description,
                question = actionObj.get("question")?.asString ?: "需要您的确认"
            )
        }
    }
    
    /**
     * 映射操作别名
     */
    private fun mapActionAlias(actionStr: String): ActionType {
        return when (actionStr.uppercase()) {
            "CLICK", "点击" -> ActionType.TAP
            "双击" -> ActionType.DOUBLE_TAP
            "长按" -> ActionType.LONG_PRESS
            "滑动" -> ActionType.SWIPE
            "上滑", "向上滑动" -> ActionType.SWIPE_UP
            "下滑", "向下滑动" -> ActionType.SWIPE_DOWN
            "左滑", "向左滑动" -> ActionType.SWIPE_LEFT
            "右滑", "向右滑动" -> ActionType.SWIPE_RIGHT
            "滚动" -> ActionType.SCROLL
            "输入", "打字" -> ActionType.INPUT_TEXT
            "清空" -> ActionType.CLEAR_TEXT
            "返回" -> ActionType.PRESS_BACK
            "主页", "回到主页" -> ActionType.PRESS_HOME
            "最近任务", "多任务" -> ActionType.PRESS_RECENT
            "打开应用", "启动应用" -> ActionType.OPEN_APP
            "关闭应用" -> ActionType.CLOSE_APP
            "打开网址", "打开链接" -> ActionType.OPEN_URL
            "打开设置" -> ActionType.OPEN_SETTINGS
            "截图" -> ActionType.TAKE_SCREENSHOT
            "复制" -> ActionType.COPY_TEXT
            "粘贴" -> ActionType.PASTE_TEXT
            "通知栏" -> ActionType.OPEN_NOTIFICATION
            "清除通知" -> ActionType.CLEAR_NOTIFICATION
            "等待" -> ActionType.WAIT
            "完成", "成功" -> ActionType.FINISHED
            "失败" -> ActionType.FAILED
            "询问", "确认" -> ActionType.ASK_USER
            else -> ActionType.FAILED
        }
    }

    /**
     * 解析滚动方向
     */
    private fun parseScrollDirection(direction: String?): ScrollDirection {
        return when (direction?.uppercase()) {
            "UP", "上" -> ScrollDirection.UP
            "DOWN", "下" -> ScrollDirection.DOWN
            "LEFT", "左" -> ScrollDirection.LEFT
            "RIGHT", "右" -> ScrollDirection.RIGHT
            else -> ScrollDirection.DOWN
        }
    }

    /**
     * 简单解析尝试（当JSON解析失败时）
     */
    private fun trySimpleParse(response: String): AiDecision {
        val responseLower = response.lowercase()
        Logger.d("Trying simple parse for: ${response.take(200)}", TAG)
        
        // 检查是否表示完成
        if (responseLower.contains("finished") || 
            responseLower.contains("完成") || 
            responseLower.contains("成功") ||
            responseLower.contains("已经完成")) {
            return AiDecision(
                thought = response,
                action = AgentAction(
                    type = ActionType.FINISHED,
                    description = "任务完成",
                    resultMessage = extractMessage(response) ?: "任务已完成"
                )
            )
        }
        
        // 尝试从文本中提取操作
        // 检查输入操作
        val inputRegex = Regex("(?:输入|input|type)[：:\"']?\\s*[\"']?([^\"'\\n]+)[\"']?", RegexOption.IGNORE_CASE)
        inputRegex.find(response)?.let { match ->
            val text = match.groupValues[1].trim()
            if (text.isNotEmpty()) {
                Logger.d("Extracted input text: $text", TAG)
                return AiDecision(
                    thought = "需要输入文字",
                    action = AgentAction(
                        type = ActionType.INPUT_TEXT,
                        description = "输入: $text",
                        text = text
                    )
                )
            }
        }
        
        // 检查点击操作
        val tapRegex = Regex("(?:点击|tap|click)[：:]?\\s*\\(?\\s*(\\d+)\\s*[,，]\\s*(\\d+)\\s*\\)?", RegexOption.IGNORE_CASE)
        tapRegex.find(response)?.let { match ->
            val x = match.groupValues[1].toIntOrNull()
            val y = match.groupValues[2].toIntOrNull()
            if (x != null && y != null) {
                Logger.d("Extracted tap: ($x, $y)", TAG)
                return AiDecision(
                    thought = "需要点击",
                    action = AgentAction(
                        type = ActionType.TAP,
                        description = "点击 ($x, $y)",
                        x = x,
                        y = y
                    )
                )
            }
        }
        
        // 检查返回操作
        if (responseLower.contains("返回") || responseLower.contains("back")) {
            return AiDecision(
                thought = "需要返回",
                action = AgentAction(
                    type = ActionType.PRESS_BACK,
                    description = "返回上一页"
                )
            )
        }
        
        // 检查滑动操作
        if (responseLower.contains("向下滑") || responseLower.contains("下滑") || responseLower.contains("scroll down")) {
            return AiDecision(
                thought = "需要向下滑动",
                action = AgentAction(
                    type = ActionType.SWIPE_DOWN,
                    description = "向下滑动"
                )
            )
        }
        if (responseLower.contains("向上滑") || responseLower.contains("上滑") || responseLower.contains("scroll up")) {
            return AiDecision(
                thought = "需要向上滑动",
                action = AgentAction(
                    type = ActionType.SWIPE_UP,
                    description = "向上滑动"
                )
            )
        }
        
        // 检查是否表示失败
        if (responseLower.contains("failed") || 
            responseLower.contains("失败") || 
            responseLower.contains("无法") ||
            responseLower.contains("error")) {
            return AiDecision(
                thought = response,
                action = AgentAction(
                    type = ActionType.FAILED,
                    description = "任务失败",
                    resultMessage = extractMessage(response) ?: "无法完成任务"
                )
            )
        }
        
        // 检查是否需要等待
        if (responseLower.contains("等待") || responseLower.contains("wait")) {
            return AiDecision(
                thought = "需要等待",
                action = AgentAction(
                    type = ActionType.WAIT,
                    description = "等待页面加载",
                    waitTime = 2000
                )
            )
        }
        
        // 如果响应很短，可能是简单回复，当作完成处理
        if (response.length < 50 && !responseLower.contains("json") && !responseLower.contains("{")) {
            return AiDecision(
                thought = response,
                action = AgentAction(
                    type = ActionType.FINISHED,
                    description = "AI回复",
                    resultMessage = response
                )
            )
        }
        
        // 默认返回失败
        Logger.w("Cannot parse response, returning failed", TAG)
        return AiDecision(
            thought = "无法解析AI响应",
            action = AgentAction(
                type = ActionType.FAILED,
                description = "解析失败",
                resultMessage = "无法理解AI的响应，请重试"
            )
        )
    }
    
    /**
     * 从响应中提取消息
     */
    private fun extractMessage(response: String): String? {
        // 尝试提取引号中的内容
        val quoteRegex = Regex("[\"']([^\"']+)[\"']")
        quoteRegex.find(response)?.let {
            return it.groupValues[1]
        }
        
        // 尝试提取冒号后的内容
        val colonRegex = Regex("(?:message|消息|结果)[：:]\\s*(.+)")
        colonRegex.find(response)?.let {
            return it.groupValues[1].trim()
        }
        
        return null
    }

    /**
     * 简单对话（不需要执行操作）
     */
    suspend fun simpleChat(prompt: String): String = withContext(Dispatchers.IO) {
        val messages = listOf(
            ChatMessage(MessageRole.USER, prompt)
        )
        
        val result = aiClient.chat(messages, PromptBuilder.SIMPLE_CHAT_PROMPT)
        
        result.fold(
            onSuccess = { response ->
                response
            },
            onFailure = { error ->
                Logger.e("Simple chat failed", error, TAG)
                "抱歉，AI服务暂时不可用：${error.message}"
            }
        )
    }
    
    /**
     * 分析任务是否需要执行操作
     */
    suspend fun analyzeTask(userInput: String): TaskAnalysis = withContext(Dispatchers.IO) {
        val prompt = PromptBuilder.buildTaskAnalysisPrompt(userInput)
        val messages = listOf(ChatMessage(MessageRole.USER, prompt))
        
        val result = aiClient.chat(messages, PromptBuilder.SYSTEM_PROMPT)
        
        result.fold(
            onSuccess = { response ->
                parseTaskAnalysis(response, userInput)
            },
            onFailure = { error ->
                Logger.e("Task analysis failed", error, TAG)
                TaskAnalysis(
                    originalInput = userInput,
                    needsExecution = false,
                    isSimpleChat = true,
                    errorMessage = error.message
                )
            }
        )
    }
    
    /**
     * 解析任务分析结果
     */
    private fun parseTaskAnalysis(response: String, originalInput: String): TaskAnalysis {
        try {
            val jsonStr = extractJson(response)
            val jsonObject = JsonParser.parseString(jsonStr).asJsonObject
            
            val needsApp = jsonObject.get("needsApp")?.asBoolean ?: false
            val app = jsonObject.get("app")?.asString
            val steps = jsonObject.getAsJsonArray("steps")?.map { it.asString } ?: emptyList()
            val isSimpleChat = jsonObject.get("isSimpleChat")?.asBoolean ?: !needsApp
            
            return TaskAnalysis(
                originalInput = originalInput,
                needsExecution = needsApp || steps.isNotEmpty(),
                isSimpleChat = isSimpleChat,
                targetApp = app,
                plannedSteps = steps
            )
        } catch (e: Exception) {
            Logger.e("Failed to parse task analysis", e, TAG)
            // 默认当作需要执行的任务
            return TaskAnalysis(
                originalInput = originalInput,
                needsExecution = true,
                isSimpleChat = false
            )
        }
    }

    /**
     * 测试AI连接
     */
    suspend fun testConnection(): Boolean {
        return aiClient.testConnection().getOrDefault(false)
    }
}

/**
 * 任务分析结果
 */
data class TaskAnalysis(
    val originalInput: String,
    val needsExecution: Boolean,      // 是否需要执行手机操作
    val isSimpleChat: Boolean,        // 是否是简单对话
    val targetApp: String? = null,    // 目标应用
    val plannedSteps: List<String> = emptyList(),  // 计划的步骤
    val errorMessage: String? = null
)

