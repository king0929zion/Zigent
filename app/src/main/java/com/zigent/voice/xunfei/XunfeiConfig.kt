package com.zigent.voice.xunfei

/**
 * 讯飞语音服务配置
 */
object XunfeiConfig {
    // 实时语音转写API地址
    const val IAT_HOST_URL = "wss://iat-api.xfyun.cn/v2/iat"
    
    // 默认配置（用户可在设置中修改）
    var APPID = "b66b84d7"
    var API_KEY = "f62a9f4852e712c55c09895e3ce240de"
    var API_SECRET = "YjVhN2UxYzJiM2NmNzNjZGY2MDE1N2I1"
    
    // 音频参数
    const val AUDIO_FORMAT = "audio/L16;rate=16000"
    const val AUDIO_ENCODING = "raw"
    const val SAMPLE_RATE = 16000
    const val FRAME_SIZE = 1280 // 每帧音频大小（40ms）
    
    // 语言设置
    const val LANGUAGE = "zh_cn" // 中文
    const val DOMAIN = "iat" // 日常用语
    const val ACCENT = "mandarin" // 普通话
    
    // 其他设置
    const val VAD_EOS = 3000 // 静音检测时长（毫秒）
    const val DWA = "wpgs" // 动态修正
    const val PD = "game" // 领域个性化
    const val PTT = 1 // 标点符号
    const val RLT_FMT = "plain" // 返回格式
    const val NUF = 1 // 数字格式
    
    /**
     * 配置API凭证
     */
    fun configure(appId: String, apiKey: String, apiSecret: String) {
        APPID = appId
        API_KEY = apiKey
        API_SECRET = apiSecret
    }
    
    /**
     * 检查是否已配置
     */
    fun isConfigured(): Boolean {
        return APPID.isNotBlank() && API_KEY.isNotBlank() && API_SECRET.isNotBlank()
    }
}

