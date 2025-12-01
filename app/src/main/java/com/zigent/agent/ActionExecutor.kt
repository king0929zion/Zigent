package com.zigent.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.zigent.accessibility.ZigentAccessibilityService
import com.zigent.adb.AdbCommands
import com.zigent.adb.AdbManager
import com.zigent.agent.models.ActionType
import com.zigent.agent.models.AgentAction
import com.zigent.agent.models.ScrollDirection
import com.zigent.ai.AiConfig
import com.zigent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 操作执行结果
 */
data class ExecutionResult(
    val success: Boolean,
    val message: String = "",
    val errorMessage: String? = null,
    val needUserInput: Boolean = false,
    val question: String? = null
)

/**
 * 操作执行器
 * 负责执行Agent决策的各种操作
 */
@Singleton
class ActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adbManager: AdbManager
) {
    companion object {
        private const val TAG = "ActionExecutor"
        
        // 屏幕默认尺寸（备用）
        private const val DEFAULT_SCREEN_WIDTH = 1080
        private const val DEFAULT_SCREEN_HEIGHT = 2400
    }
    
    // 缓存屏幕尺寸
    private var screenWidth = DEFAULT_SCREEN_WIDTH
    private var screenHeight = DEFAULT_SCREEN_HEIGHT
    
    init {
        // 初始化时获取屏幕尺寸
        updateScreenSize()
    }
    
    private fun updateScreenSize() {
        val displayMetrics = context.resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        Logger.d("Screen size: ${screenWidth}x${screenHeight}", TAG)
    }

    /**
     * 执行操作
     */
    suspend fun execute(action: AgentAction): ExecutionResult = withContext(Dispatchers.IO) {
        Logger.i("Executing action: ${action.type} - ${action.description}", TAG)
        
        try {
            val result = when (action.type) {
                // 基础触摸操作
                ActionType.TAP -> executeTap(action)
                ActionType.DOUBLE_TAP -> executeDoubleTap(action)
                ActionType.LONG_PRESS -> executeLongPress(action)
                ActionType.SWIPE -> executeSwipe(action)
                
                // 方向滑动
                ActionType.SWIPE_UP -> executeDirectionalSwipe(ScrollDirection.UP, action)
                ActionType.SWIPE_DOWN -> executeDirectionalSwipe(ScrollDirection.DOWN, action)
                ActionType.SWIPE_LEFT -> executeDirectionalSwipe(ScrollDirection.LEFT, action)
                ActionType.SWIPE_RIGHT -> executeDirectionalSwipe(ScrollDirection.RIGHT, action)
                
                // 滚动操作
                ActionType.SCROLL -> executeScroll(action)
                ActionType.SCROLL_TO_TOP -> executeScrollToTop(action)
                ActionType.SCROLL_TO_BOTTOM -> executeScrollToBottom(action)
                
                // 输入操作
                ActionType.INPUT_TEXT -> executeInputText(action)
                ActionType.CLEAR_TEXT -> executeClearText(action)
                
                // 按键操作
                ActionType.PRESS_KEY -> executePressKey(action)
                ActionType.PRESS_BACK -> executePressBack()
                ActionType.PRESS_HOME -> executePressHome()
                ActionType.PRESS_RECENT -> executePressRecent()
                
                // 应用操作
                ActionType.OPEN_APP -> executeOpenApp(action)
                ActionType.CLOSE_APP -> executeCloseApp(action)
                ActionType.OPEN_URL -> executeOpenUrl(action)
                ActionType.OPEN_SETTINGS -> executeOpenSettings(action)
                
                // 系统操作
                ActionType.TAKE_SCREENSHOT -> executeTakeScreenshot(action)
                ActionType.COPY_TEXT -> executeCopyText(action)
                ActionType.PASTE_TEXT -> executePasteText(action)
                
                // 通知操作
                ActionType.OPEN_NOTIFICATION -> executeOpenNotification()
                ActionType.CLEAR_NOTIFICATION -> executeClearNotification()
                
                // 等待操作
                ActionType.WAIT -> executeWait(action)
                ActionType.WAIT_FOR_ELEMENT -> executeWaitForElement(action)
                
                // 任务状态
                ActionType.FINISHED -> ExecutionResult(true, action.resultMessage ?: "任务完成")
                ActionType.FAILED -> ExecutionResult(false, errorMessage = action.resultMessage ?: "任务失败")
                ActionType.ASK_USER -> ExecutionResult(
                    success = true, 
                    needUserInput = true, 
                    question = action.question ?: "需要您的输入"
                )
            }
            
            // 操作后等待页面响应（某些操作除外）
            if (action.type !in listOf(
                ActionType.WAIT, ActionType.WAIT_FOR_ELEMENT,
                ActionType.FINISHED, ActionType.FAILED, ActionType.ASK_USER
            )) {
                delay(AiConfig.ACTION_WAIT_TIME)
            }
            
            Logger.i("Action result: ${result.success} - ${result.message}", TAG)
            result
        } catch (e: Exception) {
            Logger.e("Action execution failed: ${action.type}", e, TAG)
            ExecutionResult(false, errorMessage = e.message ?: "执行失败")
        }
    }

    // ==================== 基础触摸操作 ====================

    /**
     * 执行点击
     */
    private suspend fun executeTap(action: AgentAction): ExecutionResult {
        val x = action.x ?: return ExecutionResult(false, errorMessage = "缺少坐标X")
        val y = action.y ?: return ExecutionResult(false, errorMessage = "缺少坐标Y")
        
        // 优先使用无障碍服务
        ZigentAccessibilityService.instance?.let { service ->
            val success = suspendCancellableCoroutine { cont ->
                service.performClick(x.toFloat(), y.toFloat()) { result ->
                    cont.resume(result)
                }
            }
            if (success) {
                return ExecutionResult(true, "点击 ($x, $y)")
            }
        }
        
        // 备选：使用ADB
        val success = adbManager.tap(x, y)
        return if (success) {
            ExecutionResult(true, "点击 ($x, $y)")
        } else {
            ExecutionResult(false, errorMessage = "点击失败")
        }
    }

    /**
     * 执行双击
     */
    private suspend fun executeDoubleTap(action: AgentAction): ExecutionResult {
        val x = action.x ?: return ExecutionResult(false, errorMessage = "缺少坐标X")
        val y = action.y ?: return ExecutionResult(false, errorMessage = "缺少坐标Y")
        
        // 执行两次点击，间隔100ms
        val result1 = executeTap(action)
        if (!result1.success) return result1
        
        delay(100)
        
        val result2 = executeTap(action)
        return if (result2.success) {
            ExecutionResult(true, "双击 ($x, $y)")
        } else {
            result2
        }
    }

    /**
     * 执行长按
     */
    private suspend fun executeLongPress(action: AgentAction): ExecutionResult {
        val x = action.x ?: return ExecutionResult(false, errorMessage = "缺少坐标X")
        val y = action.y ?: return ExecutionResult(false, errorMessage = "缺少坐标Y")
        val duration = action.duration ?: 800
        
        // 优先使用无障碍服务
        ZigentAccessibilityService.instance?.let { service ->
            val success = suspendCancellableCoroutine { cont ->
                service.performLongClick(x.toFloat(), y.toFloat(), duration.toLong()) { result ->
                    cont.resume(result)
                }
            }
            if (success) {
                return ExecutionResult(true, "长按 ($x, $y) ${duration}ms")
            }
        }
        
        // 备选：使用ADB
        val success = adbManager.longPress(x, y, duration)
        return if (success) {
            ExecutionResult(true, "长按 ($x, $y) ${duration}ms")
        } else {
            ExecutionResult(false, errorMessage = "长按失败")
        }
    }

    /**
     * 执行自定义滑动
     */
    private suspend fun executeSwipe(action: AgentAction): ExecutionResult {
        val startX = action.startX ?: return ExecutionResult(false, errorMessage = "缺少起始坐标")
        val startY = action.startY ?: return ExecutionResult(false, errorMessage = "缺少起始坐标")
        val endX = action.endX ?: return ExecutionResult(false, errorMessage = "缺少结束坐标")
        val endY = action.endY ?: return ExecutionResult(false, errorMessage = "缺少结束坐标")
        val duration = action.duration ?: 300
        
        return performSwipe(startX, startY, endX, endY, duration, "滑动 ($startX,$startY)->($endX,$endY)")
    }

    // ==================== 方向滑动 ====================

    /**
     * 执行方向滑动
     */
    private suspend fun executeDirectionalSwipe(
        direction: ScrollDirection, 
        action: AgentAction
    ): ExecutionResult {
        val distance = action.swipeDistance ?: 50 // 默认滑动屏幕50%
        val duration = action.duration ?: 300
        
        val centerX = screenWidth / 2
        val centerY = screenHeight / 2
        val swipeDistancePx = (screenHeight * distance / 100)
        
        val (startX, startY, endX, endY) = when (direction) {
            ScrollDirection.UP -> listOf(
                centerX, centerY + swipeDistancePx / 2,
                centerX, centerY - swipeDistancePx / 2
            )
            ScrollDirection.DOWN -> listOf(
                centerX, centerY - swipeDistancePx / 2,
                centerX, centerY + swipeDistancePx / 2
            )
            ScrollDirection.LEFT -> listOf(
                centerX + swipeDistancePx / 2, centerY,
                centerX - swipeDistancePx / 2, centerY
            )
            ScrollDirection.RIGHT -> listOf(
                centerX - swipeDistancePx / 2, centerY,
                centerX + swipeDistancePx / 2, centerY
            )
        }
        
        val directionName = when (direction) {
            ScrollDirection.UP -> "上滑"
            ScrollDirection.DOWN -> "下滑"
            ScrollDirection.LEFT -> "左滑"
            ScrollDirection.RIGHT -> "右滑"
        }
        
        return performSwipe(startX, startY, endX, endY, duration, directionName)
    }

    // ==================== 滚动操作 ====================

    /**
     * 执行滚动
     */
    private suspend fun executeScroll(action: AgentAction): ExecutionResult {
        val direction = action.scrollDirection ?: ScrollDirection.DOWN
        val count = action.scrollCount ?: 1
        
        repeat(count) {
            val result = executeDirectionalSwipe(direction, action)
            if (!result.success) return result
            if (it < count - 1) delay(300) // 连续滚动间隔
        }
        
        val directionName = when (direction) {
            ScrollDirection.UP -> "向上"
            ScrollDirection.DOWN -> "向下"
            ScrollDirection.LEFT -> "向左"
            ScrollDirection.RIGHT -> "向右"
        }
        
        return ExecutionResult(true, "${directionName}滚动 ${count}次")
    }

    /**
     * 滚动到顶部
     */
    private suspend fun executeScrollToTop(action: AgentAction): ExecutionResult {
        val maxScrolls = 10
        repeat(maxScrolls) {
            val scrollAction = action.copy(scrollDirection = ScrollDirection.DOWN, swipeDistance = 80)
            executeDirectionalSwipe(ScrollDirection.DOWN, scrollAction)
            delay(200)
        }
        return ExecutionResult(true, "滚动到顶部")
    }

    /**
     * 滚动到底部
     */
    private suspend fun executeScrollToBottom(action: AgentAction): ExecutionResult {
        val maxScrolls = 10
        repeat(maxScrolls) {
            val scrollAction = action.copy(scrollDirection = ScrollDirection.UP, swipeDistance = 80)
            executeDirectionalSwipe(ScrollDirection.UP, scrollAction)
            delay(200)
        }
        return ExecutionResult(true, "滚动到底部")
    }

    // ==================== 输入操作 ====================

    /**
     * 执行输入文本
     */
    private suspend fun executeInputText(action: AgentAction): ExecutionResult {
        val text = action.text ?: return ExecutionResult(false, errorMessage = "缺少输入文本")
        
        // 如果提供了坐标，先点击
        if (action.x != null && action.y != null) {
            val tapResult = executeTap(action)
            if (!tapResult.success) return tapResult
            delay(300)
        }
        
        val success = adbManager.inputText(text)
        return if (success) {
            ExecutionResult(true, "输入: $text")
        } else {
            ExecutionResult(false, errorMessage = "输入失败")
        }
    }

    /**
     * 清空输入框
     */
    private suspend fun executeClearText(action: AgentAction): ExecutionResult {
        // 先全选，再删除
        adbManager.pressKey(AdbCommands.KeyCodes.CTRL_A) // 全选
        delay(100)
        adbManager.pressKey(AdbCommands.KeyCodes.DEL) // 删除
        return ExecutionResult(true, "清空输入框")
    }

    // ==================== 按键操作 ====================

    /**
     * 执行按键
     */
    private suspend fun executePressKey(action: AgentAction): ExecutionResult {
        val keyCode = action.keyCode ?: action.keyName?.let { parseKeyName(it) }
            ?: return ExecutionResult(false, errorMessage = "缺少按键码")
        
        // 优先使用无障碍服务的全局操作
        ZigentAccessibilityService.instance?.let { service ->
            val success = when (keyCode) {
                AdbCommands.KeyCodes.BACK -> service.performBack()
                AdbCommands.KeyCodes.HOME -> service.performHome()
                AdbCommands.KeyCodes.RECENT_APPS -> service.performRecents()
                else -> false
            }
            if (success) {
                return ExecutionResult(true, "按键: $keyCode")
            }
        }
        
        // 备选：使用ADB
        val success = adbManager.pressKey(keyCode)
        return if (success) {
            ExecutionResult(true, "按键: $keyCode")
        } else {
            ExecutionResult(false, errorMessage = "按键失败")
        }
    }

    private suspend fun executePressBack(): ExecutionResult {
        ZigentAccessibilityService.instance?.let { service ->
            if (service.performBack()) {
                return ExecutionResult(true, "返回")
            }
        }
        val success = adbManager.pressKey(AdbCommands.KeyCodes.BACK)
        return if (success) ExecutionResult(true, "返回") 
               else ExecutionResult(false, errorMessage = "返回失败")
    }

    private suspend fun executePressHome(): ExecutionResult {
        ZigentAccessibilityService.instance?.let { service ->
            if (service.performHome()) {
                return ExecutionResult(true, "回到主页")
            }
        }
        val success = adbManager.pressKey(AdbCommands.KeyCodes.HOME)
        return if (success) ExecutionResult(true, "回到主页") 
               else ExecutionResult(false, errorMessage = "回到主页失败")
    }

    private suspend fun executePressRecent(): ExecutionResult {
        ZigentAccessibilityService.instance?.let { service ->
            if (service.performRecents()) {
                return ExecutionResult(true, "打开最近任务")
            }
        }
        val success = adbManager.pressKey(AdbCommands.KeyCodes.RECENT_APPS)
        return if (success) ExecutionResult(true, "打开最近任务") 
               else ExecutionResult(false, errorMessage = "打开最近任务失败")
    }

    /**
     * 解析按键名称
     */
    private fun parseKeyName(keyName: String): Int? {
        return when (keyName.uppercase()) {
            "BACK", "返回" -> AdbCommands.KeyCodes.BACK
            "HOME", "主页", "首页" -> AdbCommands.KeyCodes.HOME
            "RECENT", "RECENTS", "最近", "多任务" -> AdbCommands.KeyCodes.RECENT_APPS
            "ENTER", "确认", "回车" -> AdbCommands.KeyCodes.ENTER
            "DEL", "DELETE", "删除", "退格" -> AdbCommands.KeyCodes.DEL
            "VOLUME_UP", "音量+", "增大音量" -> 24
            "VOLUME_DOWN", "音量-", "减小音量" -> 25
            "POWER", "电源" -> 26
            "MENU", "菜单" -> 82
            "TAB" -> 61
            "SPACE", "空格" -> 62
            "ESCAPE", "ESC" -> 111
            else -> keyName.toIntOrNull()
        }
    }

    // ==================== 应用操作 ====================

    /**
     * 执行打开应用
     */
    private suspend fun executeOpenApp(action: AgentAction): ExecutionResult {
        val packageName = action.packageName 
            ?: action.appName?.let { PromptBuilder.getPackageName(it) }
            ?: return ExecutionResult(false, errorMessage = "未知的应用")
        
        // 方法1：使用Intent启动（最可靠）
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                delay(1500) // 等待应用启动
                return ExecutionResult(true, "打开应用: ${action.appName ?: packageName}")
            }
        } catch (e: Exception) {
            Logger.e("Failed to launch app via Intent", e, TAG)
        }
        
        // 方法2：使用ADB
        val success = adbManager.startApp(packageName)
        return if (success) {
            delay(1500)
            ExecutionResult(true, "打开应用: ${action.appName ?: packageName}")
        } else {
            ExecutionResult(false, errorMessage = "打开应用失败，请确保已安装")
        }
    }

    /**
     * 关闭应用
     */
    private suspend fun executeCloseApp(action: AgentAction): ExecutionResult {
        val packageName = action.packageName 
            ?: action.appName?.let { PromptBuilder.getPackageName(it) }
            ?: return ExecutionResult(false, errorMessage = "未知的应用")
        
        val success = adbManager.forceStopApp(packageName)
        return if (success) {
            ExecutionResult(true, "关闭应用: ${action.appName ?: packageName}")
        } else {
            ExecutionResult(false, errorMessage = "关闭应用失败")
        }
    }

    /**
     * 打开URL
     */
    private suspend fun executeOpenUrl(action: AgentAction): ExecutionResult {
        val url = action.url ?: action.text 
            ?: return ExecutionResult(false, errorMessage = "缺少URL")
        
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            delay(1500)
            return ExecutionResult(true, "打开网址: $url")
        } catch (e: Exception) {
            Logger.e("Failed to open URL", e, TAG)
            return ExecutionResult(false, errorMessage = "打开网址失败: ${e.message}")
        }
    }

    /**
     * 打开系统设置
     */
    private suspend fun executeOpenSettings(action: AgentAction): ExecutionResult {
        try {
            val settingsIntent = when (action.text?.lowercase()) {
                "wifi", "网络" -> Intent(Settings.ACTION_WIFI_SETTINGS)
                "bluetooth", "蓝牙" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                "display", "显示" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                "sound", "声音" -> Intent(Settings.ACTION_SOUND_SETTINGS)
                "storage", "存储" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                "battery", "电池" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                "apps", "应用" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
                "location", "位置" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                "accessibility", "无障碍" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                else -> Intent(Settings.ACTION_SETTINGS)
            }
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            delay(1000)
            return ExecutionResult(true, "打开设置: ${action.text ?: "主页"}")
        } catch (e: Exception) {
            Logger.e("Failed to open settings", e, TAG)
            return ExecutionResult(false, errorMessage = "打开设置失败")
        }
    }

    // ==================== 系统操作 ====================

    private suspend fun executeTakeScreenshot(action: AgentAction): ExecutionResult {
        // 通过无障碍服务截图
        ZigentAccessibilityService.instance?.let { service ->
            // 截图功能需要额外实现
            return ExecutionResult(true, "截图功能")
        }
        return ExecutionResult(false, errorMessage = "截图功能暂不可用")
    }

    private suspend fun executeCopyText(action: AgentAction): ExecutionResult {
        // 使用全选+复制的方式
        adbManager.pressKey(AdbCommands.KeyCodes.CTRL_A)
        delay(100)
        adbManager.pressKey(AdbCommands.KeyCodes.CTRL_C)
        return ExecutionResult(true, "复制文本")
    }

    private suspend fun executePasteText(action: AgentAction): ExecutionResult {
        adbManager.pressKey(AdbCommands.KeyCodes.CTRL_V)
        return ExecutionResult(true, "粘贴文本")
    }

    // ==================== 通知操作 ====================

    private suspend fun executeOpenNotification(): ExecutionResult {
        ZigentAccessibilityService.instance?.let { service ->
            if (service.performOpenNotifications()) {
                return ExecutionResult(true, "打开通知栏")
            }
        }
        
        // 从屏幕顶部下滑
        val result = performSwipe(screenWidth / 2, 0, screenWidth / 2, screenHeight / 2, 300, "下拉通知栏")
        return if (result.success) {
            ExecutionResult(true, "打开通知栏")
        } else {
            result
        }
    }

    private suspend fun executeClearNotification(): ExecutionResult {
        // 先打开通知栏
        executeOpenNotification()
        delay(500)
        
        // 点击清除所有（这个位置需要根据不同手机调整）
        // 一般在通知栏底部
        val clearY = screenHeight / 2 + 100
        executeTap(AgentAction(ActionType.TAP, "清除通知", x = screenWidth / 2, y = clearY))
        
        return ExecutionResult(true, "清除通知")
    }

    // ==================== 等待操作 ====================

    private suspend fun executeWait(action: AgentAction): ExecutionResult {
        val waitTime = action.waitTime ?: 1000L
        delay(waitTime)
        return ExecutionResult(true, "等待 ${waitTime}ms")
    }

    private suspend fun executeWaitForElement(action: AgentAction): ExecutionResult {
        val targetText = action.waitForText ?: return ExecutionResult(false, errorMessage = "缺少等待目标")
        val timeout = action.timeout ?: 10000L
        
        val found = withTimeoutOrNull(timeout) {
            while (true) {
                // 检查屏幕上是否有目标文字
                // 这里需要屏幕分析支持
                delay(500)
                // TODO: 实现元素检测
            }
            true
        } ?: false
        
        return if (found) {
            ExecutionResult(true, "找到元素: $targetText")
        } else {
            ExecutionResult(false, errorMessage = "等待超时: $targetText")
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 执行滑动操作
     */
    private suspend fun performSwipe(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        duration: Int,
        description: String
    ): ExecutionResult {
        // 优先使用无障碍服务
        ZigentAccessibilityService.instance?.let { service ->
            val success = suspendCancellableCoroutine { cont ->
                service.performSwipe(
                    startX.toFloat(), startY.toFloat(),
                    endX.toFloat(), endY.toFloat(),
                    duration.toLong()
                ) { result ->
                    cont.resume(result)
                }
            }
            if (success) {
                return ExecutionResult(true, description)
            }
        }
        
        // 备选：使用ADB
        val success = adbManager.swipe(startX, startY, endX, endY, duration)
        return if (success) {
            ExecutionResult(true, description)
        } else {
            ExecutionResult(false, errorMessage = "${description}失败")
        }
    }
}
