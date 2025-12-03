package com.zigent.agent

import com.zigent.agent.models.AgentAction
import com.zigent.agent.models.AgentStep
import com.zigent.utils.Logger
import java.util.LinkedList

/**
 * 对话消息类型
 */
enum class MessageRole {
    USER,       // 用户输入
    ASSISTANT,  // AI响应
    SYSTEM,     // 系统消息（如操作结果）
    TOOL        // 工具调用结果
}

/**
 * 对话消息
 */
data class ConversationMessage(
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: MessageMetadata? = null
)

/**
 * 消息元数据
 */
data class MessageMetadata(
    val taskId: String? = null,          // 关联的任务ID
    val actionType: String? = null,      // 执行的操作类型
    val success: Boolean? = null,        // 操作是否成功
    val appContext: String? = null,      // 当时的应用上下文
    val stepNumber: Int? = null          // 步骤编号
)

/**
 * 用户偏好记忆
 */
data class UserPreference(
    val key: String,                     // 偏好键
    val value: String,                   // 偏好值
    val learnedFrom: String,             // 从哪个任务学到的
    val confidence: Float = 1.0f,        // 置信度
    val usageCount: Int = 1,             // 使用次数
    val lastUsed: Long = System.currentTimeMillis()
)

/**
 * 任务执行摘要（用于长期记忆）
 */
data class TaskSummary(
    val taskId: String,
    val userInput: String,               // 原始用户输入
    val targetApp: String?,              // 目标应用
    val success: Boolean,                // 是否成功
    val stepCount: Int,                  // 执行步骤数
    val keyActions: List<String>,        // 关键操作摘要
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 对话上下文记忆管理器
 * 
 * 功能：
 * 1. 短期记忆：当前会话的对话历史
 * 2. 工作记忆：当前任务的执行上下文
 * 3. 长期记忆：用户偏好和历史任务摘要
 */
class ConversationMemory {
    
    companion object {
        private const val TAG = "ConversationMemory"
        
        // 记忆容量限制
        private const val MAX_SHORT_TERM_MESSAGES = 20     // 短期记忆最大消息数
        private const val MAX_WORKING_MEMORY_STEPS = 10    // 工作记忆最大步骤数
        private const val MAX_TASK_SUMMARIES = 50          // 历史任务摘要最大数量
        private const val MAX_USER_PREFERENCES = 100       // 用户偏好最大数量
    }
    
    // ==================== 短期记忆（当前会话） ====================
    private val conversationHistory = LinkedList<ConversationMessage>()
    
    // ==================== 工作记忆（当前任务） ====================
    private var currentTaskId: String? = null
    private var currentTaskGoal: String? = null
    private val executionSteps = mutableListOf<AgentStep>()
    private var currentPlan: List<String> = emptyList()
    private var currentPlanIndex: Int = 0
    
    // ==================== 长期记忆 ====================
    private val taskSummaries = LinkedList<TaskSummary>()
    private val userPreferences = mutableMapOf<String, UserPreference>()
    
    // ==================== 短期记忆操作 ====================
    
    /**
     * 添加用户消息
     */
    fun addUserMessage(content: String, taskId: String? = null) {
        val message = ConversationMessage(
            role = MessageRole.USER,
            content = content,
            metadata = MessageMetadata(taskId = taskId)
        )
        addMessage(message)
        Logger.d("Added user message: ${content.take(50)}...", TAG)
    }
    
    /**
     * 添加AI响应
     */
    fun addAssistantMessage(content: String, taskId: String? = null) {
        val message = ConversationMessage(
            role = MessageRole.ASSISTANT,
            content = content,
            metadata = MessageMetadata(taskId = taskId)
        )
        addMessage(message)
        Logger.d("Added assistant message: ${content.take(50)}...", TAG)
    }
    
    /**
     * 添加系统消息（操作结果等）
     */
    fun addSystemMessage(content: String, action: AgentAction? = null, success: Boolean? = null) {
        val message = ConversationMessage(
            role = MessageRole.SYSTEM,
            content = content,
            metadata = MessageMetadata(
                taskId = currentTaskId,
                actionType = action?.type?.name,
                success = success
            )
        )
        addMessage(message)
    }
    
    private fun addMessage(message: ConversationMessage) {
        conversationHistory.add(message)
        // 限制短期记忆大小
        while (conversationHistory.size > MAX_SHORT_TERM_MESSAGES) {
            conversationHistory.removeFirst()
        }
    }
    
    /**
     * 获取对话历史（用于构建提示词）
     */
    fun getConversationHistory(maxMessages: Int = 10): List<ConversationMessage> {
        return conversationHistory.takeLast(maxMessages)
    }
    
    /**
     * 构建对话上下文字符串（用于提示词）
     */
    fun buildConversationContext(maxMessages: Int = 6): String {
        val messages = getConversationHistory(maxMessages)
        if (messages.isEmpty()) return ""
        
        val sb = StringBuilder()
        sb.appendLine("## 对话历史")
        
        messages.forEach { msg ->
            val rolePrefix = when (msg.role) {
                MessageRole.USER -> "用户"
                MessageRole.ASSISTANT -> "助手"
                MessageRole.SYSTEM -> "系统"
                MessageRole.TOOL -> "工具"
            }
            val content = msg.content.take(200)
            sb.appendLine("[$rolePrefix] $content")
        }
        
        return sb.toString()
    }
    
    // ==================== 工作记忆操作 ====================
    
    /**
     * 开始新任务
     */
    fun startTask(taskId: String, goal: String, plan: List<String> = emptyList()) {
        currentTaskId = taskId
        currentTaskGoal = goal
        currentPlan = plan
        currentPlanIndex = 0
        executionSteps.clear()
        
        addUserMessage(goal, taskId)
        Logger.i("Started task: $taskId - $goal", TAG)
    }
    
    /**
     * 更新任务规划
     */
    fun updatePlan(plan: List<String>) {
        currentPlan = plan
        currentPlanIndex = 0
        Logger.i("Plan updated: ${plan.size} steps", TAG)
    }
    
    /**
     * 记录执行步骤
     */
    fun recordStep(step: AgentStep) {
        executionSteps.add(step)
        
        // 限制工作记忆大小
        while (executionSteps.size > MAX_WORKING_MEMORY_STEPS) {
            executionSteps.removeAt(0)
        }
        
        // 更新规划进度
        if (step.success && currentPlanIndex < currentPlan.size) {
            currentPlanIndex++
        }
        
        // 添加到对话历史
        val resultText = if (step.success) {
            "✓ ${step.action.description}"
        } else {
            "✗ ${step.action.description}: ${step.errorMessage}"
        }
        addSystemMessage(resultText, step.action, step.success)
        
        Logger.d("Recorded step ${step.stepNumber}: ${step.action.type}", TAG)
    }
    
    /**
     * 获取当前执行步骤
     */
    fun getExecutionSteps(): List<AgentStep> = executionSteps.toList()
    
    /**
     * 获取当前规划进度
     */
    fun getPlanProgress(): Pair<Int, Int> = Pair(currentPlanIndex, currentPlan.size)
    
    /**
     * 获取下一个规划步骤
     */
    fun getNextPlanStep(): String? {
        return if (currentPlanIndex < currentPlan.size) {
            currentPlan[currentPlanIndex]
        } else null
    }
    
    /**
     * 构建工作记忆上下文（用于提示词）
     */
    fun buildWorkingMemoryContext(): String {
        val sb = StringBuilder()
        
        // 当前任务目标
        currentTaskGoal?.let {
            sb.appendLine("## 当前任务")
            sb.appendLine(it)
            sb.appendLine()
        }
        
        // 任务规划进度
        if (currentPlan.isNotEmpty()) {
            sb.appendLine("## 任务规划")
            currentPlan.forEachIndexed { index, step ->
                val marker = when {
                    index < currentPlanIndex -> "✓"  // 已完成
                    index == currentPlanIndex -> "➡" // 当前
                    else -> "○"                      // 待执行
                }
                sb.appendLine("$marker ${index + 1}. $step")
            }
            sb.appendLine()
        }
        
        // 最近执行步骤
        if (executionSteps.isNotEmpty()) {
            sb.appendLine("## 已执行操作")
            executionSteps.takeLast(5).forEach { step ->
                val status = if (step.success) "✓" else "✗"
                val error = if (!step.success && step.errorMessage != null) {
                    " [${step.errorMessage.take(30)}]"
                } else ""
                sb.appendLine("$status ${step.action.description}$error")
            }
            sb.appendLine()
        }
        
        return sb.toString()
    }
    
    /**
     * 结束当前任务
     */
    fun endTask(success: Boolean) {
        val taskId = currentTaskId ?: return
        val goal = currentTaskGoal ?: return
        
        // 生成任务摘要
        val summary = TaskSummary(
            taskId = taskId,
            userInput = goal,
            targetApp = detectTargetApp(goal),
            success = success,
            stepCount = executionSteps.size,
            keyActions = extractKeyActions()
        )
        
        // 保存到长期记忆
        taskSummaries.add(summary)
        while (taskSummaries.size > MAX_TASK_SUMMARIES) {
            taskSummaries.removeFirst()
        }
        
        // 学习用户偏好
        if (success) {
            learnFromTask(goal, executionSteps)
        }
        
        // 添加完成消息
        val resultMessage = if (success) "任务完成" else "任务失败"
        addAssistantMessage(resultMessage, taskId)
        
        Logger.i("Task ended: $taskId, success=$success", TAG)
        
        // 重置工作记忆
        currentTaskId = null
        currentTaskGoal = null
        currentPlan = emptyList()
        currentPlanIndex = 0
        executionSteps.clear()
    }
    
    // ==================== 长期记忆操作 ====================
    
    /**
     * 从成功任务中学习偏好
     */
    private fun learnFromTask(goal: String, steps: List<AgentStep>) {
        // 提取应用偏好
        steps.forEach { step ->
            step.action.appName?.let { app ->
                updatePreference("preferred_app_for_${detectTaskType(goal)}", app, goal)
            }
        }
        
        // 提取操作模式
        val actionSequence = steps.map { it.action.type.name }.joinToString("->")
        if (actionSequence.isNotEmpty()) {
            val taskType = detectTaskType(goal)
            updatePreference("action_pattern_$taskType", actionSequence, goal)
        }
    }
    
    private fun updatePreference(key: String, value: String, learnedFrom: String) {
        val existing = userPreferences[key]
        if (existing != null) {
            // 更新已有偏好
            userPreferences[key] = existing.copy(
                value = value,
                usageCount = existing.usageCount + 1,
                confidence = minOf(1.0f, existing.confidence + 0.1f),
                lastUsed = System.currentTimeMillis()
            )
        } else {
            // 新增偏好
            userPreferences[key] = UserPreference(
                key = key,
                value = value,
                learnedFrom = learnedFrom
            )
        }
        
        // 限制偏好数量
        if (userPreferences.size > MAX_USER_PREFERENCES) {
            // 移除最少使用的
            val leastUsed = userPreferences.values.minByOrNull { it.usageCount }
            leastUsed?.let { userPreferences.remove(it.key) }
        }
    }
    
    /**
     * 获取用户偏好
     */
    fun getPreference(key: String): String? = userPreferences[key]?.value
    
    /**
     * 获取相关历史任务
     */
    fun getRelatedTasks(currentGoal: String, maxCount: Int = 3): List<TaskSummary> {
        val keywords = extractKeywords(currentGoal)
        return taskSummaries
            .filter { summary ->
                keywords.any { keyword ->
                    summary.userInput.contains(keyword, ignoreCase = true) ||
                    summary.targetApp?.contains(keyword, ignoreCase = true) == true
                }
            }
            .sortedByDescending { it.timestamp }
            .take(maxCount)
    }
    
    /**
     * 构建长期记忆上下文（用于提示词）
     */
    fun buildLongTermMemoryContext(currentGoal: String): String {
        val sb = StringBuilder()
        
        // 相关历史任务
        val relatedTasks = getRelatedTasks(currentGoal)
        if (relatedTasks.isNotEmpty()) {
            sb.appendLine("## 相关历史任务")
            relatedTasks.forEach { task ->
                val status = if (task.success) "成功" else "失败"
                sb.appendLine("- ${task.userInput.take(50)} ($status, ${task.stepCount}步)")
            }
            sb.appendLine()
        }
        
        // 用户偏好（如果有相关的）
        val taskType = detectTaskType(currentGoal)
        val preferredApp = getPreference("preferred_app_for_$taskType")
        if (preferredApp != null) {
            sb.appendLine("## 用户偏好")
            sb.appendLine("- 偏好应用: $preferredApp")
            sb.appendLine()
        }
        
        return sb.toString()
    }
    
    // ==================== 辅助方法 ====================
    
    private fun detectTargetApp(goal: String): String? {
        val appKeywords = mapOf(
            "微信" to "微信",
            "支付宝" to "支付宝",
            "淘宝" to "淘宝",
            "抖音" to "抖音",
            "设置" to "设置"
        )
        
        for ((keyword, app) in appKeywords) {
            if (goal.contains(keyword)) return app
        }
        return null
    }
    
    private fun detectTaskType(goal: String): String {
        return when {
            goal.contains("发") && (goal.contains("消息") || goal.contains("微信")) -> "message"
            goal.contains("搜索") || goal.contains("搜") -> "search"
            goal.contains("打开") -> "open_app"
            goal.contains("支付") || goal.contains("转账") -> "payment"
            goal.contains("设置") -> "settings"
            else -> "general"
        }
    }
    
    private fun extractKeywords(text: String): List<String> {
        val keywords = mutableListOf<String>()
        
        // 简单的关键词提取
        val patterns = listOf(
            "微信", "支付宝", "淘宝", "抖音", "京东", "美团",
            "搜索", "发送", "打开", "设置", "播放"
        )
        
        patterns.forEach { pattern ->
            if (text.contains(pattern)) {
                keywords.add(pattern)
            }
        }
        
        return keywords
    }
    
    private fun extractKeyActions(): List<String> {
        return executionSteps
            .filter { it.success }
            .map { it.action.description }
            .take(5)
    }
    
    // ==================== 清理方法 ====================
    
    /**
     * 清除短期记忆
     */
    fun clearShortTermMemory() {
        conversationHistory.clear()
        Logger.i("Short-term memory cleared", TAG)
    }
    
    /**
     * 清除工作记忆
     */
    fun clearWorkingMemory() {
        currentTaskId = null
        currentTaskGoal = null
        currentPlan = emptyList()
        currentPlanIndex = 0
        executionSteps.clear()
        Logger.i("Working memory cleared", TAG)
    }
    
    /**
     * 清除所有记忆
     */
    fun clearAll() {
        clearShortTermMemory()
        clearWorkingMemory()
        taskSummaries.clear()
        userPreferences.clear()
        Logger.i("All memory cleared", TAG)
    }
}
