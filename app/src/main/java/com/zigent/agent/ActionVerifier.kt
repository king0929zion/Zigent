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
    val suggestion: String? = null, // 如果失败,给出建议
    val retryAction: RetryAction? = null,  // 建议的重试操作
    val canContinue: Boolean = true // 是否可以继续执行后续步骤
)

/**
 * 重试操作建议
 */
data class RetryAction(
    val type: RetryType,             // 重试类型
    val adjustedAction: AgentAction? = null,  // 调整后的操作
    val waitTime: Long = 0,          // 重试前等待时间
    val reason: String               // 重试原因
)

/**
 * 重试类型
 */
enum class RetryType {
    RETRY_SAME,       // 重试相同操作
    RETRY_ADJUSTED,   // 使用调整后的操作重试
    WAIT_AND_RETRY,   // 等待后重试
    SCROLL_AND_RETRY, // 滚动后重试
    FALLBACK,         // 使用备选方案
    SKIP,             // 跳过当前步骤
    ABORT             // 终止任务
}

/**
 * 操作确认回调
 */
interface OperationConfirmationCallback {
    fun onConfirmationRequired(action: AgentAction, reason: String, onConfirm: () -> Unit, onCancel: () -> Unit)
    fun onOperationFeedback(result: VerificationResult)
}

/**
 * 操作验证器
 * 验证执行的操作是否达到预期效果，并提供智能重试建议
 */
class ActionVerifier(
    private val context: Context? = null
) {
    
    companion object {
        private const val TAG = "ActionVerifier"
        
        // 验证阈值
        private const val MIN_STATE_CHANGE_THRESHOLD = 0.1f  // 最小状态变化阈值
        
        // 坐标调整参数
        private const val COORDINATE_ADJUST_OFFSET = 20  // 坐标微调偏移量
        private const val MAX_RETRY_ATTEMPTS = 3         // 最大重试次数
    }
    
    // 操作历史（用于检测重复失败）
    private val recentFailures = mutableListOf<FailedOperation>()
    private val MAX_FAILURE_HISTORY = 10
    
    // 确认回调
    var confirmationCallback: OperationConfirmationCallback? = null
    
    /**
     * 失败操作记录
     */
    private data class FailedOperation(
        val actionType: ActionType,
        val coordinates: Pair<Int, Int>?,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * 验证操作执行结果
     * @param action 执行的操作
     * @param beforeState 执行前的屏幕状态
     * @param afterState 执行后的屏幕状态
     * @param planStep 当前规划步骤（可选）
     * @return 验证结果
     */
    fun verify(
        action: AgentAction,
        beforeState: ScreenState,
        afterState: ScreenState,
        planStep: PlanStep? = null
    ): VerificationResult {
        Logger.d("Verifying action: ${action.type} - ${action.description}", TAG)
        
        val baseResult = when (action.type) {
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
        
        // 如果验证失败，生成重试建议
        val result = if (!baseResult.success) {
            enhanceWithRetryAction(baseResult, action, beforeState, afterState, planStep)
        } else {
            baseResult
        }
        
        // 记录失败操作
        if (!result.success) {
            recordFailure(action)
        }
        
        // 通知回调
        confirmationCallback?.onOperationFeedback(result)
        
        return result
    }
    
    /**
     * 验证规划步骤是否完成
     */
    fun verifyPlanStep(
        planStep: PlanStep,
        beforeState: ScreenState,
        afterState: ScreenState
    ): VerificationResult {
        Logger.d("Verifying plan step: ${planStep.description}", TAG)
        
        // 检查验证条件
        val verification = planStep.verification ?: return VerificationResult(
            success = true,
            message = "步骤已执行（无验证条件）",
            confidence = 0.7f
        )
        
        // 根据验证条件检查
        val verificationLower = verification.lowercase()
        
        return when {
            // 检查应用是否打开
            verificationLower.contains("应用已打开") || verificationLower.contains("app opened") -> {
                val targetApp = planStep.targetElement ?: ""
                verifyAppOpened(targetApp, afterState)
            }
            
            // 检查元素是否存在
            verificationLower.contains("出现") || verificationLower.contains("显示") -> {
                val targetText = extractTargetFromVerification(verification)
                verifyElementExists(targetText, afterState)
            }
            
            // 检查输入框是否激活
            verificationLower.contains("激活") || verificationLower.contains("聚焦") -> {
                verifyInputFocused(afterState)
            }
            
            // 检查内容是否输入
            verificationLower.contains("已输入") || verificationLower.contains("输入完成") -> {
                val expectedText = planStep.inputData ?: ""
                verifyTextEntered(expectedText, afterState)
            }
            
            // 检查页面是否变化
            verificationLower.contains("页面") || verificationLower.contains("界面") -> {
                verifyPageChanged(beforeState, afterState)
            }
            
            // 通用状态变化检查
            else -> verifyStateChanged(beforeState, afterState)
        }
    }
    
    /**
     * 增强验证结果，添加重试建议
     */
    private fun enhanceWithRetryAction(
        result: VerificationResult,
        action: AgentAction,
        beforeState: ScreenState,
        afterState: ScreenState,
        planStep: PlanStep?
    ): VerificationResult {
        val retryAction = generateRetryAction(action, beforeState, afterState, planStep)
        
        return result.copy(
            retryAction = retryAction,
            canContinue = retryAction.type != RetryType.ABORT
        )
    }
    
    /**
     * 生成重试操作建议
     */
    private fun generateRetryAction(
        action: AgentAction,
        beforeState: ScreenState,
        afterState: ScreenState,
        planStep: PlanStep?
    ): RetryAction {
        // 检查是否有重复失败
        val failureCount = countRecentFailures(action)
        
        if (failureCount >= MAX_RETRY_ATTEMPTS) {
            // 尝试使用备选方案
            planStep?.fallback?.let {
                return RetryAction(
                    type = RetryType.FALLBACK,
                    reason = "操作连续失败 $failureCount 次，尝试备选方案: $it"
                )
            }
            
            // 如果是可选步骤，跳过
            if (planStep?.isOptional == true) {
                return RetryAction(
                    type = RetryType.SKIP,
                    reason = "可选步骤失败，跳过继续"
                )
            }
            
            // 终止
            return RetryAction(
                type = RetryType.ABORT,
                reason = "操作连续失败 $failureCount 次，无法继续"
            )
        }
        
        // 根据操作类型生成重试建议
        return when (action.type) {
            ActionType.TAP, ActionType.LONG_PRESS, ActionType.DOUBLE_TAP -> {
                // 尝试调整坐标
                val adjustedAction = adjustCoordinates(action, failureCount)
                if (adjustedAction != null) {
                    RetryAction(
                        type = RetryType.RETRY_ADJUSTED,
                        adjustedAction = adjustedAction,
                        reason = "调整点击坐标后重试"
                    )
                } else {
                    // 可能元素不在屏幕上，尝试滚动
                    RetryAction(
                        type = RetryType.SCROLL_AND_RETRY,
                        waitTime = 500,
                        reason = "元素可能不在可见区域，尝试滚动后重试"
                    )
                }
            }
            
            ActionType.INPUT_TEXT -> {
                // 输入失败，可能需要先点击聚焦
                RetryAction(
                    type = RetryType.WAIT_AND_RETRY,
                    waitTime = 1000,
                    reason = "等待输入框聚焦后重试"
                )
            }
            
            ActionType.OPEN_APP -> {
                // 应用打开失败，等待后重试
                RetryAction(
                    type = RetryType.WAIT_AND_RETRY,
                    waitTime = 2000,
                    reason = "应用启动中，等待后重试"
                )
            }
            
            ActionType.SWIPE_UP, ActionType.SWIPE_DOWN,
            ActionType.SWIPE_LEFT, ActionType.SWIPE_RIGHT -> {
                // 滑动可能已到边界，重试同样操作
                RetryAction(
                    type = RetryType.RETRY_SAME,
                    reason = "可能已到边界，重试滑动"
                )
            }
            
            else -> {
                RetryAction(
                    type = RetryType.WAIT_AND_RETRY,
                    waitTime = 500,
                    reason = "等待页面响应后重试"
                )
            }
        }
    }
    
    /**
     * 调整点击坐标
     */
    private fun adjustCoordinates(action: AgentAction, attemptCount: Int): AgentAction? {
        val x = action.x ?: return null
        val y = action.y ?: return null
        
        // 根据失败次数调整坐标方向
        val adjustX = when (attemptCount % 4) {
            0 -> COORDINATE_ADJUST_OFFSET
            1 -> -COORDINATE_ADJUST_OFFSET
            2 -> 0
            else -> 0
        }
        val adjustY = when (attemptCount % 4) {
            0 -> 0
            1 -> 0
            2 -> COORDINATE_ADJUST_OFFSET
            else -> -COORDINATE_ADJUST_OFFSET
        }
        
        return action.copy(
            x = x + adjustX,
            y = y + adjustY,
            description = "${action.description} (坐标调整)"
        )
    }
    
    /**
     * 记录失败操作
     */
    private fun recordFailure(action: AgentAction) {
        val failure = FailedOperation(
            actionType = action.type,
            coordinates = if (action.x != null && action.y != null) {
                Pair(action.x, action.y)
            } else null
        )
        
        recentFailures.add(failure)
        
        // 限制历史大小
        while (recentFailures.size > MAX_FAILURE_HISTORY) {
            recentFailures.removeAt(0)
        }
    }
    
    /**
     * 统计最近相同操作的失败次数
     */
    private fun countRecentFailures(action: AgentAction): Int {
        val now = System.currentTimeMillis()
        val recentWindow = 30_000L  // 30秒内
        
        return recentFailures.count { failure ->
            failure.actionType == action.type &&
            (now - failure.timestamp) < recentWindow &&
            (failure.coordinates == null || 
             (action.x != null && action.y != null &&
              kotlin.math.abs(failure.coordinates.first - action.x) < 50 &&
              kotlin.math.abs(failure.coordinates.second - action.y) < 50))
        }
    }
    
    /**
     * 验证应用是否打开
     */
    private fun verifyAppOpened(appName: String, afterState: ScreenState): VerificationResult {
        val currentPackage = afterState.packageName.lowercase()
        val targetLower = appName.lowercase()
        
        if (currentPackage.contains(targetLower) || targetLower.contains(currentPackage)) {
            return VerificationResult(true, "应用已打开: $appName")
        }
        
        return VerificationResult(
            success = false,
            message = "应用未打开",
            suggestion = "当前在: ${afterState.packageName}"
        )
    }
    
    /**
     * 验证元素是否存在
     */
    private fun verifyElementExists(targetText: String, afterState: ScreenState): VerificationResult {
        if (targetText.isEmpty()) {
            return VerificationResult(true, "无具体目标元素", confidence = 0.6f)
        }
        
        val found = afterState.uiElements.any { elem ->
            elem.text.contains(targetText, ignoreCase = true) ||
            elem.description.contains(targetText, ignoreCase = true)
        }
        
        return if (found) {
            VerificationResult(true, "找到目标元素: $targetText")
        } else {
            VerificationResult(
                success = false,
                message = "未找到目标元素: $targetText",
                suggestion = "尝试滚动查找"
            )
        }
    }
    
    /**
     * 验证输入框是否聚焦
     */
    private fun verifyInputFocused(afterState: ScreenState): VerificationResult {
        val focusedInput = afterState.uiElements.any { it.isEditable && it.isFocused }
        
        return if (focusedInput) {
            VerificationResult(true, "输入框已激活")
        } else {
            VerificationResult(
                success = false,
                message = "输入框未激活",
                suggestion = "尝试点击输入框"
            )
        }
    }
    
    /**
     * 验证文字是否输入
     */
    private fun verifyTextEntered(expectedText: String, afterState: ScreenState): VerificationResult {
        if (expectedText.isEmpty()) {
            return VerificationResult(true, "无预期输入内容", confidence = 0.6f)
        }
        
        val found = afterState.uiElements.any { elem ->
            elem.text.contains(expectedText)
        }
        
        return if (found) {
            VerificationResult(true, "文字已输入")
        } else {
            VerificationResult(
                success = false,
                message = "未检测到输入的文字",
                suggestion = "确保输入框已聚焦"
            )
        }
    }
    
    /**
     * 验证页面是否变化
     */
    private fun verifyPageChanged(beforeState: ScreenState, afterState: ScreenState): VerificationResult {
        if (beforeState.activityName != afterState.activityName ||
            beforeState.packageName != afterState.packageName) {
            return VerificationResult(true, "页面已切换")
        }
        
        return VerificationResult(
            success = false,
            message = "页面未变化",
            confidence = 0.5f
        )
    }
    
    /**
     * 从验证条件中提取目标
     */
    private fun extractTargetFromVerification(verification: String): String {
        // 使用简单的字符串匹配
        val keywords = listOf("出现", "显示", "找到")
        
        for (keyword in keywords) {
            val index = verification.indexOf(keyword)
            if (index >= 0) {
                val afterKeyword = verification.substring(index + keyword.length).trim()
                val cleaned = afterKeyword.takeWhile { it.isLetterOrDigit() || it in "汉字中文" || it.code > 127 }.take(50)
                if (cleaned.isNotEmpty()) {
                    return cleaned
                }
            }
        }
        return ""
    }
    
    /**
     * 清除失败历史
     */
    fun clearFailureHistory() {
        recentFailures.clear()
        Logger.d("Failure history cleared", TAG)
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


