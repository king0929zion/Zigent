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
            ActionType.FAILED
        }
        
        val description = actionObj.get("description")?.asString ?: actionTypeStr
        
        return when (actionType) {
            ActionType.TAP -> AgentAction(
                type = ActionType.TAP,
                description = description,
                x = actionObj.get("x")?.asInt,
                y = actionObj.get("y")?.asInt,
                elementDescription = actionObj.get("element")?.asString
            )
            
            ActionType.LONG_PRESS -> AgentAction(
                type = ActionType.LONG_PRESS,
                description = description,
                x = actionObj.get("x")?.asInt,
                y = actionObj.get("y")?.asInt,
                duration = actionObj.get("duration")?.asInt ?: 500
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
            
            ActionType.INPUT_TEXT -> AgentAction(
                type = ActionType.INPUT_TEXT,
                description = description,
                text = actionObj.get("text")?.asString ?: ""
            )
            
            ActionType.PRESS_KEY -> AgentAction(
                type = ActionType.PRESS_KEY,
                description = description,
                text = actionObj.get("key")?.asString,
                keyCode = actionObj.get("keyCode")?.asInt
            )
            
            ActionType.OPEN_APP -> AgentAction(
                type = ActionType.OPEN_APP,
                description = description,
                packageName = actionObj.get("package")?.asString,
                text = actionObj.get("app")?.asString
            )
            
            ActionType.WAIT -> AgentAction(
                type = ActionType.WAIT,
                description = description,
                waitTime = actionObj.get("time")?.asLong ?: 1000L
            )
            
            ActionType.SCROLL -> AgentAction(
                type = ActionType.SCROLL,
                description = description,
                scrollDirection = parseScrollDirection(actionObj.get("direction")?.asString)
            )
            
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
     * 测试AI连接
     */
    suspend fun testConnection(): Boolean {
        return aiClient.testConnection().getOrDefault(false)
    }
}

