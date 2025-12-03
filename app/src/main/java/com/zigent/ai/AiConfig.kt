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
    
    // ==================== 豆包配置（默认） ====================
    // 火山方舟 API 地址（兼容 OpenAI 格式）
    const val DOUBAO_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
    
    // 豆包 LLM 模型（默认）
    const val DOUBAO_LLM_MODEL = "doubao-seed-1.6"
    
    // 豆包 VLM 模型（默认）
    const val DOUBAO_VLM_MODEL = "doubao-seed-1.6-vision"
    
    // 豆包可选 LLM 模型列表
    val DOUBAO_LLM_OPTIONS = listOf(
        "doubao-seed-1.6" to "Doubao Seed 1.6 (推荐)",
        "doubao-seed-1.6-lite" to "Doubao Seed 1.6 Lite",
        "doubao-seed-1.6-flash" to "Doubao Seed 1.6 Flash",
        "doubao-seed-1.6-thinking" to "Doubao Seed 1.6 Thinking",
        "doubao-1.5-pro-32k" to "Doubao 1.5 Pro 32K",
        "deepseek-v3.1" to "DeepSeek V3.1"
    )
    
    // 豆包可选 VLM 模型列表
    val DOUBAO_VLM_OPTIONS = listOf(
        "doubao-seed-1.6-vision" to "Doubao Seed 1.6 Vision (推荐)",
        "doubao-1.5-vision-pro" to "Doubao 1.5 Vision Pro",
        "doubao-1.5-vision-lite" to "Doubao 1.5 Vision Lite"
    )
    
    // ==================== 硅基流动配置 ====================
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
    DOUBAO,       // 豆包（默认推荐）
    SILICONFLOW,  // 硅基流动
    OPENAI,
    CLAUDE,
    CUSTOM        // 自定义API（兼容OpenAI格式）
}

/**
 * AI配置数据类
 * 
 * 配置说明：
 * - siliconFlowApiKey: 硅基流动API Key（必填，用于语音识别）
 * - provider: Agent模型提供商（豆包/硅基流动等）
 * - apiKey: 当前选择的Agent提供商的API Key
 */
data class AiSettings(
    // 硅基流动 API Key（必填，用于语音识别）
    val siliconFlowApiKey: String = "",
    
    // Agent 模型提供商（默认豆包）
    val provider: AiProvider = AiProvider.DOUBAO,
    
    // 当前 Agent 提供商的 API Key
    val apiKey: String = "",
    
    // API 地址
    val baseUrl: String = AiConfig.DOUBAO_BASE_URL,
    
    // 主 LLM 模型
    val model: String = AiConfig.DOUBAO_LLM_MODEL,
    
    // 辅助 VLM 模型
    val visionModel: String = AiConfig.DOUBAO_VLM_MODEL,
    
    val maxTokens: Int = AiConfig.MAX_TOKENS,
    val temperature: Float = AiConfig.TEMPERATURE
) {
    /**
     * 检查语音识别是否可用（硅基流动API Key已配置）
     */
    fun isAsrAvailable(): Boolean = siliconFlowApiKey.isNotBlank()
    
    /**
     * 检查Agent是否可用（当前提供商API Key已配置）
     */
    fun isAgentAvailable(): Boolean = apiKey.isNotBlank()
    
    /**
     * 获取当前有效的Agent API Key
     * 如果选择硅基流动作为Agent，优先使用Agent的apiKey，否则回退到siliconFlowApiKey
     */
    fun getEffectiveAgentApiKey(): String {
        return if (provider == AiProvider.SILICONFLOW && apiKey.isBlank()) {
            siliconFlowApiKey
        } else {
            apiKey
        }
    }
}

