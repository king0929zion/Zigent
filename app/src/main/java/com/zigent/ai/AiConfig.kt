package com.zigent.ai

/**
 * AI配置
 */
object AiConfig {
    // API超时时间（秒）
    const val API_TIMEOUT = 120L
    
    // 最大重试次数
    const val MAX_RETRY = 3
    
    // 默认模型配置
    const val DEFAULT_MODEL_OPENAI = "gpt-4o"
    const val DEFAULT_MODEL_CLAUDE = "claude-3-5-sonnet-20241022"
    
    // 硅基流动配置
    const val SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1"
    
    // 主 LLM 模型（默认 GLM-4.6，支持 Function Calling）
    const val SILICONFLOW_LLM_MODEL = "zai-org/GLM-4.6"
    
    // 备选 LLM 模型
    const val SILICONFLOW_LLM_DEEPSEEK = "deepseek-ai/DeepSeek-V3.2-Exp"
    
    // 辅助 VLM 模型（用于图片描述）
    const val SILICONFLOW_VLM_MODEL = "Qwen/Qwen3-VL-235B-A22B-Instruct"
    
    // 语音识别模型
    const val SILICONFLOW_ASR_MODEL = "FunAudioLLM/SenseVoiceSmall"
    
    // 可选的 LLM 模型列表
    val SILICONFLOW_LLM_OPTIONS = listOf(
        "zai-org/GLM-4.6" to "GLM-4.6 (推荐)",
        "deepseek-ai/DeepSeek-V3.2-Exp" to "DeepSeek V3.2",
        "Qwen/Qwen3-Next-80B-A3B-Instruct" to "Qwen3 Next 80B",
        "Qwen/Qwen3-235B-A22B-Instruct" to "Qwen3 235B"
    )
    
    // 兼容旧配置
    const val SILICONFLOW_MODEL = SILICONFLOW_LLM_MODEL
    
    // 最大Token数
    const val MAX_TOKENS = 4096
    
    // 温度参数
    const val TEMPERATURE = 0.7f
    
    // Agent最大执行步数（防止无限循环）
    const val MAX_AGENT_STEPS = 20
    
    // 每步之间的等待时间（毫秒）
    const val STEP_DELAY = 500L
    
    // 操作执行后等待页面响应时间（毫秒）
    const val ACTION_WAIT_TIME = 1000L
}

/**
 * AI提供商类型
 */
enum class AiProvider {
    SILICONFLOW,  // 硅基流动（默认推荐）
    OPENAI,
    CLAUDE,
    CUSTOM        // 自定义API（兼容OpenAI格式）
}

/**
 * AI配置数据类
 */
data class AiSettings(
    val provider: AiProvider = AiProvider.SILICONFLOW,
    val apiKey: String = "",
    val baseUrl: String = AiConfig.SILICONFLOW_BASE_URL,
    val model: String = AiConfig.SILICONFLOW_LLM_MODEL,  // 主 LLM 模型
    val visionModel: String = AiConfig.SILICONFLOW_VLM_MODEL,  // 辅助 VLM 模型
    val maxTokens: Int = AiConfig.MAX_TOKENS,
    val temperature: Float = AiConfig.TEMPERATURE
)

