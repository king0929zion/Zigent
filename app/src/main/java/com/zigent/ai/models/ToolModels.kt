package com.zigent.ai.models

import com.google.gson.annotations.SerializedName

/**
 * Function Calling 工具定义
 * 符合 OpenAI 函数调用规范
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
    val parameters: FunctionParameters,
    val strict: Boolean = true  // 严格模式，要求参数必须完整
)

/**
 * 函数参数定义
 */
data class FunctionParameters(
    val type: String = "object",
    val properties: Map<String, PropertyDef>,
    val required: List<String> = emptyList(),
    val additionalProperties: Boolean = false  // 禁止额外属性
)

/**
 * 参数属性定义
 */
data class PropertyDef(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
    val minimum: Int? = null,
    val maximum: Int? = null,
    val default: Any? = null
)

/**
 * 工具调用响应
 */
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
) {
    /**
     * 验证工具调用是否有效
     */
    fun isValid(): Boolean {
        return id.isNotBlank() && 
               type == "function" && 
               function.name.isNotBlank()
    }
}

/**
 * 函数调用
 */
data class FunctionCall(
    val name: String,
    val arguments: String  // JSON字符串
) {
    /**
     * 验证函数调用是否有效
     */
    fun isValid(): Boolean {
        return name.isNotBlank() && arguments.isNotBlank()
    }
}

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
) {
    /**
     * 是否是工具调用完成
     */
    fun isToolCallFinish(): Boolean = finishReason == "tool_calls"
    
    /**
     * 是否是正常完成
     */
    fun isStopFinish(): Boolean = finishReason == "stop"
}

/**
 * 带工具的API响应
 */
data class ToolResponse(
    val id: String?,
    val choices: List<ToolChoice>?,
    val usage: Usage?,
    val error: OpenAiError?
) {
    /**
     * 获取第一个有效的工具调用
     */
    fun getFirstToolCall(): ToolCall? {
        return choices?.firstOrNull()?.message?.toolCalls?.firstOrNull { it.isValid() }
    }
    
    /**
     * 获取文本响应
     */
    fun getTextContent(): String? {
        return choices?.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
    }
    
    /**
     * 获取推理内容
     */
    fun getReasoning(): String? {
        return choices?.firstOrNull()?.message?.reasoningContent?.takeIf { it.isNotBlank() }
    }
    
    /**
     * 是否有错误
     */
    fun hasError(): Boolean = error != null
    
    /**
     * 是否有有效响应
     */
    fun hasValidResponse(): Boolean {
        return !hasError() && (getFirstToolCall() != null || getTextContent() != null)
    }
}

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
 * 封装 LLM 的响应，统一处理工具调用和文本响应
 */
sealed class ToolCallResult {
    abstract val reasoning: String?
    
    /**
     * 成功的工具调用
     */
    data class Success(
        val toolCall: ToolCall,
        override val reasoning: String? = null
    ) : ToolCallResult()
    
    /**
     * 纯文本响应（未调用工具）
     */
    data class TextOnly(
        val text: String,
        override val reasoning: String? = null
    ) : ToolCallResult()
    
    /**
     * 空响应
     */
    data class Empty(
        override val reasoning: String? = null,
        val message: String = "LLM 返回空响应"
    ) : ToolCallResult()
    
    /**
     * 错误
     */
    data class Error(
        val error: String,
        val exception: Throwable? = null,
        override val reasoning: String? = null
    ) : ToolCallResult()
    
    // 便捷属性
    val isSuccess: Boolean get() = this is Success
    val isTextOnly: Boolean get() = this is TextOnly
    val isEmpty: Boolean get() = this is Empty
    val isError: Boolean get() = this is Error
    
    val hasToolCall: Boolean get() = this is Success
    val hasTextResponse: Boolean get() = this is TextOnly
    
    // 获取工具调用（如果有）
    val toolCall: ToolCall? get() = (this as? Success)?.toolCall
    
    // 获取文本响应（如果有）
    val textResponse: String? get() = (this as? TextOnly)?.text
    
    companion object {
        fun fromToolCall(call: ToolCall, reasoning: String? = null): ToolCallResult {
            return if (call.isValid()) {
                Success(call, reasoning)
            } else {
                Error("无效的工具调用: ${call.function.name}", reasoning = reasoning)
            }
        }
        
        fun fromText(text: String, reasoning: String? = null): ToolCallResult {
            return if (text.isNotBlank()) {
                TextOnly(text, reasoning)
            } else {
                Empty(reasoning)
            }
        }
        
        fun empty(reasoning: String? = null) = Empty(reasoning)
        
        fun error(message: String, exception: Throwable? = null) = 
            Error(message, exception)
    }
}

/**
 * 工具参数验证结果
 */
data class ParameterValidation(
    val isValid: Boolean,
    val missingRequired: List<String> = emptyList(),
    val invalidTypes: Map<String, String> = emptyMap(),
    val errors: List<String> = emptyList()
) {
    companion object {
        fun valid() = ParameterValidation(true)
        
        fun invalid(errors: List<String>) = ParameterValidation(
            isValid = false, 
            errors = errors
        )
    }
}
