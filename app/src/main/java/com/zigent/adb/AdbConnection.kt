package com.zigent.adb

import android.content.Context
import android.net.wifi.WifiManager
import com.zigent.utils.Logger
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ADB连接状态
 */
enum class AdbConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * ADB连接状态回调
 */
interface AdbConnectionListener {
    fun onStateChanged(state: AdbConnectionState)
    fun onError(message: String)
}

/**
 * WIFI ADB连接管理器
 * 负责建立和维护与本机的ADB连接
 */
class AdbConnection(private val context: Context) {

    companion object {
        private const val TAG = "AdbConnection"
    }

    // 连接状态
    var state: AdbConnectionState = AdbConnectionState.DISCONNECTED
        private set(value) {
            field = value
            listener?.onStateChanged(value)
        }

    // 状态监听器
    var listener: AdbConnectionListener? = null

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 当前设备IP
    private var deviceIp: String? = null

    // Shell进程
    private var shellProcess: Process? = null

    /**
     * 获取设备WIFI IP地址
     */
    fun getDeviceIp(): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipAddress = wifiInfo.ipAddress
            
            if (ipAddress != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    ipAddress shr 8 and 0xff,
                    ipAddress shr 16 and 0xff,
                    ipAddress shr 24 and 0xff
                )
            }
        } catch (e: Exception) {
            Logger.e("Failed to get device IP", e, TAG)
        }
        return null
    }

    /**
     * 检查ADB是否可用
     * 通过执行简单命令来验证
     */
    suspend fun checkAdbAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = executeShellCommand("echo test")
            result.isSuccess && result.output.trim() == "test"
        } catch (e: Exception) {
            Logger.e("ADB not available", e, TAG)
            false
        }
    }

    /**
     * 启用WIFI ADB（需要root或已开启USB调试）
     */
    suspend fun enableWifiAdb(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 尝试通过shell命令启用
            val result = executeShellCommand("setprop service.adb.tcp.port ${AdbConfig.DEFAULT_ADB_PORT}")
            if (result.isSuccess) {
                // 重启ADB服务
                executeShellCommand("stop adbd")
                delay(500)
                executeShellCommand("start adbd")
                delay(1000)
                
                deviceIp = getDeviceIp()
                Logger.i("WIFI ADB enabled on $deviceIp:${AdbConfig.DEFAULT_ADB_PORT}", TAG)
                return@withContext true
            }
        } catch (e: Exception) {
            Logger.e("Failed to enable WIFI ADB", e, TAG)
        }
        false
    }

    /**
     * 连接到WIFI ADB
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (state == AdbConnectionState.CONNECTED) {
            return@withContext true
        }

        state = AdbConnectionState.CONNECTING
        
        try {
            // 首先检查本地shell是否可用
            if (checkAdbAvailable()) {
                state = AdbConnectionState.CONNECTED
                Logger.i("ADB connected via local shell", TAG)
                return@withContext true
            }
            
            // 尝试启用WIFI ADB
            if (enableWifiAdb()) {
                state = AdbConnectionState.CONNECTED
                return@withContext true
            }
            
            state = AdbConnectionState.ERROR
            listener?.onError("无法建立ADB连接，请确保已开启USB调试")
            return@withContext false
            
        } catch (e: Exception) {
            Logger.e("ADB connection failed", e, TAG)
            state = AdbConnectionState.ERROR
            listener?.onError("ADB连接失败: ${e.message}")
            return@withContext false
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        shellProcess?.destroy()
        shellProcess = null
        state = AdbConnectionState.DISCONNECTED
        Logger.i("ADB disconnected", TAG)
    }

    /**
     * 执行Shell命令
     * @param command 要执行的命令
     * @return 执行结果
     */
    suspend fun executeShellCommand(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            Logger.d("Executing: $command", TAG)
            
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            
            val outputBuilder = StringBuilder()
            val errorBuilder = StringBuilder()
            
            // 读取标准输出
            val outputJob = launch {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            outputBuilder.appendLine(line)
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("Error reading output", e, TAG)
                }
            }
            
            // 读取错误输出
            val errorJob = launch {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            errorBuilder.appendLine(line)
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("Error reading error stream", e, TAG)
                }
            }
            
            // 等待完成（带超时）
            val completed = withTimeoutOrNull(AdbConfig.COMMAND_TIMEOUT) {
                outputJob.join()
                errorJob.join()
                process.waitFor()
            }
            
            if (completed == null) {
                process.destroy()
                return@withContext ShellResult(
                    isSuccess = false,
                    output = "",
                    error = "Command timeout"
                )
            }
            
            val exitCode = process.exitValue()
            val output = outputBuilder.toString().trim()
            val error = errorBuilder.toString().trim()
            
            Logger.d("Exit code: $exitCode, Output: ${output.take(100)}", TAG)
            
            ShellResult(
                isSuccess = exitCode == 0,
                output = output,
                error = error,
                exitCode = exitCode
            )
            
        } catch (e: Exception) {
            Logger.e("Shell command failed: $command", e, TAG)
            ShellResult(
                isSuccess = false,
                output = "",
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * 执行需要root权限的命令
     */
    suspend fun executeSuCommand(command: String): ShellResult = withContext(Dispatchers.IO) {
        executeShellCommand("su -c '$command'")
    }

    /**
     * 释放资源
     */
    fun release() {
        disconnect()
        scope.cancel()
    }
}

/**
 * Shell命令执行结果
 */
data class ShellResult(
    val isSuccess: Boolean,
    val output: String,
    val error: String = "",
    val exitCode: Int = -1
)

