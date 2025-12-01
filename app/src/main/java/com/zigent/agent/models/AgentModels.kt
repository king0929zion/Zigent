package com.zigent.agent.models

/**
 * Agent操作类型
 */
enum class ActionType {
    TAP,            // 点击
    LONG_PRESS,     // 长按
    SWIPE,          // 滑动
    INPUT_TEXT,     // 输入文本
    PRESS_KEY,      // 按键（返回、Home等）
    OPEN_APP,       // 打开应用
    WAIT,           // 等待
    SCROLL,         // 滚动
    FINISHED,       // 任务完成
    FAILED          // 任务失败
}

/**
 * Agent操作
 */
data class AgentAction(
    val type: ActionType,
    val description: String,        // 操作描述（用于显示和日志）
    
    // 点击/长按相关
    val x: Int? = null,
    val y: Int? = null,
    val elementDescription: String? = null,  // 目标元素描述
    
    // 滑动相关
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
    val duration: Int? = null,
    
    // 输入文本
    val text: String? = null,
    
    // 按键
    val keyCode: Int? = null,
    
    // 打开应用
    val packageName: String? = null,
    
    // 等待时间（毫秒）
    val waitTime: Long? = null,
    
    // 滚动方向
    val scrollDirection: ScrollDirection? = null,
    
    // 结果消息（完成/失败时使用）
    val resultMessage: String? = null
)

/**
 * 滚动方向
 */
enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT
}

/**
 * 屏幕状态
 */
data class ScreenState(
    val packageName: String,                // 当前包名
    val activityName: String?,              // 当前Activity
    val screenDescription: String,          // 屏幕描述（AI生成）
    val uiElements: List<UiElement>,        // UI元素列表
    val screenshotBase64: String?,          // 截图Base64
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * UI元素
 */
data class UiElement(
    val id: String,                         // 元素ID
    val type: String,                       // 类型（Button, TextView等）
    val text: String,                       // 文本内容
    val description: String,                // 内容描述
    val bounds: ElementBounds,              // 边界
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean
)

/**
 * 元素边界
 */
data class ElementBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Agent任务
 */
data class AgentTask(
    val id: String,
    val userInput: String,                  // 用户原始输入
    val taskDescription: String,            // 任务描述
    val steps: List<String>,                // 规划的步骤
    val status: TaskStatus,
    val currentStep: Int = 0,
    val history: List<AgentStep> = emptyList(),
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null
)

/**
 * 任务状态
 */
enum class TaskStatus {
    PENDING,        // 等待执行
    PLANNING,       // 规划中
    EXECUTING,      // 执行中
    COMPLETED,      // 已完成
    FAILED,         // 失败
    CANCELLED       // 已取消
}

/**
 * Agent执行步骤记录
 */
data class AgentStep(
    val stepNumber: Int,
    val screenStateBefore: String,          // 执行前的屏幕状态描述
    val action: AgentAction,                // 执行的操作
    val screenStateAfter: String?,          // 执行后的屏幕状态描述
    val success: Boolean,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * AI决策结果
 */
data class AiDecision(
    val thought: String,                    // AI的思考过程
    val action: AgentAction,                // 决定执行的操作
    val confidence: Float = 1.0f            // 置信度
)

