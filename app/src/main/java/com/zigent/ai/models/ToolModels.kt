package com.zigent.ai.models

import com.google.gson.annotations.SerializedName

/**
 * Function Calling 工具定义
 */
data class Tool(
    val type: String = "function",
    val function: FunctionDef
)

/**
 * 函数定义
 */
data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: FunctionParameters
)

/**
 * 函数参数定义
 */
data class FunctionParameters(
    val type: String = "object",
    val properties: Map<String, PropertyDef>,
    val required: List<String> = emptyList()
)

/**
 * 参数属性定义
 */
data class PropertyDef(
    val type: String,
    val description: String,
    val enum: List<String>? = null
)

/**
 * 工具调用响应
 */
data class ToolCall(
    val id: String,
    val type: String,
    val function: FunctionCall
)

/**
 * 函数调用
 */
data class FunctionCall(
    val name: String,
    val arguments: String  // JSON字符串
)

/**
 * 带工具的响应消息
 */
data class ToolResponseMessage(
    val role: String,
    val content: String?,
    @SerializedName("reasoning_content") val reasoningContent: String?,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>?
)

/**
 * 带工具的响应选择
 */
data class ToolChoice(
    val index: Int,
    val message: ToolResponseMessage?,
    @SerializedName("finish_reason") val finishReason: String?
)

/**
 * 带工具的API响应
 */
data class ToolResponse(
    val id: String?,
    val choices: List<ToolChoice>?,
    val usage: Usage?,
    val error: OpenAiError?
)

/**
 * 带工具的请求
 */
data class ToolRequest(
    val model: String,
    val messages: List<Any>,  // ChatMessage 或 Map
    val tools: List<Tool>? = null,
    @SerializedName("tool_choice") val toolChoice: String? = "auto",
    @SerializedName("max_tokens") val maxTokens: Int = 4096,
    val temperature: Float = 0.7f,
    val stream: Boolean = false
)

/**
 * 工具调用结果
 */
data class ToolCallResult(
    val toolCall: ToolCall?,
    val textResponse: String?,
    val reasoning: String?
) {
    val hasToolCall: Boolean get() = toolCall != null
    val hasTextResponse: Boolean get() = !textResponse.isNullOrBlank()
    
    companion object {
        fun fromToolCall(call: ToolCall, reasoning: String? = null) = 
            ToolCallResult(call, null, reasoning)
        
        fun fromText(text: String, reasoning: String? = null) = 
            ToolCallResult(null, text, reasoning)
        
        fun empty() = ToolCallResult(null, null, null)
    }
}

