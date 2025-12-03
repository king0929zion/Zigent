package com.zigent.agent.models

/**
 * Agent操作类型
 */
enum class ActionType {
    // 基础触摸操作
    TAP,            // 点击
    DOUBLE_TAP,     // 双击
    LONG_PRESS,     // 长按
    SWIPE,          // 滑动（自定义起止点）
    
    // 方向滑动
    SWIPE_UP,       // 上滑
    SWIPE_DOWN,     // 下滑
    SWIPE_LEFT,     // 左滑
    SWIPE_RIGHT,    // 右滑
    
    // 滚动操作
    SCROLL,         // 滚动（页面内）
    SCROLL_TO_TOP,  // 滚动到顶部
    SCROLL_TO_BOTTOM, // 滚动到底部
    
    // 输入操作
    INPUT_TEXT,     // 输入文本
    CLEAR_TEXT,     // 清空输入框
    
    // 按键操作
    PRESS_KEY,      // 按键（返回、Home等）
    PRESS_BACK,     // 返回
    PRESS_HOME,     // 主页
    PRESS_RECENT,   // 最近任务
    PRESS_ENTER,    // 确认/回车
    
    // 应用操作
    OPEN_APP,       // 打开应用（通过包名或名称）
    CLOSE_APP,      // 关闭应用
    OPEN_URL,       // 打开URL
    OPEN_SETTINGS,  // 打开系统设置
    
    // 系统操作
    TAKE_SCREENSHOT, // 截图
    COPY_TEXT,      // 复制文本
    PASTE_TEXT,     // 粘贴文本
    
    // 视觉操作
    DESCRIBE_SCREEN, // 调用 VLM 描述屏幕
    
    // 通知操作
    OPEN_NOTIFICATION, // 打开通知栏
    CLEAR_NOTIFICATION, // 清除通知
    
    // 等待和控制
    WAIT,           // 等待
    WAIT_FOR_ELEMENT, // 等待元素出现
    
    // 任务状态
    FINISHED,       // 任务完成
    FAILED,         // 任务失败
    ASK_USER        // 询问用户
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
    val elementId: String? = null,           // 目标元素ID（优先使用）
    
    // 滑动相关
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
    val duration: Int? = null,
    val swipeDistance: Int? = null,          // 滑动距离（百分比，如50表示屏幕50%）
    
    // 输入文本
    val text: String? = null,
    
    // 按键
    val keyCode: Int? = null,
    val keyName: String? = null,             // 按键名称
    
    // 应用相关
    val packageName: String? = null,
    val appName: String? = null,             // 应用名称（用于查找包名）
    val activityName: String? = null,        // 要启动的Activity
    
    // URL
    val url: String? = null,
    
    // 等待相关
    val waitTime: Long? = null,
    val waitForText: String? = null,         // 等待出现的文字
    val timeout: Long? = null,               // 超时时间
    
    // 滚动方向
    val scrollDirection: ScrollDirection? = null,
    val scrollCount: Int? = null,            // 滚动次数
    
    // 结果消息（完成/失败/询问时使用）
    val resultMessage: String? = null,
    val question: String? = null             // ASK_USER时的问题
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
    val isScrollable: Boolean,
    val isFocused: Boolean = false          // 是否获得焦点
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

/**
 * 任务分析结果
 */
data class TaskAnalysis(
    val originalTask: String,               // 原始任务描述
    val needsExecution: Boolean,            // 是否需要执行手机操作
    val isSimpleChat: Boolean,              // 是否是简单对话
    val targetApp: String? = null,          // 目标应用名称
    val estimatedSteps: Int = 0,            // 预估步骤数
    val requiresUserConfirmation: Boolean = false // 是否需要用户确认才能执行
)
