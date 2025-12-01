package com.zigent.ai.models

import com.google.gson.annotations.SerializedName

/**
 * 聊天消息角色
 */
enum class MessageRole {
    @SerializedName("system") SYSTEM,
    @SerializedName("user") USER,
    @SerializedName("assistant") ASSISTANT
}

/**
 * 聊天消息
 */
data class ChatMessage(
    val role: MessageRole,
    val content: Any  // String 或 List<ContentPart>
)

/**
 * 多模态内容部分
 */
sealed class ContentPart {
    data class Text(val type: String = "text", val text: String) : ContentPart()
    data class ImageUrl(
        val type: String = "image_url",
        @SerializedName("image_url") val imageUrl: ImageUrlData
    ) : ContentPart()
}

data class ImageUrlData(
    val url: String,  // "data:image/png;base64,..." 或 URL
    val detail: String = "auto"  // "low", "high", "auto"
)

/**
 * OpenAI 请求格式
 */
data class OpenAiRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerializedName("max_tokens") val maxTokens: Int = 4096,
    val temperature: Float = 0.7f,
    val stream: Boolean = false
)

/**
 * OpenAI 响应格式
 */
data class OpenAiResponse(
    val id: String?,
    val choices: List<Choice>?,
    val usage: Usage?,
    val error: OpenAiError?
)

data class Choice(
    val index: Int,
    val message: ResponseMessage?,
    @SerializedName("finish_reason") val finishReason: String?
)

data class ResponseMessage(
    val role: String,
    val content: String?
)

data class Usage(
    @SerializedName("prompt_tokens") val promptTokens: Int,
    @SerializedName("completion_tokens") val completionTokens: Int,
    @SerializedName("total_tokens") val totalTokens: Int
)

data class OpenAiError(
    val message: String?,
    val type: String?,
    val code: String?
)

/**
 * Claude 请求格式
 */
data class ClaudeRequest(
    val model: String,
    val messages: List<ClaudeMessage>,
    @SerializedName("max_tokens") val maxTokens: Int = 4096,
    val system: String? = null
)

data class ClaudeMessage(
    val role: String,  // "user" 或 "assistant"
    val content: Any   // String 或 List<ClaudeContentPart>
)

sealed class ClaudeContentPart {
    data class Text(val type: String = "text", val text: String) : ClaudeContentPart()
    data class Image(
        val type: String = "image",
        val source: ImageSource
    ) : ClaudeContentPart()
}

data class ImageSource(
    val type: String = "base64",
    @SerializedName("media_type") val mediaType: String = "image/png",
    val data: String  // base64编码的图片数据
)

/**
 * Claude 响应格式
 */
data class ClaudeResponse(
    val id: String?,
    val content: List<ClaudeContentBlock>?,
    val usage: ClaudeUsage?,
    val error: ClaudeError?
)

data class ClaudeContentBlock(
    val type: String,
    val text: String?
)

data class ClaudeUsage(
    @SerializedName("input_tokens") val inputTokens: Int,
    @SerializedName("output_tokens") val outputTokens: Int
)

data class ClaudeError(
    val type: String?,
    val message: String?
)

