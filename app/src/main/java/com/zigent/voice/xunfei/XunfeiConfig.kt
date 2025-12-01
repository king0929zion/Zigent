package com.zigent.voice.xunfei

/**
 * 讯飞语音服务配置
 * 基于中英识别大模型 API 文档: https://www.xfyun.cn/doc/spark/spark_zh_iat.html
 */
object XunfeiConfig {
    // 中英识别大模型API地址（新版）
    const val IAT_HOST_URL = "wss://iat.xf-yun.com/v1"
    const val IAT_HOST = "iat.xf-yun.com"
    const val IAT_PATH = "/v1"
    
    // 默认配置（用户可在设置中修改）
    var APPID = "b66b84d7"
    var API_KEY = "f62a9f4852e712c55c09895e3ce240de"
    var API_SECRET = "YjVhN2UxYzJiM2NmNzNjZGY2MDE1N2I1"
    
    // 音频参数
    const val AUDIO_FORMAT = "audio/L16;rate=16000"
    const val AUDIO_ENCODING = "raw"  // pcm格式
    const val SAMPLE_RATE = 16000
    const val FRAME_SIZE = 1280 // 每帧音频大小（40ms, 16000*16bit*0.04s/8=1280字节）
    
    // 语言设置
    const val LANGUAGE = "zh_cn" // 中文
    const val DOMAIN = "iat" // 日常用语
    const val ACCENT = "mandarin" // 普通话
    
    // 其他设置
    const val VAD_EOS = 60000 // 静音检测时长（毫秒），设置为60秒，实际由用户手动停止
    const val DWA = "wpgs" // 动态修正（开启流式结果返回）
    const val PTT = 1 // 标点符号（1-返回标点）
    const val NUF = 1 // 数字格式（1-按数值格式输出）
    
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

