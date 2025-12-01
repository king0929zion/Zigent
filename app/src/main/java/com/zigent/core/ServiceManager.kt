package com.zigent.core

import android.content.Context
import com.zigent.accessibility.ZigentAccessibilityService
import com.zigent.shizuku.ShizukuManager
import com.zigent.shizuku.ShizukuState
import com.zigent.utils.Logger
import com.zigent.utils.PermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 服务状态
 */
data class ServiceStatus(
    val overlayPermission: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val shizukuState: ShizukuState = ShizukuState.NOT_INSTALLED,
    val microphonePermission: Boolean = false,
    val aiConfigured: Boolean = false
) {
    val isReady: Boolean get() = overlayPermission && (accessibilityEnabled || shizukuState == ShizukuState.READY)
    val canExecuteActions: Boolean get() = accessibilityEnabled || shizukuState == ShizukuState.READY
    
    fun getReadinessMessage(): String {
        return when {
            !overlayPermission -> "需要悬浮窗权限"
            !accessibilityEnabled && shizukuState != ShizukuState.READY -> "需要开启无障碍服务或Shizuku"
            !microphonePermission -> "需要麦克风权限（可选）"
            !aiConfigured -> "需要配置AI设置"
            else -> "已就绪"
        }
    }
    
    fun getMissingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (!overlayPermission) missing.add("悬浮窗权限")
        if (!accessibilityEnabled) missing.add("无障碍服务")
        if (!microphonePermission) missing.add("麦克风权限")
        return missing
    }
}

/**
 * 统一的服务管理器
 * 负责管理和协调所有系统服务
 */
class ServiceManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ServiceManager"
        
        @Volatile
        private var instance: ServiceManager? = null
        
        fun getInstance(context: Context): ServiceManager {
            return instance ?: synchronized(this) {
                instance ?: ServiceManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // 服务状态
    private val _status = MutableStateFlow(ServiceStatus())
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()
    
    // Shizuku 管理器
    private val shizukuManager = ShizukuManager.getInstance(context)
    
    init {
        // 初始化 Shizuku
        shizukuManager.initialize()
    }

    /**
     * 刷新服务状态
     */
    fun refreshStatus(aiConfigured: Boolean = false) {
        shizukuManager.updateState()
        
        _status.value = ServiceStatus(
            overlayPermission = PermissionHelper.canDrawOverlays(context),
            accessibilityEnabled = ZigentAccessibilityService.isServiceAvailable(),
            shizukuState = shizukuManager.state.value,
            microphonePermission = PermissionHelper.hasRecordAudioPermission(context),
            aiConfigured = aiConfigured
        )
        
        Logger.d("Service status updated: ${_status.value}", TAG)
    }

    /**
     * 检查悬浮窗权限
     */
    fun hasOverlayPermission(): Boolean = PermissionHelper.canDrawOverlays(context)

    /**
     * 检查无障碍服务
     */
    fun isAccessibilityEnabled(): Boolean = ZigentAccessibilityService.isServiceAvailable()

    /**
     * 检查 Shizuku 状态
     */
    fun getShizukuState(): ShizukuState = shizukuManager.state.value

    /**
     * 请求 Shizuku 权限
     */
    fun requestShizukuPermission() {
        shizukuManager.requestPermission()
    }

    /**
     * 检查麦克风权限
     */
    fun hasMicrophonePermission(): Boolean = PermissionHelper.hasRecordAudioPermission(context)

    /**
     * 获取当前可用的执行方式
     */
    fun getAvailableExecutors(): List<String> {
        val executors = mutableListOf<String>()
        if (isAccessibilityEnabled()) executors.add("无障碍服务")
        if (shizukuManager.isAvailable()) executors.add("Shizuku")
        return executors
    }

    /**
     * 检查是否可以执行操作
     */
    fun canExecuteActions(): Boolean {
        return isAccessibilityEnabled() || shizukuManager.isAvailable()
    }

    /**
     * 检查是否可以截屏
     */
    fun canTakeScreenshot(): Boolean {
        return shizukuManager.isAvailable()
    }

    /**
     * 获取 Shizuku 管理器
     */
    fun getShizukuManager(): ShizukuManager = shizukuManager

    /**
     * 释放资源
     */
    fun release() {
        shizukuManager.release()
    }
}

