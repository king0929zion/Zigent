package com.zigent.adb

/**
 * ADB配置常量
 */
object AdbConfig {
    // 默认ADB端口
    const val DEFAULT_ADB_PORT = 5555
    
    // 连接超时时间（毫秒）
    const val CONNECTION_TIMEOUT = 5000L
    
    // 命令执行超时时间（毫秒）
    const val COMMAND_TIMEOUT = 30000L
    
    // 重连间隔时间（毫秒）
    const val RECONNECT_INTERVAL = 3000L
    
    // 最大重试次数
    const val MAX_RETRY_COUNT = 3
    
    // 截图临时文件路径
    const val SCREENSHOT_PATH = "/sdcard/zigent_screenshot.png"
    
    // UI dump临时文件路径  
    const val UI_DUMP_PATH = "/sdcard/zigent_ui.xml"
}

