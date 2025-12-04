package com.zigent.agent

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.zigent.ai.AiClient
import com.zigent.ai.AiSettings
import com.zigent.ai.models.ChatMessage
import com.zigent.ai.models.MessageRole
import com.zigent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 规划步骤
 */
data class PlanStep(
    val index: Int,                      // 步骤序号
    val description: String,             // 步骤描述
    val expectedAction: String?,         // 预期操作类型
    val targetElement: String?,          // 目标元素描述
    val inputData: String?,              // 需要输入的数据
    val verification: String?,           // 验证条件
    val isOptional: Boolean = false,     // 是否可选
    val fallback: String? = null         // 失败时的备选方案
)

/**
 * 任务规划结果
 */
data class TaskPlan(
    val taskId: String,
    val originalGoal: String,            // 原始任务目标
    val refinedGoal: String,             // AI理解后的精确目标
    val steps: List<PlanStep>,           // 规划步骤
    val targetApp: String?,              // 目标应用
    val estimatedDuration: Int,          // 预估时间（秒）
    val complexity: PlanComplexity,      // 复杂度
    val preconditions: List<String>,     // 前置条件
    val risks: List<String>,             // 风险点
    val requiresConfirmation: Boolean,   // 是否需要用户确认
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 规划复杂度
 */
enum class PlanComplexity {
    TRIVIAL,     // 极简（1步）
    SIMPLE,      // 简单（2-3步）
    MODERATE,    // 中等（4-6步）
    COMPLEX,     // 复杂（7-10步）
    VERY_COMPLEX // 非常复杂（10+步）
}

/**
 * 规划执行状态
 */
data class PlanExecutionState(
    val plan: TaskPlan,
    var currentStepIndex: Int = 0,
    var completedSteps: MutableList<Int> = mutableListOf(),
    var failedSteps: MutableList<Int> = mutableListOf(),
    var retryCount: Int = 0,
    val maxRetries: Int = 3
) {
    val currentStep: PlanStep? get() = plan.steps.getOrNull(currentStepIndex)
    val progress: Float get() = if (plan.steps.isEmpty()) 1f else completedSteps.size.toFloat() / plan.steps.size
    val isComplete: Boolean get() = currentStepIndex >= plan.steps.size
    val canRetry: Boolean get() = retryCount < maxRetries
    
    fun markStepComplete() {
        completedSteps.add(currentStepIndex)
        currentStepIndex++
        retryCount = 0
    }
    
    fun markStepFailed() {
        failedSteps.add(currentStepIndex)
        retryCount++
    }
    
    fun skipOptionalStep() {
        if (currentStep?.isOptional == true) {
            currentStepIndex++
            retryCount = 0
        }
    }
}

/**
 * AI驱动的任务规划器
 * 
 * 核心功能：
 * 1. 理解用户意图，生成精确的任务目标
 * 2. 分析任务复杂度，规划执行步骤
 * 3. 预判风险点，提供备选方案
 * 4. 支持动态调整规划
 */
class TaskPlanner(
    private val aiSettings: AiSettings
) {
    companion object {
        private const val TAG = "TaskPlanner"
        
        // 规划系统提示词
        private val PLANNING_SYSTEM_PROMPT = """
你是一个专业的手机操作任务规划专家。你的职责是分析用户的任务目标，并制定详细、可执行的操作步骤规划。

## 规划原则
1. 步骤要具体、可操作，每个步骤只做一件事
2. 考虑可能的失败情况，提供备选方案
3. 标注敏感操作（支付、删除等），需要用户确认
4. 评估任务复杂度和风险

## 输出格式
返回 JSON 格式的规划结果，包含以下字段：
```json
{
    "refined_goal": "精确理解后的任务目标",
    "target_app": "目标应用名称（如果有）",
    "estimated_duration": 预估秒数,
    "complexity": "TRIVIAL/SIMPLE/MODERATE/COMPLEX/VERY_COMPLEX",
    "requires_confirmation": true/false,
    "preconditions": ["前置条件1", "前置条件2"],
    "risks": ["风险点1", "风险点2"],
    "steps": [
        {
            "description": "步骤描述",
            "expected_action": "预期操作类型(tap/input_text/swipe等)",
            "target_element": "目标元素描述",
            "input_data": "需要输入的数据(如有)",
            "verification": "验证条件",
            "is_optional": false,
            "fallback": "失败时的备选方案"
        }
    ]
}
```

## 常见操作类型
- tap: 点击
- long_press: 长按
- input_text: 输入文字
- swipe_up/down/left/right: 滑动
- press_back: 返回
- open_app: 打开应用
- wait: 等待

## 注意事项
1. 每个步骤的 description 要清晰明了
2. 验证条件要具体，便于检查操作是否成功
3. 敏感操作（涉及金钱、隐私、删除）必须设置 requires_confirmation=true
4. 考虑用户可能的输入不完整情况
""".trimIndent()
    }
    
    private val aiClient = AiClient(aiSettings)
    private val gson = Gson()
    
    // 当前执行状态
    private var currentExecutionState: PlanExecutionState? = null
    
    /**
     * 规划任务
     * 使用 AI 分析用户目标，生成执行计划
     */
    suspend fun planTask(
        goal: String,
        context: String? = null,
        conversationHistory: String? = null
    ): Result<TaskPlan> = withContext(Dispatchers.IO) {
        Logger.i("=== Planning task: $goal ===", TAG)
        
        try {
            // 构建规划提示词
            val prompt = buildPlanningPrompt(goal, context, conversationHistory)
            
            // 调用 AI 生成规划
            val messages = listOf(
                ChatMessage(MessageRole.USER, prompt)
            )
            
            val result = aiClient.chat(
                messages = messages,
                systemPrompt = PLANNING_SYSTEM_PROMPT
            )
            
            result.fold(
                onSuccess = { response ->
                    Logger.d("AI planning response: ${response.take(500)}...", TAG)
                    
                    // 解析 JSON 响应
                    val plan = parsePlanResponse(goal, response)
                    if (plan != null) {
                        Logger.i("Plan created: ${plan.steps.size} steps, complexity=${plan.complexity}", TAG)
                        Result.success(plan)
                    } else {
                        // 解析失败，使用回退规划
                        Logger.w("Failed to parse AI plan, using fallback", TAG)
                        Result.success(createFallbackPlan(goal))
                    }
                },
                onFailure = { error ->
                    Logger.e("AI planning failed", error, TAG)
                    // AI 调用失败，使用回退规划
                    Result.success(createFallbackPlan(goal))
                }
            )
        } catch (e: Exception) {
            Logger.e("Planning error", e, TAG)
            Result.success(createFallbackPlan(goal))
        }
    }
    
    /**
     * 开始执行规划
     */
    fun startExecution(plan: TaskPlan): PlanExecutionState {
        val state = PlanExecutionState(plan)
        currentExecutionState = state
        Logger.i("Started execution of plan: ${plan.taskId}", TAG)
        return state
    }
    
    /**
     * 获取当前执行状态
     */
    fun getCurrentState(): PlanExecutionState? = currentExecutionState
    
    /**
     * 获取当前步骤的执行提示
     */
    fun getCurrentStepPrompt(): String? {
        val state = currentExecutionState ?: return null
        val step = state.currentStep ?: return null
        
        return buildString {
            appendLine("当前步骤 ${step.index + 1}/${state.plan.steps.size}: ${step.description}")
            step.targetElement?.let { appendLine("目标元素: $it") }
            step.expectedAction?.let { appendLine("预期操作: $it") }
            step.inputData?.let { appendLine("输入数据: $it") }
            step.verification?.let { appendLine("验证条件: $it") }
        }
    }
    
    /**
     * 标记当前步骤完成
     */
    fun markCurrentStepComplete() {
        currentExecutionState?.markStepComplete()
        Logger.d("Step marked complete, progress: ${currentExecutionState?.progress}", TAG)
    }
    
    /**
     * 标记当前步骤失败
     */
    fun markCurrentStepFailed(): Boolean {
        val state = currentExecutionState ?: return false
        state.markStepFailed()
        Logger.w("Step marked failed, retry count: ${state.retryCount}", TAG)
        return state.canRetry
    }
    
    /**
     * 跳过可选步骤
     */
    fun skipCurrentStep(): Boolean {
        val state = currentExecutionState ?: return false
        if (state.currentStep?.isOptional == true) {
            state.skipOptionalStep()
            Logger.i("Skipped optional step", TAG)
            return true
        }
        return false
    }
    
    /**
     * 动态调整规划
     * 当遇到意外情况时，让 AI 重新规划剩余步骤
     */
    suspend fun adjustPlan(
        reason: String,
        currentScreenState: String
    ): Result<TaskPlan> = withContext(Dispatchers.IO) {
        val state = currentExecutionState ?: return@withContext Result.failure(
            IllegalStateException("No active plan")
        )
        
        Logger.i("Adjusting plan due to: $reason", TAG)
        
        // 构建调整提示
        val prompt = buildAdjustmentPrompt(state, reason, currentScreenState)
        
        val messages = listOf(
            ChatMessage(MessageRole.USER, prompt)
        )
        
        val result = aiClient.chat(
            messages = messages,
            systemPrompt = PLANNING_SYSTEM_PROMPT
        )
        
        result.fold(
            onSuccess = { response ->
                val adjustedPlan = parsePlanResponse(state.plan.originalGoal, response)
                if (adjustedPlan != null) {
                    // 更新执行状态
                    currentExecutionState = PlanExecutionState(adjustedPlan)
                    Result.success(adjustedPlan)
                } else {
                    Result.failure(Exception("Failed to parse adjusted plan"))
                }
            },
            onFailure = { error ->
                Result.failure(error)
            }
        )
    }
    
    /**
     * 获取规划摘要文本
     */
    fun getPlanSummary(plan: TaskPlan): String {
        return buildString {
            appendLine("📋 任务规划")
            appendLine("目标: ${plan.refinedGoal}")
            plan.targetApp?.let { appendLine("应用: $it") }
            appendLine("复杂度: ${plan.complexity.name}")
            appendLine("预计时间: ${plan.estimatedDuration}秒")
            appendLine()
            
            if (plan.preconditions.isNotEmpty()) {
                appendLine("前置条件:")
                plan.preconditions.forEach { appendLine("  • $it") }
                appendLine()
            }
            
            appendLine("执行步骤:")
            plan.steps.forEachIndexed { index, step ->
                val optional = if (step.isOptional) " (可选)" else ""
                appendLine("${index + 1}. ${step.description}$optional")
            }
            
            if (plan.risks.isNotEmpty()) {
                appendLine()
                appendLine("⚠️ 注意事项:")
                plan.risks.forEach { appendLine("  • $it") }
            }
            
            if (plan.requiresConfirmation) {
                appendLine()
                appendLine("⚠️ 此任务包含敏感操作，需要您的确认")
            }
        }
    }
    
    // ==================== 私有方法 ====================
    
    private fun buildPlanningPrompt(
        goal: String,
        context: String?,
        conversationHistory: String?
    ): String {
        return buildString {
            appendLine("请为以下任务制定详细的执行规划：")
            appendLine()
            appendLine("## 任务目标")
            appendLine(goal)
            
            context?.let {
                appendLine()
                appendLine("## 当前上下文")
                appendLine(it)
            }
            
            conversationHistory?.let {
                appendLine()
                appendLine("## 对话历史")
                appendLine(it)
            }
            
            appendLine()
            appendLine("请返回 JSON 格式的规划结果。")
        }
    }
    
    private fun buildAdjustmentPrompt(
        state: PlanExecutionState,
        reason: String,
        currentScreenState: String
    ): String {
        return buildString {
            appendLine("需要调整任务规划。")
            appendLine()
            appendLine("## 原始目标")
            appendLine(state.plan.originalGoal)
            appendLine()
            appendLine("## 已完成步骤")
            state.completedSteps.forEach { index ->
                val step = state.plan.steps.getOrNull(index)
                step?.let { appendLine("✓ ${it.description}") }
            }
            appendLine()
            appendLine("## 调整原因")
            appendLine(reason)
            appendLine()
            appendLine("## 当前屏幕状态")
            appendLine(currentScreenState)
            appendLine()
            appendLine("请根据当前情况，重新规划剩余步骤。返回 JSON 格式。")
        }
    }
    
    private fun parsePlanResponse(originalGoal: String, response: String): TaskPlan? {
        try {
            // 提取 JSON（可能包含在 markdown 代码块中）
            val jsonStr = extractJson(response) ?: return null
            val json = gson.fromJson(jsonStr, JsonObject::class.java)
            
            val steps = mutableListOf<PlanStep>()
            val stepsArray = json.getAsJsonArray("steps") ?: JsonArray()
            
            stepsArray.forEachIndexed { index, element ->
                val stepObj = element.asJsonObject
                steps.add(
                    PlanStep(
                        index = index,
                        description = stepObj.get("description")?.asString ?: "步骤 ${index + 1}",
                        expectedAction = stepObj.get("expected_action")?.asString,
                        targetElement = stepObj.get("target_element")?.asString,
                        inputData = stepObj.get("input_data")?.asString,
                        verification = stepObj.get("verification")?.asString,
                        isOptional = stepObj.get("is_optional")?.asBoolean ?: false,
                        fallback = stepObj.get("fallback")?.asString
                    )
                )
            }

            // 补充收尾自检，避免遗漏最终确认
            val normalizedSteps = ensureCompletionStep(steps, originalGoal)
            
            val complexityStr = json.get("complexity")?.asString ?: "MODERATE"
            val complexity = try {
                PlanComplexity.valueOf(complexityStr.uppercase())
            } catch (e: Exception) {
                PlanComplexity.MODERATE
            }
            
            return TaskPlan(
                taskId = java.util.UUID.randomUUID().toString(),
                originalGoal = originalGoal,
                refinedGoal = json.get("refined_goal")?.asString ?: originalGoal,
                steps = normalizedSteps,
                targetApp = json.get("target_app")?.asString,
                estimatedDuration = json.get("estimated_duration")?.asInt ?: (normalizedSteps.size * 5),
                complexity = complexity,
                preconditions = json.getAsJsonArray("preconditions")?.map { it.asString } ?: emptyList(),
                risks = json.getAsJsonArray("risks")?.map { it.asString } ?: emptyList(),
                requiresConfirmation = json.get("requires_confirmation")?.asBoolean ?: false
            )
        } catch (e: Exception) {
            Logger.e("Failed to parse plan JSON", e, TAG)
            return null
        }
    }
    
    private fun extractJson(text: String): String? {
        // 尝试提取 markdown 代码块中的 JSON
        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```")
        val codeBlockMatch = codeBlockPattern.find(text)
        if (codeBlockMatch != null) {
            return codeBlockMatch.groupValues[1].trim()
        }
        
        // 尝试直接找 JSON 对象
        val jsonPattern = Regex("\\{[\\s\\S]*\\}")
        val jsonMatch = jsonPattern.find(text)
        return jsonMatch?.value
    }
    
    /**
     * 创建回退规划（当 AI 规划失败时使用）
     */
    private fun createFallbackPlan(goal: String): TaskPlan {
        Logger.w("Creating fallback plan for: $goal", TAG)
        
        val steps = mutableListOf<PlanStep>()
        val lowerGoal = goal.lowercase()
        
        // 检测目标应用
        val targetApp = detectTargetApp(lowerGoal)
        
        // 添加打开应用步骤
        targetApp?.let {
            steps.add(PlanStep(
                index = 0,
                description = "打开 $it",
                expectedAction = "open_app",
                targetElement = it,
                inputData = null,
                verification = "应用已打开"
            ))
        }
        
        // 根据任务类型添加通用步骤
        when {
            lowerGoal.contains("搜索") || lowerGoal.contains("找") -> {
                steps.add(PlanStep(
                    index = steps.size,
                    description = "点击搜索框",
                    expectedAction = "tap",
                    targetElement = "搜索",
                    inputData = null,
                    verification = "搜索框已激活"
                ))
                
                val keyword = extractSearchKeyword(goal)
                if (keyword.isNotEmpty()) {
                    steps.add(PlanStep(
                        index = steps.size,
                        description = "输入搜索关键词: $keyword",
                        expectedAction = "input_text",
                        targetElement = "搜索输入框",
                        inputData = keyword,
                        verification = "关键词已输入"
                    ))
                }
            }
            
            lowerGoal.contains("发") && (lowerGoal.contains("消息") || lowerGoal.contains("信息")) -> {
                steps.add(PlanStep(
                    index = steps.size,
                    description = "找到并点击联系人",
                    expectedAction = "tap",
                    targetElement = "联系人",
                    inputData = null,
                    verification = "进入聊天界面"
                ))
                steps.add(PlanStep(
                    index = steps.size,
                    description = "点击输入框",
                    expectedAction = "tap",
                    targetElement = "输入框",
                    inputData = null,
                    verification = "输入框已激活"
                ))
                steps.add(PlanStep(
                    index = steps.size,
                    description = "输入消息内容",
                    expectedAction = "input_text",
                    targetElement = "输入框",
                    inputData = extractMessageContent(goal),
                    verification = "消息已输入"
                ))
                steps.add(PlanStep(
                    index = steps.size,
                    description = "点击发送",
                    expectedAction = "tap",
                    targetElement = "发送按钮",
                    inputData = null,
                    verification = "消息已发送"
                ))
            }
        }
        
        val normalizedSteps = ensureCompletionStep(steps, goal)
        
        return TaskPlan(
            taskId = java.util.UUID.randomUUID().toString(),
            originalGoal = goal,
            refinedGoal = goal,
            steps = normalizedSteps,
            targetApp = targetApp,
            estimatedDuration = normalizedSteps.size * 5,
            complexity = when {
                normalizedSteps.size <= 1 -> PlanComplexity.TRIVIAL
                normalizedSteps.size <= 3 -> PlanComplexity.SIMPLE
                normalizedSteps.size <= 6 -> PlanComplexity.MODERATE
                else -> PlanComplexity.COMPLEX
            },
            preconditions = emptyList(),
            risks = emptyList(),
            requiresConfirmation = goal.contains("支付") || goal.contains("转账")
        )
    }

    /**
     * 补充收尾自检，防止漏掉最终确认
     */
    private fun ensureCompletionStep(steps: MutableList<PlanStep>, goal: String): List<PlanStep> {
        if (steps.isNotEmpty()) {
            val last = steps.last()
            val hasCheck = (last.verification ?: last.description).contains("完成") ||
                    (last.verification ?: "").contains("成功") ||
                    last.description.contains("确认")
            if (hasCheck) return steps.mapIndexed { idx, step -> step.copy(index = idx) }
        }
        steps.add(
            PlanStep(
                index = steps.size,
                description = "自检并确认任务已完成: $goal",
                expectedAction = "verify",
                targetElement = null,
                inputData = null,
                verification = "关键结果已呈现/任务目标达成",
                isOptional = false,
                fallback = "如未完成，回到上一步重试或提示用户协助"
            )
        )
        return steps.mapIndexed { idx, step -> step.copy(index = idx) }
    }
    
    private fun detectTargetApp(goal: String): String? {
        val appMap = mapOf(
            "微信" to "微信",
            "支付宝" to "支付宝",
            "淘宝" to "淘宝",
            "抖音" to "抖音",
            "京东" to "京东",
            "美团" to "美团",
            "设置" to "设置"
        )
        
        for ((keyword, app) in appMap) {
            if (goal.contains(keyword)) return app
        }
        return null
    }
    
    private fun extractSearchKeyword(goal: String): String {
        val keywords = listOf("搜索", "找", "查")
        
        for (keyword in keywords) {
            val index = goal.indexOf(keyword)
            if (index >= 0) {
                val afterKeyword = goal.substring(index + keyword.length).trim()
                val cleaned = afterKeyword.takeWhile { it != '\n' && it.code > 32 }.take(50)
                if (cleaned.isNotEmpty()) {
                    return cleaned
                }
            }
        }
        return ""
    }
    
    private fun extractMessageContent(goal: String): String? {
        // 先检查冒号分隔的模式
        val colonKeywords = listOf("说：", "说:", "内容：", "内容:")
        for (keyword in colonKeywords) {
            val index = goal.indexOf(keyword)
            if (index >= 0) {
                val content = goal.substring(index + keyword.length).trim()
                if (content.isNotEmpty()) {
                    return content.take(200)
                }
            }
        }
        
        // 尝试提取引号内的内容
        for (quoteStart in listOf('"', '\'')) {
            val startIdx = goal.indexOf(quoteStart)
            if (startIdx >= 0 && startIdx < goal.length - 1) {
                val remaining = goal.substring(startIdx + 1)
                val endIdx = remaining.indexOf(quoteStart)
                if (endIdx > 0) {
                    return remaining.substring(0, endIdx).trim()
                }
            }
        }
        
        return null
    }
    
    /**
     * 清除当前执行状态
     */
    fun clearState() {
        currentExecutionState = null
    }
}


