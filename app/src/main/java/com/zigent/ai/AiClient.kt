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

