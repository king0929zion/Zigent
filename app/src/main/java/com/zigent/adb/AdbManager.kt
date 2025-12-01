package com.zigent.adb

import android.content.Context
import android.graphics.Bitmap
import com.zigent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADB管理器
 * 统一管理ADB连接和命令执行
 */
@Singleton
class AdbManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AdbManager"
    }

    // ADB连接
    private val connection = AdbConnection(context)
    
    // ADB命令执行器
    private val commands = AdbCommands(context, connection)
    
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // 连接状态
    private val _connectionState = MutableStateFlow(AdbConnectionState.DISCONNECTED)
    val connectionState: StateFlow<AdbConnectionState> = _connectionState.asStateFlow()
    
    // 是否正在自动重连
    private var isAutoReconnecting = false
    
    init {
        // 监听连接状态变化
        connection.listener = object : AdbConnectionListener {
            override fun onStateChanged(state: AdbConnectionState) {
                _connectionState.value = state
                Logger.d("ADB state changed: $state", TAG)
                
                // 断开连接时尝试自动重连
                if (state == AdbConnectionState.DISCONNECTED && isAutoReconnecting) {
                    scope.launch {
                        delay(AdbConfig.RECONNECT_INTERVAL)
                        connect()
                    }
                }
            }
            
            override fun onError(message: String) {
                Logger.e("ADB error: $message", TAG)
            }
        }
    }

    /**
     * 连接ADB
     */
    suspend fun connect(): Boolean {
        return connection.connect()
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        isAutoReconnecting = false
        connection.disconnect()
    }

    /**
     * 启用自动重连
     */
    fun enableAutoReconnect() {
        isAutoReconnecting = true
    }

    /**
     * 禁用自动重连
     */
    fun disableAutoReconnect() {
        isAutoReconnecting = false
    }

    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean {
        return connectionState.value == AdbConnectionState.CONNECTED
    }

    // ==================== 屏幕数据采集 ====================

    /**
     * 截取屏幕截图
     */
    suspend fun takeScreenshot(): Bitmap? {
        return commands.takeScreenshot()
    }

    /**
     * 获取截图的Base64编码
     */
    suspend fun takeScreenshotBase64(): String? {
        return commands.takeScreenshotBase64()
    }

    /**
     * 导出UI层级
     */
    suspend fun dumpUiHierarchy(): String? {
        return commands.dumpUiHierarchy()
    }

    /**
     * 获取当前Activity
     */
    suspend fun getCurrentActivity(): String? {
        return commands.getCurrentActivity()
    }

    /**
     * 获取当前包名
     */
    suspend fun getCurrentPackage(): String? {
        return commands.getCurrentPackage()
    }

    // ==================== 输入操作 ====================

    /**
     * 点击
     */
    suspend fun tap(x: Int, y: Int): Boolean {
        return commands.tap(x, y)
    }

    /**
     * 滑动
     */
    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Int = 300): Boolean {
        return commands.swipe(startX, startY, endX, endY, duration)
    }

    /**
     * 长按
     */
    suspend fun longPress(x: Int, y: Int, duration: Int = 500): Boolean {
        return commands.longPress(x, y, duration)
    }

    /**
     * 输入文本
     */
    suspend fun inputText(text: String): Boolean {
        return commands.inputText(text)
    }

    /**
     * 按键
     */
    suspend fun pressKey(keyCode: Int): Boolean {
        return commands.pressKey(keyCode)
    }

    /**
     * 返回键
     */
    suspend fun pressBack(): Boolean {
        return commands.pressBack()
    }

    /**
     * Home键
     */
    suspend fun pressHome(): Boolean {
        return commands.pressHome()
    }

    /**
     * 回车键
     */
    suspend fun pressEnter(): Boolean {
        return commands.pressEnter()
    }

    // ==================== 应用操作 ====================

    /**
     * 启动应用
     */
    suspend fun startApp(packageName: String, activityName: String? = null): Boolean {
        return commands.startApp(packageName, activityName)
    }

    /**
     * 停止应用
     */
    suspend fun forceStopApp(packageName: String): Boolean {
        return commands.forceStopApp(packageName)
    }

    // ==================== 系统信息 ====================

    /**
     * 获取屏幕尺寸
     */
    suspend fun getScreenSize(): Pair<Int, Int>? {
        return commands.getScreenSize()
    }

    /**
     * 执行自定义Shell命令
     */
    suspend fun executeCommand(command: String): ShellResult {
        return connection.executeShellCommand(command)
    }

    /**
     * 释放资源
     */
    fun release() {
        disconnect()
        connection.release()
        scope.cancel()
    }
}

