package com.zigent.agent

import android.content.Context
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 操作执行结果
 */
data class ExecutionResult(
    val success: Boolean,
    val message: String = "",
    val errorMessage: String? = null
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
    }

    /**
     * 执行操作
     */
    suspend fun execute(action: AgentAction): ExecutionResult = withContext(Dispatchers.IO) {
        Logger.d("Executing action: ${action.type} - ${action.description}", TAG)
        
        try {
            val result = when (action.type) {
                ActionType.TAP -> executeTap(action)
                ActionType.LONG_PRESS -> executeLongPress(action)
                ActionType.SWIPE -> executeSwipe(action)
                ActionType.INPUT_TEXT -> executeInputText(action)
                ActionType.PRESS_KEY -> executePressKey(action)
                ActionType.OPEN_APP -> executeOpenApp(action)
                ActionType.WAIT -> executeWait(action)
                ActionType.SCROLL -> executeScroll(action)
                ActionType.FINISHED -> ExecutionResult(true, action.resultMessage ?: "任务完成")
                ActionType.FAILED -> ExecutionResult(false, errorMessage = action.resultMessage ?: "任务失败")
            }
            
            // 操作后等待页面响应
            if (action.type !in listOf(ActionType.WAIT, ActionType.FINISHED, ActionType.FAILED)) {
                delay(AiConfig.ACTION_WAIT_TIME)
            }
            
            result
        } catch (e: Exception) {
            Logger.e("Action execution failed: ${action.type}", e, TAG)
            ExecutionResult(false, errorMessage = e.message ?: "执行失败")
        }
    }

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
     * 执行长按
     */
    private suspend fun executeLongPress(action: AgentAction): ExecutionResult {
        val x = action.x ?: return ExecutionResult(false, errorMessage = "缺少坐标X")
        val y = action.y ?: return ExecutionResult(false, errorMessage = "缺少坐标Y")
        val duration = action.duration ?: 500
        
        // 优先使用无障碍服务
        ZigentAccessibilityService.instance?.let { service ->
            val success = suspendCancellableCoroutine { cont ->
                service.performLongClick(x.toFloat(), y.toFloat(), duration.toLong()) { result ->
                    cont.resume(result)
                }
            }
            if (success) {
                return ExecutionResult(true, "长按 ($x, $y)")
            }
        }
        
        // 备选：使用ADB
        val success = adbManager.longPress(x, y, duration)
        return if (success) {
            ExecutionResult(true, "长按 ($x, $y)")
        } else {
            ExecutionResult(false, errorMessage = "长按失败")
        }
    }

    /**
     * 执行滑动
     */
    private suspend fun executeSwipe(action: AgentAction): ExecutionResult {
        val startX = action.startX ?: return ExecutionResult(false, errorMessage = "缺少起始坐标")
        val startY = action.startY ?: return ExecutionResult(false, errorMessage = "缺少起始坐标")
        val endX = action.endX ?: return ExecutionResult(false, errorMessage = "缺少结束坐标")
        val endY = action.endY ?: return ExecutionResult(false, errorMessage = "缺少结束坐标")
        val duration = action.duration ?: 300
        
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
                return ExecutionResult(true, "滑动完成")
            }
        }
        
        // 备选：使用ADB
        val success = adbManager.swipe(startX, startY, endX, endY, duration)
        return if (success) {
            ExecutionResult(true, "滑动完成")
        } else {
            ExecutionResult(false, errorMessage = "滑动失败")
        }
    }

    /**
     * 执行输入文本
     */
    private suspend fun executeInputText(action: AgentAction): ExecutionResult {
        val text = action.text ?: return ExecutionResult(false, errorMessage = "缺少输入文本")
        
        val success = adbManager.inputText(text)
        return if (success) {
            ExecutionResult(true, "输入: $text")
        } else {
            ExecutionResult(false, errorMessage = "输入失败")
        }
    }

    /**
     * 执行按键
     */
    private suspend fun executePressKey(action: AgentAction): ExecutionResult {
        val keyCode = action.keyCode ?: action.text?.let { parseKeyName(it) }
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

    /**
     * 解析按键名称
     */
    private fun parseKeyName(keyName: String): Int? {
        return when (keyName.uppercase()) {
            "BACK", "返回" -> AdbCommands.KeyCodes.BACK
            "HOME", "主页" -> AdbCommands.KeyCodes.HOME
            "RECENT", "RECENTS", "最近", "多任务" -> AdbCommands.KeyCodes.RECENT_APPS
            "ENTER", "确认", "回车" -> AdbCommands.KeyCodes.ENTER
            "DEL", "DELETE", "删除" -> AdbCommands.KeyCodes.DEL
            else -> keyName.toIntOrNull()
        }
    }

    /**
     * 执行打开应用
     */
    private suspend fun executeOpenApp(action: AgentAction): ExecutionResult {
        val packageName = action.packageName 
            ?: PromptBuilder.getPackageName(action.text ?: "")
            ?: return ExecutionResult(false, errorMessage = "未知的应用")
        
        val success = adbManager.startApp(packageName)
        return if (success) {
            delay(1500) // 等待应用启动
            ExecutionResult(true, "打开应用: $packageName")
        } else {
            ExecutionResult(false, errorMessage = "打开应用失败")
        }
    }

    /**
     * 执行等待
     */
    private suspend fun executeWait(action: AgentAction): ExecutionResult {
        val waitTime = action.waitTime ?: 1000L
        delay(waitTime)
        return ExecutionResult(true, "等待 ${waitTime}ms")
    }

    /**
     * 执行滚动
     */
    private suspend fun executeScroll(action: AgentAction): ExecutionResult {
        val direction = action.scrollDirection ?: ScrollDirection.DOWN
        
        // 获取屏幕尺寸
        val screenSize = adbManager.getScreenSize() ?: Pair(1080, 1920)
        val (width, height) = screenSize
        
        val centerX = width / 2
        val centerY = height / 2
        val scrollDistance = height / 3
        
        val (startX, startY, endX, endY) = when (direction) {
            ScrollDirection.UP -> listOf(centerX, centerY - scrollDistance / 2, centerX, centerY + scrollDistance / 2)
            ScrollDirection.DOWN -> listOf(centerX, centerY + scrollDistance / 2, centerX, centerY - scrollDistance / 2)
            ScrollDirection.LEFT -> listOf(centerX + scrollDistance / 2, centerY, centerX - scrollDistance / 2, centerY)
            ScrollDirection.RIGHT -> listOf(centerX - scrollDistance / 2, centerY, centerX + scrollDistance / 2, centerY)
        }
        
        val success = adbManager.swipe(startX, startY, endX, endY, 300)
        return if (success) {
            ExecutionResult(true, "滚动: $direction")
        } else {
            ExecutionResult(false, errorMessage = "滚动失败")
        }
    }
}

