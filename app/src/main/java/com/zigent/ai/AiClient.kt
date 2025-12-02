package com.zigent.ai

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.zigent.ai.models.*
import com.zigent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI客户端
 * 支持OpenAI和Claude API
 */
class AiClient(private val settings: AiSettings) {

    companion object {
        private const val TAG = "AiClient"
        
        private const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        private const val CLAUDE_BASE_URL = "https://api.anthropic.com/v1"
        private const val SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1"
    }

    private val gson: Gson = GsonBuilder()
        .setLenient()
        .create()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(AiConfig.API_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(AiConfig.API_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(AiConfig.API_TIMEOUT, TimeUnit.SECONDS)
        .build()

    /**
     * 发送聊天请求（纯文本）
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        systemPrompt: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        when (settings.provider) {
            AiProvider.SILICONFLOW -> chatOpenAi(messages, systemPrompt, SILICONFLOW_BASE_URL)
            AiProvider.OPENAI -> chatOpenAi(messages, systemPrompt, OPENAI_BASE_URL)
            AiProvider.CUSTOM -> chatOpenAi(messages, systemPrompt, settings.baseUrl)
            AiProvider.CLAUDE -> chatClaude(messages, systemPrompt)
        }
    }

    /**
     * 发送带工具的聊天请求（Function Calling）
     */
    suspend fun chatWithTools(
        messages: List<ChatMessage>,
        tools: List<Tool>,
        systemPrompt: String? = null
    ): Result<ToolCallResult> = withContext(Dispatchers.IO) {
        when (settings.provider) {
            AiProvider.SILICONFLOW -> chatOpenAiWithTools(messages, tools, systemPrompt, null, SILICONFLOW_BASE_URL)
            AiProvider.OPENAI -> chatOpenAiWithTools(messages, tools, systemPrompt, null, OPENAI_BASE_URL)
            AiProvider.CUSTOM -> chatOpenAiWithTools(messages, tools, systemPrompt, null, settings.baseUrl)
            AiProvider.CLAUDE -> Result.failure(Exception("Claude不支持Function Calling，请使用硅基流动或OpenAI"))
        }
    }

    /**
     * 发送带图片和工具的聊天请求（多模态 + Function Calling）
     */
    suspend fun chatWithToolsAndImage(
        prompt: String,
        imageBase64: String?,
        tools: List<Tool>,
        systemPrompt: String? = null
    ): Result<ToolCallResult> = withContext(Dispatchers.IO) {
        when (settings.provider) {
            AiProvider.SILICONFLOW -> {
                val messages = listOf(ChatMessage(MessageRole.USER, prompt))
                chatOpenAiWithTools(messages, tools, systemPrompt, imageBase64, SILICONFLOW_BASE_URL)
            }
            AiProvider.OPENAI -> {
                val messages = listOf(ChatMessage(MessageRole.USER, prompt))
                chatOpenAiWithTools(messages, tools, systemPrompt, imageBase64, OPENAI_BASE_URL)
            }
            AiProvider.CUSTOM -> {
                val messages = listOf(ChatMessage(MessageRole.USER, prompt))
                chatOpenAiWithTools(messages, tools, systemPrompt, imageBase64, settings.baseUrl)
            }
            AiProvider.CLAUDE -> Result.failure(Exception("Claude不支持Function Calling"))
        }
    }

    /**
     * OpenAI兼容格式 带工具的聊天请求（支持多模态）
     */
    private fun chatOpenAiWithTools(
        messages: List<ChatMessage>,
        tools: List<Tool>,
        systemPrompt: String?,
        imageBase64: String?,
        baseUrl: String = OPENAI_BASE_URL
    ): Result<ToolCallResult> {
        try {
            val allMessages = buildList<Any> {
                if (!systemPrompt.isNullOrBlank()) {
                    add(mapOf("role" to "system", "content" to systemPrompt))
                }
                
                messages.forEach { msg ->
                    if (imageBase64 != null && msg.role == MessageRole.USER) {
                        // 多模态消息（带图片）
                        val contentParts = listOf(
                            mapOf("type" to "text", "text" to msg.content),
                            mapOf(
                                "type" to "image_url",
                                "image_url" to mapOf(
                                    "url" to "data:image/png;base64,$imageBase64",
                                    "detail" to "high"
                                )
                            )
                        )
                        add(mapOf("role" to "user", "content" to contentParts))
                    } else {
                        add(mapOf(
                            "role" to msg.role.name.lowercase(),
                            "content" to msg.content
                        ))
                    }
                }
            }

            val defaultModel = when (settings.provider) {
                AiProvider.SILICONFLOW -> AiConfig.SILICONFLOW_MODEL
                else -> AiConfig.DEFAULT_MODEL_OPENAI
            }

            val request = ToolRequest(
                model = settings.model.ifBlank { defaultModel },
                messages = allMessages,
                tools = tools,
                toolChoice = "required",  // 强制使用工具
                maxTokens = settings.maxTokens,
                temperature = settings.temperature
            )

            val actualBaseUrl = baseUrl.trimEnd('/')

            val httpRequest = Request.Builder()
                .url("$actualBaseUrl/chat/completions")
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(request).toRequestBody("application/json".toMediaType()))
                .build()

            Logger.d("Tool request: ${gson.toJson(request).take(1000)}", TAG)

            val response = httpClient.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: ""

            Logger.d("Tool response: ${responseBody.take(1000)}", TAG)

            if (!response.isSuccessful) {
                return Result.failure(Exception("API error: ${response.code} - $responseBody"))
            }

            // 检查响应是否为空
            if (responseBody.isBlank()) {
                Logger.e("API returned empty response body", TAG)
                return Result.failure(Exception("API响应为空"))
            }

            val toolResponse = try {
                gson.fromJson(responseBody, ToolResponse::class.java)
            } catch (e: Exception) {
                Logger.e("Failed to parse API response: ${responseBody.take(500)}", e, TAG)
                return Result.failure(Exception("无法解析API响应: ${e.message}"))
            }
            
            if (toolResponse == null) {
                Logger.e("Parsed response is null", TAG)
                return Result.failure(Exception("API响应解析为空"))
            }
            
            if (toolResponse.error != null) {
                val errorMsg = toolResponse.error.message ?: "未知错误"
                Logger.e("API error: $errorMsg", TAG)
                return Result.failure(Exception("API错误: $errorMsg"))
            }

            val choice = toolResponse.choices?.firstOrNull()
            if (choice == null) {
                Logger.w("No choices in response", TAG)
                // 返回空结果而不是失败，让调用方决定如何处理
                return Result.success(ToolCallResult.empty())
            }
            
            val reasoning = choice.message?.reasoningContent
            
            // 检查是否有工具调用
            val toolCalls = choice.message?.toolCalls
            if (!toolCalls.isNullOrEmpty()) {
                val toolCall = toolCalls.first()
                Logger.i("Tool call: ${toolCall.function.name}(${toolCall.function.arguments})", TAG)
                return Result.success(ToolCallResult.fromToolCall(toolCall, reasoning))
            }
            
            // 没有工具调用，检查普通回复
            val content = choice.message?.content
            if (!content.isNullOrBlank()) {
                Logger.d("Text response (no tool call): ${content.take(200)}", TAG)
                return Result.success(ToolCallResult.fromText(content, reasoning))
            }
            
            // 完全空响应
            Logger.w("Empty response: no tool_calls and no content", TAG)
            return Result.success(ToolCallResult.empty())

        } catch (e: Exception) {
            Logger.e("Tool API call failed", e, TAG)
            return Result.failure(e)
        }
    }

    /**
     * 发送带图片的聊天请求（多模态）
     */
    suspend fun chatWithImage(
        prompt: String,
        imageBase64: String,
        systemPrompt: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        when (settings.provider) {
            AiProvider.SILICONFLOW -> chatOpenAiWithImage(prompt, imageBase64, systemPrompt, SILICONFLOW_BASE_URL)
            AiProvider.OPENAI -> chatOpenAiWithImage(prompt, imageBase64, systemPrompt, OPENAI_BASE_URL)
            AiProvider.CUSTOM -> chatOpenAiWithImage(prompt, imageBase64, systemPrompt, settings.baseUrl)
            AiProvider.CLAUDE -> chatClaudeWithImage(prompt, imageBase64, systemPrompt)
        }
    }

    /**
     * OpenAI兼容格式 聊天请求（支持硅基流动等）
     */
    private fun chatOpenAi(
        messages: List<ChatMessage>,
        systemPrompt: String?,
        baseUrl: String = OPENAI_BASE_URL
    ): Result<String> {
        try {
            val allMessages = buildList {
                if (!systemPrompt.isNullOrBlank()) {
                    add(ChatMessage(MessageRole.SYSTEM, systemPrompt))
                }
                addAll(messages)
            }

            val defaultModel = when (settings.provider) {
                AiProvider.SILICONFLOW -> AiConfig.SILICONFLOW_MODEL
                else -> AiConfig.DEFAULT_MODEL_OPENAI
            }

            val request = OpenAiRequest(
                model = settings.model.ifBlank { defaultModel },
                messages = allMessages,
                maxTokens = settings.maxTokens,
                temperature = settings.temperature
            )

            val actualBaseUrl = baseUrl.trimEnd('/')

            val httpRequest = Request.Builder()
                .url("$actualBaseUrl/chat/completions")
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(request).toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: ""

            Logger.d("API Response: ${responseBody.take(500)}", TAG)

            if (!response.isSuccessful) {
                return Result.failure(Exception("API error: ${response.code} - $responseBody"))
            }

            val openAiResponse = gson.fromJson(responseBody, OpenAiResponse::class.java)
            
            if (openAiResponse.error != null) {
                return Result.failure(Exception("API error: ${openAiResponse.error.message}"))
            }

            val content = openAiResponse.choices?.firstOrNull()?.message?.content
                ?: return Result.failure(Exception("Empty response"))

            return Result.success(content)

        } catch (e: Exception) {
            Logger.e("API chat failed", e, TAG)
            return Result.failure(e)
        }
    }

    /**
     * OpenAI兼容格式 多模态请求（带图片，支持硅基流动等）
     */
    private fun chatOpenAiWithImage(
        prompt: String,
        imageBase64: String,
        systemPrompt: String?,
        baseUrl: String = OPENAI_BASE_URL
    ): Result<String> {
        try {
            val contentParts = listOf(
                mapOf("type" to "text", "text" to prompt),
                mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf(
                        "url" to "data:image/png;base64,$imageBase64",
                        "detail" to "high"
                    )
                )
            )

            val messages = buildList {
                if (!systemPrompt.isNullOrBlank()) {
                    add(mapOf("role" to "system", "content" to systemPrompt))
                }
                add(mapOf("role" to "user", "content" to contentParts))
            }

            val defaultModel = when (settings.provider) {
                AiProvider.SILICONFLOW -> AiConfig.SILICONFLOW_MODEL
                else -> AiConfig.DEFAULT_MODEL_OPENAI
            }

            val requestBody = mapOf(
                "model" to (settings.model.ifBlank { defaultModel }),
                "messages" to messages,
                "max_tokens" to settings.maxTokens,
                "temperature" to settings.temperature
            )

            val actualBaseUrl = baseUrl.trimEnd('/')

            val httpRequest = Request.Builder()
                .url("$actualBaseUrl/chat/completions")
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: ""

            Logger.d("Vision API Response: ${responseBody.take(500)}", TAG)

            if (!response.isSuccessful) {
                return Result.failure(Exception("API error: ${response.code} - $responseBody"))
            }

            val openAiResponse = gson.fromJson(responseBody, OpenAiResponse::class.java)
            
            if (openAiResponse.error != null) {
                return Result.failure(Exception("API error: ${openAiResponse.error.message}"))
            }

            val content = openAiResponse.choices?.firstOrNull()?.message?.content
                ?: return Result.failure(Exception("Empty response"))

            return Result.success(content)

        } catch (e: Exception) {
            Logger.e("Vision API chat failed", e, TAG)
            return Result.failure(e)
        }
    }

    /**
     * Claude 聊天请求
     */
    private fun chatClaude(
        messages: List<ChatMessage>,
        systemPrompt: String?
    ): Result<String> {
        try {
            val claudeMessages = messages
                .filter { it.role != MessageRole.SYSTEM }
                .map { msg ->
                    ClaudeMessage(
                        role = if (msg.role == MessageRole.USER) "user" else "assistant",
                        content = msg.content as String
                    )
                }

            val request = ClaudeRequest(
                model = settings.model.ifBlank { AiConfig.DEFAULT_MODEL_CLAUDE },
                messages = claudeMessages,
                maxTokens = settings.maxTokens,
                system = systemPrompt
            )

            val httpRequest = Request.Builder()
                .url("$CLAUDE_BASE_URL/messages")
                .addHeader("x-api-key", settings.apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(request).toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: ""

            Logger.d("Claude Response: ${responseBody.take(500)}", TAG)

            if (!response.isSuccessful) {
                return Result.failure(Exception("API error: ${response.code} - $responseBody"))
            }

            val claudeResponse = gson.fromJson(responseBody, ClaudeResponse::class.java)
            
            if (claudeResponse.error != null) {
                return Result.failure(Exception("API error: ${claudeResponse.error.message}"))
            }

            val content = claudeResponse.content?.firstOrNull { it.type == "text" }?.text
                ?: return Result.failure(Exception("Empty response"))

            return Result.success(content)

        } catch (e: Exception) {
            Logger.e("Claude chat failed", e, TAG)
            return Result.failure(e)
        }
    }

    /**
     * Claude 多模态请求（带图片）
     */
    private fun chatClaudeWithImage(
        prompt: String,
        imageBase64: String,
        systemPrompt: String?
    ): Result<String> {
        try {
            val contentParts = listOf(
                mapOf(
                    "type" to "image",
                    "source" to mapOf(
                        "type" to "base64",
                        "media_type" to "image/png",
                        "data" to imageBase64
                    )
                ),
                mapOf("type" to "text", "text" to prompt)
            )

            val requestBody = mapOf(
                "model" to (settings.model.ifBlank { AiConfig.DEFAULT_MODEL_CLAUDE }),
                "max_tokens" to settings.maxTokens,
                "system" to (systemPrompt ?: ""),
                "messages" to listOf(
                    mapOf("role" to "user", "content" to contentParts)
                )
            )

            val httpRequest = Request.Builder()
                .url("$CLAUDE_BASE_URL/messages")
                .addHeader("x-api-key", settings.apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: ""

            Logger.d("Claude Vision Response: ${responseBody.take(500)}", TAG)

            if (!response.isSuccessful) {
                return Result.failure(Exception("API error: ${response.code} - $responseBody"))
            }

            val claudeResponse = gson.fromJson(responseBody, ClaudeResponse::class.java)
            
            if (claudeResponse.error != null) {
                return Result.failure(Exception("API error: ${claudeResponse.error.message}"))
            }

            val content = claudeResponse.content?.firstOrNull { it.type == "text" }?.text
                ?: return Result.failure(Exception("Empty response"))

            return Result.success(content)

        } catch (e: Exception) {
            Logger.e("Claude vision chat failed", e, TAG)
            return Result.failure(e)
        }
    }

    /**
     * 测试API连接
     */
    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val result = chat(
                messages = listOf(ChatMessage(MessageRole.USER, "Hello")),
                systemPrompt = "Reply with 'OK' only."
            )
            Result.success(result.isSuccess)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

