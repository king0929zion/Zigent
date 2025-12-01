package com.zigent.adb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.zigent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * ADB命令执行器
 * 封装常用的ADB操作命令
 */
class AdbCommands(private val context: Context, private val connection: AdbConnection) {

    companion object {
        private const val TAG = "AdbCommands"
    }

    // ==================== 屏幕操作 ====================

    /**
     * 截取屏幕截图
     * @return 截图Bitmap，失败返回null
     */
    suspend fun takeScreenshot(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // 使用screencap命令截图
            val screenshotPath = AdbConfig.SCREENSHOT_PATH
            val result = connection.executeShellCommand("screencap -p $screenshotPath")
            
            if (!result.isSuccess) {
                Logger.e("Screenshot command failed: ${result.error}", TAG)
                return@withContext null
            }
            
            // 读取截图文件
            val file = File(screenshotPath)
            if (!file.exists()) {
                // 尝试从应用缓存目录读取
                val localPath = File(context.cacheDir, "screenshot.png")
                connection.executeShellCommand("cp $screenshotPath ${localPath.absolutePath}")
                
                if (localPath.exists()) {
                    val bitmap = BitmapFactory.decodeFile(localPath.absolutePath)
                    localPath.delete()
                    return@withContext bitmap
                }
                
                Logger.e("Screenshot file not found", TAG)
                return@withContext null
            }
            
            val bitmap = BitmapFactory.decodeFile(screenshotPath)
            
            // 清理临时文件
            connection.executeShellCommand("rm $screenshotPath")
            
            Logger.d("Screenshot captured: ${bitmap?.width}x${bitmap?.height}", TAG)
            bitmap
            
        } catch (e: Exception) {
            Logger.e("Failed to take screenshot", e, TAG)
            null
        }
    }

    /**
     * 获取屏幕截图的Base64编码
     * 用于发送给AI进行分析
     */
    suspend fun takeScreenshotBase64(): String? = withContext(Dispatchers.IO) {
        try {
            // 直接获取base64编码的截图
            val result = connection.executeShellCommand("screencap -p | base64")
            if (result.isSuccess && result.output.isNotEmpty()) {
                return@withContext result.output
            }
            
            // 备选方案：先截图再读取
            val screenshotPath = AdbConfig.SCREENSHOT_PATH
            connection.executeShellCommand("screencap -p $screenshotPath")
            
            val base64Result = connection.executeShellCommand("base64 $screenshotPath")
            connection.executeShellCommand("rm $screenshotPath")
            
            if (base64Result.isSuccess) {
                return@withContext base64Result.output
            }
            
            null
        } catch (e: Exception) {
            Logger.e("Failed to take screenshot base64", e, TAG)
            null
        }
    }

    // ==================== UI信息获取 ====================

    /**
     * 导出UI层级信息（使用uiautomator）
     * @return UI层级XML字符串
     */
    suspend fun dumpUiHierarchy(): String? = withContext(Dispatchers.IO) {
        try {
            val dumpPath = AdbConfig.UI_DUMP_PATH
            
            // 执行uiautomator dump
            val dumpResult = connection.executeShellCommand("uiautomator dump $dumpPath")
            
            if (!dumpResult.isSuccess && !dumpResult.output.contains("UI hierchary dumped")) {
                Logger.e("UI dump failed: ${dumpResult.error}", TAG)
                return@withContext null
            }
            
            // 读取dump文件
            val catResult = connection.executeShellCommand("cat $dumpPath")
            
            // 清理临时文件
            connection.executeShellCommand("rm $dumpPath")
            
            if (catResult.isSuccess) {
                Logger.d("UI hierarchy dumped, size: ${catResult.output.length}", TAG)
                return@withContext catResult.output
            }
            
            null
        } catch (e: Exception) {
            Logger.e("Failed to dump UI hierarchy", e, TAG)
            null
        }
    }

    /**
     * 获取当前Activity信息
     */
    suspend fun getCurrentActivity(): String? = withContext(Dispatchers.IO) {
        try {
            val result = connection.executeShellCommand(
                "dumpsys activity activities | grep mResumedActivity"
            )
            if (result.isSuccess && result.output.isNotEmpty()) {
                // 解析输出获取Activity名称
                val regex = Regex("([\\w.]+/[\\w.]+)")
                val match = regex.find(result.output)
                return@withContext match?.value
            }
            null
        } catch (e: Exception) {
            Logger.e("Failed to get current activity", e, TAG)
            null
        }
    }

    /**
     * 获取当前包名
     */
    suspend fun getCurrentPackage(): String? = withContext(Dispatchers.IO) {
        try {
            val result = connection.executeShellCommand(
                "dumpsys window | grep mCurrentFocus"
            )
            if (result.isSuccess && result.output.isNotEmpty()) {
                val regex = Regex("([\\w.]+)/")
                val match = regex.find(result.output)
                return@withContext match?.groupValues?.get(1)
            }
            null
        } catch (e: Exception) {
            Logger.e("Failed to get current package", e, TAG)
            null
        }
    }

    // ==================== 输入操作 ====================

    /**
     * 执行点击操作
     * @param x X坐标
     * @param y Y坐标
     */
    suspend fun tap(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = connection.executeShellCommand("input tap $x $y")
            Logger.d("Tap at ($x, $y): ${result.isSuccess}", TAG)
            result.isSuccess
        } catch (e: Exception) {
            Logger.e("Tap failed", e, TAG)
            false
        }
    }

    /**
     * 执行滑动操作
     * @param startX 起始X
     * @param startY 起始Y
     * @param endX 结束X
     * @param endY 结束Y
     * @param duration 持续时间（毫秒）
     */
    suspend fun swipe(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        duration: Int = 300
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = connection.executeShellCommand(
                "input swipe $startX $startY $endX $endY $duration"
            )
            Logger.d("Swipe from ($startX,$startY) to ($endX,$endY): ${result.isSuccess}", TAG)
            result.isSuccess
        } catch (e: Exception) {
            Logger.e("Swipe failed", e, TAG)
            false
        }
    }

    /**
     * 执行长按操作
     */
    suspend fun longPress(x: Int, y: Int, duration: Int = 500): Boolean = withContext(Dispatchers.IO) {
        // 长按通过滑动到相同位置实现
        swipe(x, y, x, y, duration)
    }

    /**
     * 输入文本
     * @param text 要输入的文本
     */
    suspend fun inputText(text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 对特殊字符进行转义
            val escapedText = text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("'", "\\'")
                .replace(" ", "%s")
                .replace("&", "\\&")
                .replace("<", "\\<")
                .replace(">", "\\>")
                .replace("|", "\\|")
                .replace(";", "\\;")
                .replace("(", "\\(")
                .replace(")", "\\)")
            
            val result = connection.executeShellCommand("input text \"$escapedText\"")
            Logger.d("Input text: ${result.isSuccess}", TAG)
            result.isSuccess
        } catch (e: Exception) {
            Logger.e("Input text failed", e, TAG)
            false
        }
    }

    /**
     * 发送按键事件
     * @param keyCode 按键码
     */
    suspend fun pressKey(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = connection.executeShellCommand("input keyevent $keyCode")
            Logger.d("Press key $keyCode: ${result.isSuccess}", TAG)
            result.isSuccess
        } catch (e: Exception) {
            Logger.e("Press key failed", e, TAG)
            false
        }
    }

    // 常用按键码
    object KeyCodes {
        const val BACK = 4
        const val HOME = 3
        const val RECENT_APPS = 187
        const val POWER = 26
        const val VOLUME_UP = 24
        const val VOLUME_DOWN = 25
        const val ENTER = 66
        const val DEL = 67
        const val TAB = 61
        const val SPACE = 62
        const val DPAD_UP = 19
        const val DPAD_DOWN = 20
        const val DPAD_LEFT = 21
        const val DPAD_RIGHT = 22
        const val DPAD_CENTER = 23
    }

    /**
     * 按返回键
     */
    suspend fun pressBack(): Boolean = pressKey(KeyCodes.BACK)

    /**
     * 按Home键
     */
    suspend fun pressHome(): Boolean = pressKey(KeyCodes.HOME)

    /**
     * 按最近任务键
     */
    suspend fun pressRecentApps(): Boolean = pressKey(KeyCodes.RECENT_APPS)

    /**
     * 按回车键
     */
    suspend fun pressEnter(): Boolean = pressKey(KeyCodes.ENTER)

    // ==================== 应用操作 ====================

    /**
     * 启动应用
     * @param packageName 包名
     * @param activityName Activity名称（可选）
     */
    suspend fun startApp(packageName: String, activityName: String? = null): Boolean = 
        withContext(Dispatchers.IO) {
            try {
                val command = if (activityName != null) {
                    "am start -n $packageName/$activityName"
                } else {
                    "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
                }
                
                val result = connection.executeShellCommand(command)
                Logger.d("Start app $packageName: ${result.isSuccess}", TAG)
                result.isSuccess
            } catch (e: Exception) {
                Logger.e("Start app failed", e, TAG)
                false
            }
        }

    /**
     * 强制停止应用
     */
    suspend fun forceStopApp(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = connection.executeShellCommand("am force-stop $packageName")
            Logger.d("Force stop $packageName: ${result.isSuccess}", TAG)
            result.isSuccess
        } catch (e: Exception) {
            Logger.e("Force stop app failed", e, TAG)
            false
        }
    }

    /**
     * 获取已安装应用列表
     */
    suspend fun getInstalledPackages(): List<String> = withContext(Dispatchers.IO) {
        try {
            val result = connection.executeShellCommand("pm list packages")
            if (result.isSuccess) {
                return@withContext result.output
                    .lines()
                    .filter { it.startsWith("package:") }
                    .map { it.removePrefix("package:") }
            }
            emptyList()
        } catch (e: Exception) {
            Logger.e("Failed to get installed packages", e, TAG)
            emptyList()
        }
    }

    // ==================== 系统信息 ====================

    /**
     * 获取屏幕分辨率
     */
    suspend fun getScreenSize(): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            val result = connection.executeShellCommand("wm size")
            if (result.isSuccess) {
                val regex = Regex("(\\d+)x(\\d+)")
                val match = regex.find(result.output)
                if (match != null) {
                    val width = match.groupValues[1].toInt()
                    val height = match.groupValues[2].toInt()
                    return@withContext Pair(width, height)
                }
            }
            null
        } catch (e: Exception) {
            Logger.e("Failed to get screen size", e, TAG)
            null
        }
    }

    /**
     * 获取屏幕密度
     */
    suspend fun getScreenDensity(): Int? = withContext(Dispatchers.IO) {
        try {
            val result = connection.executeShellCommand("wm density")
            if (result.isSuccess) {
                val regex = Regex("(\\d+)")
                val match = regex.find(result.output)
                return@withContext match?.value?.toInt()
            }
            null
        } catch (e: Exception) {
            Logger.e("Failed to get screen density", e, TAG)
            null
        }
    }

    /**
     * 获取电池信息
     */
    suspend fun getBatteryInfo(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val result = connection.executeShellCommand("dumpsys battery")
            if (result.isSuccess) {
                val info = mutableMapOf<String, String>()
                result.output.lines().forEach { line ->
                    val parts = line.trim().split(":")
                    if (parts.size == 2) {
                        info[parts[0].trim()] = parts[1].trim()
                    }
                }
                return@withContext info
            }
            emptyMap()
        } catch (e: Exception) {
            Logger.e("Failed to get battery info", e, TAG)
            emptyMap()
        }
    }
}

