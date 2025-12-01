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
    
    // 硅基流动配置（默认推荐）
    const val SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1"
    const val SILICONFLOW_MODEL = "Qwen/Qwen3-VL-235B-A22B-Instruct"
    
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
    val model: String = AiConfig.SILICONFLOW_MODEL,
    val maxTokens: Int = AiConfig.MAX_TOKENS,
    val temperature: Float = AiConfig.TEMPERATURE
)

