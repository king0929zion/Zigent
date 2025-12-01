package com.zigent.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import com.zigent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Shizuku 连接状态
 */
enum class ShizukuState {
    NOT_INSTALLED,      // Shizuku 未安装
    NOT_RUNNING,        // Shizuku 未运行
    NOT_AUTHORIZED,     // 未授权
    READY               // 已就绪可用
}

/**
 * Shizuku 管理器
 * 通过 Shizuku 获取 ADB 级别权限执行系统命令
 */
class ShizukuManager(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuManager"
        const val REQUEST_CODE_PERMISSION = 1001
        
        @Volatile
        private var instance: ShizukuManager? = null
        
        fun getInstance(context: Context): ShizukuManager {
            return instance ?: synchronized(this) {
                instance ?: ShizukuManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // 状态
    private val _state = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()
    
    // 是否已初始化监听
    private var isListenerAdded = false
    
    // Shizuku 绑定监听
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Logger.i("Shizuku binder received", TAG)
        checkPermission()
    }
    
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Logger.w("Shizuku binder dead", TAG)
        _state.value = ShizukuState.NOT_RUNNING
    }
    
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE_PERMISSION) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Logger.i("Shizuku permission granted", TAG)
                _state.value = ShizukuState.READY
            } else {
                Logger.w("Shizuku permission denied", TAG)
                _state.value = ShizukuState.NOT_AUTHORIZED
            }
        }
    }

    /**
     * 初始化 Shizuku
     */
    fun initialize() {
        if (isListenerAdded) return
        
        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            isListenerAdded = true
            
            // 检查当前状态
            updateState()
            
            Logger.i("ShizukuManager initialized", TAG)
        } catch (e: Exception) {
            Logger.e("Failed to initialize Shizuku", e, TAG)
            _state.value = ShizukuState.NOT_INSTALLED
        }
    }

    /**
     * 更新状态
     */
    fun updateState() {
        try {
            if (!isShizukuInstalled()) {
                _state.value = ShizukuState.NOT_INSTALLED
                return
            }
            
            if (!Shizuku.pingBinder()) {
                _state.value = ShizukuState.NOT_RUNNING
                return
            }
            
            checkPermission()
        } catch (e: Exception) {
            Logger.e("Failed to update Shizuku state", e, TAG)
            _state.value = ShizukuState.NOT_INSTALLED
        }
    }

    /**
     * 检查 Shizuku 是否已安装
     */
    fun isShizukuInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 检查权限
     */
    private fun checkPermission() {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                _state.value = ShizukuState.READY
            } else {
                _state.value = ShizukuState.NOT_AUTHORIZED
            }
        } catch (e: Exception) {
            Logger.e("Failed to check Shizuku permission", e, TAG)
            _state.value = ShizukuState.NOT_RUNNING
        }
    }

    /**
     * 请求权限
     */
    fun requestPermission() {
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
            } else {
                _state.value = ShizukuState.READY
            }
        } catch (e: Exception) {
            Logger.e("Failed to request Shizuku permission", e, TAG)
        }
    }

    /**
     * 检查是否可用
     */
    fun isAvailable(): Boolean {
        return _state.value == ShizukuState.READY
    }

    /**
     * 执行 Shell 命令
     * 使用反射调用 Shizuku.newProcess (因为该方法在某些版本中是私有的)
     */
    suspend fun executeCommand(command: String): ShizukuResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext ShizukuResult(
                success = false,
                output = "",
                error = "Shizuku not available"
            )
        }
        
        try {
            Logger.d("Shizuku executing: $command", TAG)
            
            // 使用反射调用 Shizuku.newProcess
            val process = try {
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.isAccessible = true
                method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            } catch (e: Exception) {
                Logger.w("Shizuku.newProcess not available, falling back to Runtime", TAG)
                // 如果反射失败，尝试使用 Runtime（可能需要 root）
                Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            }
            
            val outputBuilder = StringBuilder()
            val errorBuilder = StringBuilder()
            
            // 读取输出
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    outputBuilder.appendLine(line)
                }
            }
            
            // 读取错误
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    errorBuilder.appendLine(line)
                }
            }
            
            val exitCode = process.waitFor()
            
            Logger.d("Shizuku result: exitCode=$exitCode, output=${outputBuilder.toString().take(100)}", TAG)
            
            ShizukuResult(
                success = exitCode == 0,
                output = outputBuilder.toString().trim(),
                error = errorBuilder.toString().trim(),
                exitCode = exitCode
            )
            
        } catch (e: Exception) {
            Logger.e("Shizuku command failed: $command", e, TAG)
            ShizukuResult(
                success = false,
                output = "",
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * 点击
     */
    suspend fun tap(x: Int, y: Int): Boolean {
        val result = executeCommand("input tap $x $y")
        return result.success
    }

    /**
     * 滑动
     */
    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Int = 300): Boolean {
        val result = executeCommand("input swipe $startX $startY $endX $endY $duration")
        return result.success
    }

    /**
     * 长按
     */
    suspend fun longPress(x: Int, y: Int, duration: Int = 500): Boolean {
        val result = executeCommand("input swipe $x $y $x $y $duration")
        return result.success
    }

    /**
     * 输入文本
     */
    suspend fun inputText(text: String): Boolean {
        // 对特殊字符进行转义
        val escapedText = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace(" ", "%s")
            .replace("&", "\\&")
            .replace("<", "\\<")
            .replace(">", "\\>")
        
        val result = executeCommand("input text \"$escapedText\"")
        return result.success
    }

    /**
     * 按键
     */
    suspend fun pressKey(keyCode: Int): Boolean {
        val result = executeCommand("input keyevent $keyCode")
        return result.success
    }

    /**
     * 截屏到文件
     */
    suspend fun takeScreenshot(path: String): Boolean {
        val result = executeCommand("screencap -p $path")
        return result.success
    }

    /**
     * 截屏获取 Base64
     */
    suspend fun takeScreenshotBase64(): String? {
        val result = executeCommand("screencap -p | base64 -w 0")
        return if (result.success && result.output.isNotBlank()) {
            result.output
        } else {
            null
        }
    }

    /**
     * 导出 UI 层级
     */
    suspend fun dumpUiHierarchy(): String? {
        val dumpPath = "/sdcard/window_dump.xml"
        val dumpResult = executeCommand("uiautomator dump $dumpPath")
        
        if (!dumpResult.success && !dumpResult.output.contains("UI hierchary dumped")) {
            return null
        }
        
        val catResult = executeCommand("cat $dumpPath")
        executeCommand("rm $dumpPath") // 清理
        
        return if (catResult.success) catResult.output else null
    }

    /**
     * 获取当前包名
     */
    suspend fun getCurrentPackage(): String? {
        val result = executeCommand("dumpsys window | grep mCurrentFocus")
        if (result.success && result.output.isNotBlank()) {
            val regex = Regex("([\\w.]+)/")
            val match = regex.find(result.output)
            return match?.groupValues?.get(1)
        }
        return null
    }

    /**
     * 启动应用
     */
    suspend fun startApp(packageName: String): Boolean {
        val result = executeCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        return result.success
    }

    /**
     * 强制停止应用
     */
    suspend fun forceStopApp(packageName: String): Boolean {
        val result = executeCommand("am force-stop $packageName")
        return result.success
    }

    /**
     * 获取屏幕尺寸
     */
    suspend fun getScreenSize(): Pair<Int, Int>? {
        val result = executeCommand("wm size")
        if (result.success) {
            val regex = Regex("(\\d+)x(\\d+)")
            val match = regex.find(result.output)
            if (match != null) {
                val width = match.groupValues[1].toIntOrNull()
                val height = match.groupValues[2].toIntOrNull()
                if (width != null && height != null) {
                    return Pair(width, height)
                }
            }
        }
        return null
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            if (isListenerAdded) {
                Shizuku.removeBinderReceivedListener(binderReceivedListener)
                Shizuku.removeBinderDeadListener(binderDeadListener)
                Shizuku.removeRequestPermissionResultListener(permissionResultListener)
                isListenerAdded = false
            }
        } catch (e: Exception) {
            Logger.e("Failed to release Shizuku", e, TAG)
        }
    }
}

/**
 * Shizuku 命令执行结果
 */
data class ShizukuResult(
    val success: Boolean,
    val output: String,
    val error: String = "",
    val exitCode: Int = -1
)

