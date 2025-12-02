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
import java.util.concurrent.TimeUnit

/**
 * AI客户端
 * 双模型架构：
 * - 主 LLM (DeepSeek-V3.2-Exp): 任务理解 + Function Calling
 * - 辅助 VLM (Qwen3-Omni-Captioner): 图片描述
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
     * 使用 VLM 描述图片内容
     * 当 LLM 调用 describe_screen 工具时使用
     */
    suspend fun describeImage(
        imageBase64: String,
        context: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        Logger.i("=== VLM Describe Image ===", TAG)
        
        val prompt = buildString {
            append("请详细描述这个手机屏幕截图的内容。包括：\n")
            append("1. 当前显示的应用或页面\n")
            append("2. 屏幕上的主要元素（按钮、文字、图片等）\n")
            append("3. 界面布局和结构\n")
            append("4. 任何重要的视觉信息\n")
            if (!context.isNullOrBlank()) {
                append("\n用户当前任务: $context\n")
                append("请特别关注与此任务相关的元素。")
            }
        }
        
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
            
            val messages = listOf(
                mapOf("role" to "user", "content" to contentParts)
            )
            
            // 使用 VLM 模型
            val visionModel = settings.visionModel.ifBlank { AiConfig.SILICONFLOW_VLM_MODEL }
            Logger.i("Using VLM model: $visionModel", TAG)
            
            val requestBody = mapOf(
                "model" to visionModel,
                "messages" to messages,
                "max_tokens" to 2048,
                "temperature" to 0.3f  // 较低温度，更准确的描述
            )
            
            val baseUrl = when (settings.provider) {
                AiProvider.SILICONFLOW -> SILICONFLOW_BASE_URL
                AiProvider.OPENAI -> OPENAI_BASE_URL
                AiProvider.CUSTOM -> settings.baseUrl
                else -> SILICONFLOW_BASE_URL
            }.trimEnd('/')
            
            val httpRequest = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: ""
            
            Logger.d("VLM Response: ${responseBody.take(500)}", TAG)
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("VLM API error: ${response.code}"))
            }
            
            val openAiResponse = gson.fromJson(responseBody, OpenAiResponse::class.java)
            
            if (openAiResponse.error != null) {
                return@withContext Result.failure(Exception("VLM error: ${openAiResponse.error.message}"))
            }
            
            val content = openAiResponse.choices?.firstOrNull()?.message?.content
                ?: return@withContext Result.failure(Exception("VLM返回空内容"))
            
            Logger.i("VLM description length: ${content.length}", TAG)
            Result.success(content)
            
        } catch (e: Exception) {
            Logger.e("VLM describe image failed", e, TAG)
            Result.failure(e)
        }
    }

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
     * 使用 LLM 进行工具调用（Function Calling）
     * 主要决策入口 - 不需要图片
     */
    suspend fun chatWithTools(
        prompt: String,
        tools: List<Tool>,
        systemPrompt: String? = null
    ): Result<ToolCallResult> = withContext(Dispatchers.IO) {
        Logger.i("=== LLM Tool Call ===", TAG)
        
        try {
            val messages = buildList<Any> {
                if (!systemPrompt.isNullOrBlank()) {
                    add(mapOf("role" to "system", "content" to systemPrompt))
                }
                add(mapOf("role" to "user", "content" to prompt))
            }
            
            // 使用 LLM 模型（支持 Function Calling）
            val llmModel = settings.model.ifBlank { AiConfig.SILICONFLOW_LLM_MODEL }
            Logger.i("Using LLM model: $llmModel", TAG)
            Logger.i("Tools count: ${tools.size}", TAG)
            
            val request = ToolRequest(
                model = llmModel,
                messages = messages,
                tools = tools,
                toolChoice = "auto",
                maxTokens = settings.maxTokens,
                temperature = settings.temperature
            )
            
            val baseUrl = when (settings.provider) {
                AiProvider.SILICONFLOW -> SILICONFLOW_BASE_URL
                AiProvider.OPENAI -> OPENAI_BASE_URL
                AiProvider.CUSTOM -> settings.baseUrl
                else -> SILICONFLOW_BASE_URL
            }.trimEnd('/')
            
            val httpRequest = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(request).toRequestBody("application/json".toMediaType()))
                .build()
            
            val requestJson = gson.toJson(request)
            Logger.d("Request: ${requestJson.take(2000)}", TAG)
            
            val response = httpClient.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: ""
            
            Logger.i("Response code: ${response.code}", TAG)
            Logger.d("Response: ${responseBody.take(2000)}", TAG)
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("LLM API error: ${response.code} - $responseBody"))
            }
            
            if (responseBody.isBlank()) {
                return@withContext Result.failure(Exception("LLM返回空响应"))
            }
            
            val toolResponse = try {
                gson.fromJson(responseBody, ToolResponse::class.java)
            } catch (e: Exception) {
                Logger.e("Failed to parse response", e, TAG)
                return@withContext Result.failure(Exception("无法解析响应: ${e.message}"))
            }
            
            if (toolResponse?.error != null) {
                return@withContext Result.failure(Exception("API错误: ${toolResponse.error.message}"))
            }
            
            val choice = toolResponse?.choices?.firstOrNull()
            if (choice == null) {
                Logger.w("No choices in response", TAG)
                return@withContext Result.success(ToolCallResult.empty())
            }
            
            val reasoning = choice.message?.reasoningContent
            
            // 检查工具调用
            val toolCalls = choice.message?.toolCalls
            if (!toolCalls.isNullOrEmpty()) {
                val toolCall = toolCalls.first()
                Logger.i("✅ Tool call: ${toolCall.function.name}", TAG)
                Logger.d("Arguments: ${toolCall.function.arguments}", TAG)
                return@withContext Result.success(ToolCallResult.fromToolCall(toolCall, reasoning))
            }
            
            // 纯文本响应
            val content = choice.message?.content
            if (!content.isNullOrBlank()) {
                Logger.i("📝 Text response (no tool)", TAG)
                return@withContext Result.success(ToolCallResult.fromText(content, reasoning))
            }
            
            Logger.w("Empty response from LLM", TAG)
            Result.success(ToolCallResult.empty())
            
        } catch (e: Exception) {
            Logger.e("LLM tool call failed", e, TAG)
            Result.failure(e)
        }
    }

    /**
     * 发送带图片的聊天请求（多模态，用于兼容）
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
     * OpenAI兼容格式 聊天请求
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

            val llmModel = settings.model.ifBlank { AiConfig.SILICONFLOW_LLM_MODEL }

            val request = OpenAiRequest(
                model = llmModel,
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
     * OpenAI兼容格式 多模态请求（带图片）
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

            // 使用 VLM 模型处理图片
            val visionModel = settings.visionModel.ifBlank { AiConfig.SILICONFLOW_VLM_MODEL }

            val requestBody = mapOf(
                "model" to visionModel,
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

