package com.zigent.agent

import com.zigent.agent.models.ActionType
import com.zigent.utils.Logger

/**
 * 子任务
 */
data class SubTask(
    val id: String,                          // 子任务ID
    val description: String,                 // 任务描述
    val actionHint: ActionType? = null,      // 建议的操作类型
    val targetApp: String? = null,           // 目标应用
    val targetElement: String? = null,       // 目标元素描述
    val inputText: String? = null,           // 需要输入的文字
    val dependencies: List<String> = emptyList(),  // 依赖的子任务ID
    val isOptional: Boolean = false,         // 是否可选
    val priority: Int = 0                    // 优先级 (0最高)
)

/**
 * 任务分解结果
 */
data class DecomposedTask(
    val originalTask: String,                // 原始任务
    val subTasks: List<SubTask>,             // 子任务列表
    val estimatedSteps: Int,                 // 预估步骤数
    val complexity: TaskComplexity,          // 任务复杂度
    val targetApp: String? = null,           // 主要目标应用
    val requiresUserInput: Boolean = false   // 是否需要用户额外输入
)

/**
 * 任务复杂度
 */
enum class TaskComplexity {
    SIMPLE,      // 简单（1-2步）
    MODERATE,    // 中等（3-5步）
    COMPLEX,     // 复杂（6-10步）
    VERY_COMPLEX // 非常复杂（10+步）
}

/**
 * 任务分解器
 * 将复杂的用户任务分解为可执行的子任务序列
 */
class TaskDecomposer {
    
    companion object {
        private const val TAG = "TaskDecomposer"
    }
    
    // 任务模板库
    private val taskTemplates = mapOf(
        // ==================== 微信相关 ====================
        "发微信" to TaskTemplate(
            pattern = Regex("(发|发送).*(微信|wx).*给(.+?)(说|内容|消息)?[:：]?(.*)"),
            subTasks = { match ->
                val contact = match.groupValues[3].trim()
                val message = match.groupValues[5].trim()
                listOf(
                    SubTask("1", "打开微信", ActionType.OPEN_APP, targetApp = "微信"),
                    SubTask("2", "点击搜索", ActionType.TAP, targetElement = "搜索", dependencies = listOf("1")),
                    SubTask("3", "搜索联系人: $contact", ActionType.INPUT_TEXT, inputText = contact, dependencies = listOf("2")),
                    SubTask("4", "点击联系人", ActionType.TAP, targetElement = contact, dependencies = listOf("3")),
                    SubTask("5", "点击输入框", ActionType.TAP, targetElement = "输入框", dependencies = listOf("4")),
                    SubTask("6", "输入消息内容", ActionType.INPUT_TEXT, inputText = message.ifEmpty { "[需要用户输入]" }, dependencies = listOf("5")),
                    SubTask("7", "发送消息", ActionType.TAP, targetElement = "发送", dependencies = listOf("6"))
                )
            },
            targetApp = "微信"
        ),
        
        "微信搜索" to TaskTemplate(
            pattern = Regex("(在)?微信.*(搜索|搜|找)(.+)"),
            subTasks = { match ->
                val keyword = match.groupValues[3].trim()
                listOf(
                    SubTask("1", "打开微信", ActionType.OPEN_APP, targetApp = "微信"),
                    SubTask("2", "点击搜索", ActionType.TAP, targetElement = "搜索", dependencies = listOf("1")),
                    SubTask("3", "输入搜索内容: $keyword", ActionType.INPUT_TEXT, inputText = keyword, dependencies = listOf("2")),
                    SubTask("4", "确认搜索", ActionType.PRESS_ENTER, dependencies = listOf("3"))
                )
            },
            targetApp = "微信"
        ),
        
        // ==================== 支付宝相关 ====================
        "支付宝转账" to TaskTemplate(
            pattern = Regex("(用)?支付宝.*(转账?|付款?).*给?(.+?)([0-9]+)元?"),
            subTasks = { match ->
                val target = match.groupValues[3].trim()
                val amount = match.groupValues[4].trim()
                listOf(
                    SubTask("1", "打开支付宝", ActionType.OPEN_APP, targetApp = "支付宝"),
                    SubTask("2", "点击转账", ActionType.TAP, targetElement = "转账", dependencies = listOf("1")),
                    SubTask("3", "选择收款方", ActionType.TAP, targetElement = target, dependencies = listOf("2")),
                    SubTask("4", "输入金额: $amount", ActionType.INPUT_TEXT, inputText = amount, dependencies = listOf("3")),
                    SubTask("5", "确认转账", ActionType.TAP, targetElement = "确认转账", dependencies = listOf("4")),
                    SubTask("6", "验证支付", ActionType.ASK_USER, dependencies = listOf("5"), isOptional = true)
                )
            },
            targetApp = "支付宝"
        ),
        
        "支付宝扫码" to TaskTemplate(
            pattern = Regex("(用)?支付宝.*(扫码|扫一扫|扫)"),
            subTasks = { _ ->
                listOf(
                    SubTask("1", "打开支付宝", ActionType.OPEN_APP, targetApp = "支付宝"),
                    SubTask("2", "点击扫一扫", ActionType.TAP, targetElement = "扫一扫", dependencies = listOf("1"))
                )
            },
            targetApp = "支付宝"
        ),
        
        // ==================== 抖音相关 ====================
        "抖音搜索" to TaskTemplate(
            pattern = Regex("(在)?抖音.*(搜索|搜|找|看)(.+)"),
            subTasks = { match ->
                val keyword = match.groupValues[3].trim()
                listOf(
                    SubTask("1", "打开抖音", ActionType.OPEN_APP, targetApp = "抖音"),
                    SubTask("2", "点击搜索", ActionType.TAP, targetElement = "搜索", dependencies = listOf("1")),
                    SubTask("3", "输入搜索内容: $keyword", ActionType.INPUT_TEXT, inputText = keyword, dependencies = listOf("2")),
                    SubTask("4", "确认搜索", ActionType.PRESS_ENTER, dependencies = listOf("3"))
                )
            },
            targetApp = "抖音"
        ),
        
        // ==================== 通用搜索 ====================
        "应用内搜索" to TaskTemplate(
            pattern = Regex("(在)?(.+?)(里|中|上)?(搜索|搜|找|查)(.+)"),
            subTasks = { match ->
                val app = match.groupValues[2].trim()
                val keyword = match.groupValues[5].trim()
                listOf(
                    SubTask("1", "打开$app", ActionType.OPEN_APP, targetApp = app),
                    SubTask("2", "点击搜索", ActionType.TAP, targetElement = "搜索", dependencies = listOf("1")),
                    SubTask("3", "输入: $keyword", ActionType.INPUT_TEXT, inputText = keyword, dependencies = listOf("2")),
                    SubTask("4", "确认搜索", ActionType.PRESS_ENTER, dependencies = listOf("3"))
                )
            }
        ),
        
        // ==================== 打开应用 ====================
        "打开应用" to TaskTemplate(
            pattern = Regex("(打开|启动|运行|进入)(.+?)(应用|app|软件)?$"),
            subTasks = { match ->
                val app = match.groupValues[2].trim()
                listOf(
                    SubTask("1", "打开$app", ActionType.OPEN_APP, targetApp = app)
                )
            }
        ),
        
        // ==================== 设置相关 ====================
        "打开设置项" to TaskTemplate(
            pattern = Regex("(打开|进入|设置).*(wifi|蓝牙|亮度|音量|网络|飞行模式|位置|定位|通知).*设置?"),
            subTasks = { match ->
                val setting = match.groupValues[2].trim()
                listOf(
                    SubTask("1", "打开设置", ActionType.OPEN_APP, targetApp = "设置"),
                    SubTask("2", "搜索设置项: $setting", ActionType.TAP, targetElement = "搜索", dependencies = listOf("1")),
                    SubTask("3", "输入: $setting", ActionType.INPUT_TEXT, inputText = setting, dependencies = listOf("2")),
                    SubTask("4", "进入设置项", ActionType.TAP, targetElement = setting, dependencies = listOf("3"))
                )
            },
            targetApp = "设置"
        ),
        
        // ==================== 拍照/相机 ====================
        "拍照" to TaskTemplate(
            pattern = Regex("(拍照|拍张?照片?|打开相机)"),
            subTasks = { _ ->
                listOf(
                    SubTask("1", "打开相机", ActionType.OPEN_APP, targetApp = "相机"),
                    SubTask("2", "点击拍照按钮", ActionType.TAP, targetElement = "拍照", dependencies = listOf("1"))
                )
            },
            targetApp = "相机"
        ),
        
        // ==================== 导航/地图 ====================
        "导航到" to TaskTemplate(
            pattern = Regex("(导航|去|怎么去|路线)到?(.+)"),
            subTasks = { match ->
                val destination = match.groupValues[2].trim()
                listOf(
                    SubTask("1", "打开高德地图", ActionType.OPEN_APP, targetApp = "高德地图"),
                    SubTask("2", "点击搜索", ActionType.TAP, targetElement = "搜索", dependencies = listOf("1")),
                    SubTask("3", "输入目的地: $destination", ActionType.INPUT_TEXT, inputText = destination, dependencies = listOf("2")),
                    SubTask("4", "选择目的地", ActionType.TAP, targetElement = destination, dependencies = listOf("3")),
                    SubTask("5", "开始导航", ActionType.TAP, targetElement = "导航", dependencies = listOf("4"))
                )
            },
            targetApp = "高德地图"
        )
    )
    
    /**
     * 分解任务
     */
    fun decompose(task: String): DecomposedTask {
        Logger.i("Decomposing task: $task", TAG)
        
        val taskLower = task.lowercase()
        
        // 尝试匹配任务模板
        for ((name, template) in taskTemplates) {
            val match = template.pattern.find(taskLower)
            if (match != null) {
                Logger.i("Matched template: $name", TAG)
                val subTasks = template.subTasks(match)
                val complexity = calculateComplexity(subTasks.size)
                val requiresUserInput = subTasks.any { it.inputText == "[需要用户输入]" }
                
                return DecomposedTask(
                    originalTask = task,
                    subTasks = subTasks,
                    estimatedSteps = subTasks.size,
                    complexity = complexity,
                    targetApp = template.targetApp,
                    requiresUserInput = requiresUserInput
                )
            }
        }
        
        // 未匹配到模板，使用通用分解
        return decomposeGeneric(task)
    }
    
    /**
     * 通用任务分解（无模板匹配时）
     */
    private fun decomposeGeneric(task: String): DecomposedTask {
        Logger.i("Using generic decomposition for: $task", TAG)
        
        val subTasks = mutableListOf<SubTask>()
        val taskLower = task.lowercase()
        
        // 检测目标应用
        val targetApp = detectTargetApp(taskLower)
        if (targetApp != null) {
            subTasks.add(SubTask("1", "打开$targetApp", ActionType.OPEN_APP, targetApp = targetApp))
        }
        
        // 检测操作类型
        when {
            taskLower.contains("搜索") || taskLower.contains("搜") || taskLower.contains("找") -> {
                val keyword = extractKeyword(task)
                subTasks.add(SubTask(
                    "${subTasks.size + 1}",
                    "点击搜索",
                    ActionType.TAP,
                    targetElement = "搜索",
                    dependencies = if (subTasks.isNotEmpty()) listOf("1") else emptyList()
                ))
                if (keyword.isNotEmpty()) {
                    subTasks.add(SubTask(
                        "${subTasks.size + 1}",
                        "输入搜索词: $keyword",
                        ActionType.INPUT_TEXT,
                        inputText = keyword,
                        dependencies = listOf("${subTasks.size}")
                    ))
                }
            }
            
            taskLower.contains("发送") || taskLower.contains("发") -> {
                val content = extractContent(task)
                subTasks.add(SubTask(
                    "${subTasks.size + 1}",
                    "点击输入框",
                    ActionType.TAP,
                    targetElement = "输入框",
                    dependencies = if (subTasks.isNotEmpty()) listOf("1") else emptyList()
                ))
                subTasks.add(SubTask(
                    "${subTasks.size + 1}",
                    "输入内容",
                    ActionType.INPUT_TEXT,
                    inputText = content.ifEmpty { "[需要用户输入]" },
                    dependencies = listOf("${subTasks.size}")
                ))
                subTasks.add(SubTask(
                    "${subTasks.size + 1}",
                    "发送",
                    ActionType.TAP,
                    targetElement = "发送",
                    dependencies = listOf("${subTasks.size}")
                ))
            }
            
            taskLower.contains("打开") || taskLower.contains("启动") -> {
                // 已经处理了打开应用
                if (subTasks.isEmpty()) {
                    subTasks.add(SubTask("1", "执行打开操作", ActionType.OPEN_APP))
                }
            }
            
            else -> {
                // 完全未知的任务
                subTasks.add(SubTask("1", "执行用户任务: $task", null))
            }
        }
        
        // 添加完成步骤
        if (subTasks.isNotEmpty()) {
            subTasks.add(SubTask(
                "${subTasks.size + 1}",
                "确认任务完成",
                ActionType.FINISHED,
                dependencies = listOf("${subTasks.size}")
            ))
        }
        
        val complexity = calculateComplexity(subTasks.size)
        val requiresUserInput = subTasks.any { it.inputText == "[需要用户输入]" }
        
        return DecomposedTask(
            originalTask = task,
            subTasks = subTasks,
            estimatedSteps = subTasks.size,
            complexity = complexity,
            targetApp = targetApp,
            requiresUserInput = requiresUserInput
        )
    }
    
    /**
     * 检测目标应用
     */
    private fun detectTargetApp(task: String): String? {
        val appKeywords = mapOf(
            "微信" to "微信",
            "wechat" to "微信",
            "支付宝" to "支付宝",
            "alipay" to "支付宝",
            "抖音" to "抖音",
            "douyin" to "抖音",
            "淘宝" to "淘宝",
            "京东" to "京东",
            "美团" to "美团",
            "饿了么" to "饿了么",
            "高德" to "高德地图",
            "百度地图" to "百度地图",
            "设置" to "设置",
            "相机" to "相机",
            "相册" to "相册",
            "qq" to "QQ",
            "快手" to "快手",
            "小红书" to "小红书",
            "b站" to "哔哩哔哩",
            "bilibili" to "哔哩哔哩"
        )
        
        for ((keyword, app) in appKeywords) {
            if (task.contains(keyword)) {
                return app
            }
        }
        return null
    }
    
    /**
     * 提取搜索关键词
     */
    private fun extractKeyword(task: String): String {
        val patterns = listOf(
            Regex("搜索[\""]?(.+?)[\""]?$"),
            Regex("搜[\""]?(.+?)[\""]?$"),
            Regex("找[\""]?(.+?)[\""]?$"),
            Regex("查[\""]?(.+?)[\""]?$")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(task)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return ""
    }
    
    /**
     * 提取发送内容
     */
    private fun extractContent(task: String): String {
        val patterns = listOf(
            Regex("(说|内容|消息)[：:](.+)$"),
            Regex("发送[\""](.+?)[\""]"),
            Regex("发[\""](.+?)[\""]")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(task)
            if (match != null) {
                return match.groupValues.last().trim()
            }
        }
        return ""
    }
    
    /**
     * 计算任务复杂度
     */
    private fun calculateComplexity(stepCount: Int): TaskComplexity {
        return when {
            stepCount <= 2 -> TaskComplexity.SIMPLE
            stepCount <= 5 -> TaskComplexity.MODERATE
            stepCount <= 10 -> TaskComplexity.COMPLEX
            else -> TaskComplexity.VERY_COMPLEX
        }
    }
    
    /**
     * 获取下一个可执行的子任务
     */
    fun getNextExecutableSubTask(
        decomposed: DecomposedTask,
        completedTaskIds: Set<String>
    ): SubTask? {
        return decomposed.subTasks.find { subTask ->
            !completedTaskIds.contains(subTask.id) &&
            subTask.dependencies.all { completedTaskIds.contains(it) }
        }
    }
    
    /**
     * 生成任务概要（用于显示给用户）
     */
    fun generateTaskSummary(decomposed: DecomposedTask): String {
        val sb = StringBuilder()
        sb.appendLine("📋 任务分解 (${decomposed.complexity.name})")
        sb.appendLine("原始任务: ${decomposed.originalTask}")
        sb.appendLine("预估步骤: ${decomposed.estimatedSteps}")
        decomposed.targetApp?.let { sb.appendLine("目标应用: $it") }
        sb.appendLine()
        sb.appendLine("执行步骤:")
        decomposed.subTasks.forEachIndexed { index, subTask ->
            val optional = if (subTask.isOptional) " (可选)" else ""
            sb.appendLine("${index + 1}. ${subTask.description}$optional")
        }
        if (decomposed.requiresUserInput) {
            sb.appendLine()
            sb.appendLine("⚠️ 需要用户提供额外信息")
        }
        return sb.toString()
    }
}

/**
 * 任务模板
 */
private data class TaskTemplate(
    val pattern: Regex,
    val subTasks: (MatchResult) -> List<SubTask>,
    val targetApp: String? = null
)

