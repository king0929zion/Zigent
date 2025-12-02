package com.zigent.agent

import android.content.Context
import com.zigent.agent.models.*
import com.zigent.utils.AppUtils
import com.zigent.utils.Logger

/**
 * 操作验证结果
 */
data class VerificationResult(
    val success: Boolean,           // 验证是否通过
    val message: String,            // 验证消息
    val confidence: Float = 1.0f,   // 置信度 (0-1)
    val suggestion: String? = null  // 如果失败,给出建议
)

/**
 * 操作验证器
 * 验证执行的操作是否达到预期效果
 */
class ActionVerifier(
    private val context: Context? = null
) {
    
    companion object {
        private const val TAG = "ActionVerifier"
        
        // 验证阈值
        private const val MIN_STATE_CHANGE_THRESHOLD = 0.1f  // 最小状态变化阈值
    }
    
    /**
     * 验证操作执行结果
     * @param action 执行的操作
     * @param beforeState 执行前的屏幕状态
     * @param afterState 执行后的屏幕状态
     * @return 验证结果
     */
    fun verify(
        action: AgentAction,
        beforeState: ScreenState,
        afterState: ScreenState
    ): VerificationResult {
        Logger.d("Verifying action: ${action.type} - ${action.description}", TAG)
        
        return when (action.type) {
            // 应用操作验证
            ActionType.OPEN_APP -> verifyOpenApp(action, afterState)
            ActionType.CLOSE_APP -> verifyCloseApp(action, beforeState, afterState)
            
            // 输入验证
            ActionType.INPUT_TEXT -> verifyInputText(action, afterState)
            ActionType.CLEAR_TEXT -> verifyClearText(beforeState, afterState)
            
            // 点击验证
            ActionType.TAP -> verifyTap(action, beforeState, afterState)
            ActionType.DOUBLE_TAP -> verifyTap(action, beforeState, afterState)
            ActionType.LONG_PRESS -> verifyTap(action, beforeState, afterState)
            
            // 滑动验证
            ActionType.SWIPE_UP, ActionType.SWIPE_DOWN,
            ActionType.SWIPE_LEFT, ActionType.SWIPE_RIGHT,
            ActionType.SWIPE, ActionType.SCROLL -> verifyScroll(beforeState, afterState)
            
            // 按键验证
            ActionType.PRESS_BACK -> verifyPressBack(beforeState, afterState)
            ActionType.PRESS_HOME -> verifyPressHome(afterState)
            ActionType.PRESS_ENTER -> verifyStateChanged(beforeState, afterState)
            
            // 等待操作 - 总是成功
            ActionType.WAIT -> VerificationResult(true, "等待完成")
            
            // 任务状态 - 不需要验证
            ActionType.FINISHED, ActionType.FAILED, ActionType.ASK_USER ->
                VerificationResult(true, "状态操作")
            
            // 其他操作 - 检查状态是否有变化
            else -> verifyStateChanged(beforeState, afterState)
        }
    }
    
    /**
     * 验证打开应用
     */
    private fun verifyOpenApp(action: AgentAction, afterState: ScreenState): VerificationResult {
        val appName = action.appName ?: return VerificationResult(false, "未指定应用名")
        
        // 获取预期包名
        val expectedPackage = action.packageName ?: AppUtils.getPackageName(appName, context)
        
        if (expectedPackage == null) {
            Logger.w("Unable to find package for app: $appName", TAG)
            return VerificationResult(
                success = false,
                message = "无法找到应用包名: $appName",
                suggestion = "请检查应用是否已安装或使用完整包名"
            )
        }
        
        // 检查当前包名
        val currentPackage = afterState.packageName.lowercase()
        val expectedLower = expectedPackage.lowercase()
        
        // 精确匹配
        if (currentPackage == expectedLower) {
            return VerificationResult(true, "应用已打开: $appName", 1.0f)
        }
        
        // 模糊匹配（包名包含应用关键词）
        val appKeywords = listOf(
            expectedLower,
            appName.lowercase(),
            appName.replace("应用", "").lowercase(),
            appName.replace("app", "").lowercase()
        )
        
        for (keyword in appKeywords) {
            if (keyword.isNotEmpty() && (currentPackage.contains(keyword) || keyword.contains(currentPackage))) {
                return VerificationResult(true, "应用已打开: $appName", 0.9f)
            }
        }
        
        // 检查是否是启动器/选择器（可能是选择打开方式）
        if (currentPackage.contains("launcher") || currentPackage.contains("resolver")) {
            return VerificationResult(
                success = false,
                message = "打开了应用选择器",
                confidence = 0.5f,
                suggestion = "可能需要选择一个应用"
            )
        }
        
        Logger.w("App not opened. Expected: $expectedPackage, Current: ${afterState.packageName}", TAG)
        return VerificationResult(
            success = false,
            message = "应用未打开，当前在: ${AppUtils.getAppName(afterState.packageName)} (${afterState.packageName})",
            suggestion = "尝试重新打开或检查应用是否已安装"
        )
    }
    
    /**
     * 验证关闭应用
     */
    private fun verifyCloseApp(
        action: AgentAction,
        beforeState: ScreenState,
        afterState: ScreenState
    ): VerificationResult {
        val appName = action.appName ?: return VerificationResult(false, "未指定应用名")
        
        // 检查是否离开了目标应用
        val beforePackage = beforeState.packageName.lowercase()
        val afterPackage = afterState.packageName.lowercase()
        
        if (beforePackage != afterPackage) {
            return VerificationResult(true, "已关闭: $appName")
        }
        
        // 检查是否回到桌面
        if (afterPackage.contains("launcher") || afterPackage.contains("home")) {
            return VerificationResult(true, "已关闭并回到桌面")
        }
        
        return VerificationResult(
            success = false,
            message = "应用可能未关闭",
            suggestion = "尝试使用返回键或强制关闭"
        )
    }
    
    /**
     * 验证输入文字
     */
    private fun verifyInputText(action: AgentAction, afterState: ScreenState): VerificationResult {
        val expectedText = action.text ?: return VerificationResult(false, "未指定输入文字")
        
        // 检查输入框中是否有预期的文字
        val editableElements = afterState.uiElements.filter { it.isEditable }
        
        for (element in editableElements) {
            if (element.text.contains(expectedText)) {
                return VerificationResult(true, "文字已输入: ${expectedText.take(20)}...", 1.0f)
            }
        }
        
        // 检查所有元素（有些输入框可能不标记为 editable）
        val allElements = afterState.uiElements
        for (element in allElements) {
            if (element.text.contains(expectedText)) {
                return VerificationResult(true, "文字已输入", 0.8f)
            }
        }
        
        return VerificationResult(
            success = false,
            message = "未检测到输入的文字",
            confidence = 0.6f,  // 可能是检测问题
            suggestion = "输入框可能未聚焦，尝试先点击输入框"
        )
    }
    
    /**
     * 验证清空文字
     */
    private fun verifyClearText(beforeState: ScreenState, afterState: ScreenState): VerificationResult {
        // 检查输入框文字是否变少
        val beforeEditable = beforeState.uiElements.filter { it.isEditable }
        val afterEditable = afterState.uiElements.filter { it.isEditable }
        
        val beforeTextLength = beforeEditable.sumOf { it.text.length }
        val afterTextLength = afterEditable.sumOf { it.text.length }
        
        if (afterTextLength < beforeTextLength) {
            return VerificationResult(true, "文字已清空")
        }
        
        // 如果都是空的，也算成功
        if (afterTextLength == 0) {
            return VerificationResult(true, "输入框已为空")
        }
        
        return VerificationResult(
            success = false,
            message = "文字可能未清空",
            suggestion = "尝试全选后删除"
        )
    }
    
    /**
     * 验证点击操作
     */
    private fun verifyTap(
        action: AgentAction,
        beforeState: ScreenState,
        afterState: ScreenState
    ): VerificationResult {
        // 检查页面是否有变化
        val hasChange = hasStateChanged(beforeState, afterState)
        
        if (hasChange) {
            // 检查是否是预期的变化
            val description = action.description.lowercase()
            
            // 如果点击的是输入框，检查是否出现键盘（元素位置变化）
            if (description.contains("输入") || description.contains("搜索")) {
                val elementsShifted = checkElementsShifted(beforeState, afterState)
                if (elementsShifted) {
                    return VerificationResult(true, "点击成功，键盘可能已弹出", 0.9f)
                }
            }
            
            return VerificationResult(true, "点击成功，页面已响应")
        }
        
        // 没有明显变化，可能是：
        // 1. 点击了不可交互的区域
        // 2. 页面正在加载
        // 3. 点击坐标不准确
        return VerificationResult(
            success = true,  // 点击本身成功了
            message = "点击已执行，但页面无明显变化",
            confidence = 0.7f,
            suggestion = "可能需要等待或检查坐标"
        )
    }
    
    /**
     * 验证滚动/滑动
     */
    private fun verifyScroll(beforeState: ScreenState, afterState: ScreenState): VerificationResult {
        // 检查元素是否有变化
        val beforeTexts = beforeState.uiElements.map { it.text }.toSet()
        val afterTexts = afterState.uiElements.map { it.text }.toSet()
        
        val newElements = afterTexts - beforeTexts
        val removedElements = beforeTexts - afterTexts
        
        // 如果有新元素出现或旧元素消失，说明滚动有效
        if (newElements.isNotEmpty() || removedElements.isNotEmpty()) {
            return VerificationResult(
                success = true,
                message = "滚动成功，出现${newElements.size}个新元素",
                confidence = 1.0f
            )
        }
        
        // 检查元素位置是否变化
        if (checkElementsShifted(beforeState, afterState)) {
            return VerificationResult(true, "滚动成功，元素位置已变化", 0.9f)
        }
        
        return VerificationResult(
            success = true,
            message = "滚动已执行，但内容可能已到底/顶",
            confidence = 0.6f
        )
    }
    
    /**
     * 验证返回键
     */
    private fun verifyPressBack(beforeState: ScreenState, afterState: ScreenState): VerificationResult {
        // 检查是否切换了页面
        if (beforeState.activityName != afterState.activityName) {
            return VerificationResult(true, "返回成功，页面已切换")
        }
        
        // 检查是否切换了应用
        if (beforeState.packageName != afterState.packageName) {
            return VerificationResult(true, "返回成功，已离开应用")
        }
        
        // 检查是否有弹窗关闭
        if (hasStateChanged(beforeState, afterState)) {
            return VerificationResult(true, "返回成功，页面有变化", 0.8f)
        }
        
        return VerificationResult(
            success = true,
            message = "返回键已按下",
            confidence = 0.7f
        )
    }
    
    /**
     * 验证 Home 键
     */
    private fun verifyPressHome(afterState: ScreenState): VerificationResult {
        val packageName = afterState.packageName.lowercase()
        
        if (packageName.contains("launcher") || 
            packageName.contains("home") ||
            packageName.contains("lawnchair") ||
            packageName.contains("nova")) {
            return VerificationResult(true, "已回到桌面")
        }
        
        return VerificationResult(
            success = true,
            message = "Home键已按下",
            confidence = 0.8f
        )
    }
    
    /**
     * 通用状态变化验证
     */
    private fun verifyStateChanged(beforeState: ScreenState, afterState: ScreenState): VerificationResult {
        val hasChange = hasStateChanged(beforeState, afterState)
        
        return if (hasChange) {
            VerificationResult(true, "操作成功，状态已变化")
        } else {
            VerificationResult(
                success = true,
                message = "操作已执行",
                confidence = 0.7f
            )
        }
    }
    
    /**
     * 检查状态是否有变化
     */
    private fun hasStateChanged(beforeState: ScreenState, afterState: ScreenState): Boolean {
        // 检查包名变化
        if (beforeState.packageName != afterState.packageName) return true
        
        // 检查 Activity 变化
        if (beforeState.activityName != afterState.activityName) return true
        
        // 检查元素数量变化
        val elementCountDiff = kotlin.math.abs(
            beforeState.uiElements.size - afterState.uiElements.size
        )
        if (elementCountDiff > 2) return true
        
        // 检查元素内容变化
        val beforeTexts = beforeState.uiElements.map { it.text }.sorted()
        val afterTexts = afterState.uiElements.map { it.text }.sorted()
        if (beforeTexts != afterTexts) return true
        
        return false
    }
    
    /**
     * 检查元素是否发生位移（如键盘弹出）
     */
    private fun checkElementsShifted(beforeState: ScreenState, afterState: ScreenState): Boolean {
        // 找到相同文字的元素，比较位置
        for (beforeElem in beforeState.uiElements.take(10)) {
            if (beforeElem.text.isEmpty()) continue
            
            val afterElem = afterState.uiElements.find { it.text == beforeElem.text }
            if (afterElem != null) {
                val yDiff = kotlin.math.abs(beforeElem.bounds.centerY - afterElem.bounds.centerY)
                if (yDiff > 100) {  // Y 方向位移超过 100 像素
                    return true
                }
            }
        }
        return false
    }
}

