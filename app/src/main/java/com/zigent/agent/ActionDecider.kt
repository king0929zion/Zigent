package com.zigent.agent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zigent.agent.models.*
import com.zigent.ai.AiClient
import com.zigent.ai.AiSettings
import com.zigent.ai.models.ChatMessage
import com.zigent.ai.models.MessageRole
import com.zigent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 操作决策器
 * 调用AI分析屏幕状态并决定下一步操作
 */
class ActionDecider(
    private val aiSettings: AiSettings
) {
    companion object {
        private const val TAG = "ActionDecider"
    }

    private val aiClient = AiClient(aiSettings)
    private val gson = Gson()

    /**
     * 决定下一步操作（带图片，使用多模态AI）
     */
    suspend fun decideWithVision(
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>
    ): AiDecision = withContext(Dispatchers.IO) {
        
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
     * 解析AI响应
     */
    private fun parseAiResponse(response: String): AiDecision {
        Logger.d("Parsing AI response: ${response.take(500)}", TAG)
        
        try {
            // 提取JSON部分
            val jsonStr = extractJson(response)
            val jsonObject = JsonParser.parseString(jsonStr).asJsonObject
            
            val thought = jsonObject.get("thought")?.asString ?: ""
            val actionObj = jsonObject.getAsJsonObject("action")
            
            val action = parseAction(actionObj)
            
            return AiDecision(thought = thought, action = action)
            
        } catch (e: Exception) {
            Logger.e("Failed to parse AI response", e, TAG)
            
            // 尝试简单解析
            return trySimpleParse(response)
        }
    }

    /**
     * 从响应中提取JSON
     */
    private fun extractJson(response: String): String {
        // 尝试找到JSON代码块
        val codeBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        codeBlockRegex.find(response)?.let {
            return it.groupValues[1].trim()
        }
        
        // 尝试找到JSON对象
        val jsonRegex = Regex("\\{[\\s\\S]*\\}")
        jsonRegex.find(response)?.let {
            return it.value
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
        
        // 检查是否表示完成
        if (responseLower.contains("finished") || responseLower.contains("完成") || responseLower.contains("成功")) {
            return AiDecision(
                thought = response,
                action = AgentAction(
                    type = ActionType.FINISHED,
                    description = "任务完成",
                    resultMessage = "任务已完成"
                )
            )
        }
        
        // 检查是否表示失败
        if (responseLower.contains("failed") || responseLower.contains("失败") || responseLower.contains("无法")) {
            return AiDecision(
                thought = response,
                action = AgentAction(
                    type = ActionType.FAILED,
                    description = "任务失败",
                    resultMessage = "无法完成任务"
                )
            )
        }
        
        // 默认返回失败
        return AiDecision(
            thought = "无法解析AI响应: $response",
            action = AgentAction(
                type = ActionType.FAILED,
                description = "解析失败",
                resultMessage = "无法理解AI的响应"
            )
        )
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

