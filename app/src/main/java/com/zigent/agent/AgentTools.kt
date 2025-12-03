package com.zigent.agent

import com.google.gson.JsonObject
import com.zigent.ai.models.*
import com.zigent.utils.Logger

/**
 * Agent 工具定义与验证
 * 
 * 核心职责：
 * 1. 定义所有可用的 Function Calling 工具
 * 2. 提供工具参数验证和校验
 * 3. 提供工具调用上下文校验
 * 
 * 架构说明：
 * - 主 LLM (GLM-4.6)：任务理解 + Function Calling
 * - 辅助 VLM (Qwen3-VL-235B)：图片描述（当调用 describe_screen 时）
 * 
 * 工具分类：
 * 1. 触摸操作：tap, long_press, double_tap
 * 2. 滑动操作：swipe_up, swipe_down, swipe_left, swipe_right, swipe
 * 3. 滚动操作：scroll
 * 4. 输入操作：input_text, clear_text
 * 5. 按键操作：press_back, press_home, press_recent, press_enter
 * 6. 应用操作：open_app, close_app
 * 7. 视觉操作：describe_screen（调用 VLM 分析截图）
 * 8. 等待操作：wait
 * 9. 任务状态：finished, failed, ask_user
 */
object AgentTools {
    
    private const val TAG = "AgentTools"
    
    // ==================== 参数约束常量 ====================
    object Constraints {
        const val MIN_COORDINATE = 0
        const val MAX_COORDINATE = 3000  // 适应大多数手机屏幕
        const val MIN_SWIPE_DISTANCE = 1
        const val MAX_SWIPE_DISTANCE = 100
        const val MIN_DURATION = 100
        const val MAX_DURATION = 5000
        const val MIN_WAIT_TIME = 100
        const val MAX_WAIT_TIME = 30000
        const val MAX_TEXT_LENGTH = 1000
    }

    /**
     * 所有可用的工具列表
     * 参考 Manus 架构，提供清晰的工具分类和精确的描述
     */
    val ALL_TOOLS: List<Tool> = listOf(
        // ==================== 触摸操作 ====================
        createTool(
            name = "tap",
            description = """
                点击屏幕上的指定坐标位置。
                使用场景：
                - 点击按钮、图标、链接
                - 点击输入框（获取焦点后再输入）
                - 选择列表项、菜单项
                注意：使用屏幕元素列表中的坐标 (x, y)
            """.trimIndent(),
            properties = mapOf(
                "x" to intProperty("点击位置的横坐标（像素），从屏幕元素列表获取"),
                "y" to intProperty("点击位置的纵坐标（像素），从屏幕元素列表获取"),
                "description" to stringProperty("操作说明，描述点击的具体元素")
            ),
            required = listOf("x", "y", "description")
        ),
        
        createTool(
            name = "long_press",
            description = """
                长按屏幕上的指定位置。
                使用场景：
                - 触发长按菜单（复制、编辑等）
                - 开始拖拽操作
                - 进入编辑模式
                默认长按时间: 800毫秒
            """.trimIndent(),
            properties = mapOf(
                "x" to intProperty("长按位置的横坐标"),
                "y" to intProperty("长按位置的纵坐标"),
                "duration" to intProperty("长按时长（毫秒），默认800ms，特殊场景可设置更长"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("x", "y", "description")
        ),
        
        createTool(
            name = "double_tap",
            description = """
                双击屏幕上的指定位置。
                使用场景：
                - 放大/缩小图片
                - 快速选中文本
                - 特定应用的快捷操作
            """.trimIndent(),
            properties = mapOf(
                "x" to intProperty("双击位置的横坐标"),
                "y" to intProperty("双击位置的纵坐标"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("x", "y", "description")
        ),
        
        // ==================== 滑动操作 ====================
        createTool(
            name = "swipe_up",
            description = """
                向上滑动屏幕，滚动内容向下。
                使用场景：
                - 浏览更多内容（向下滚动页面）
                - 在列表中向下查找元素
                - 关闭底部弹窗
                distance: 1-100 表示屏幕百分比，默认 50
            """.trimIndent(),
            properties = mapOf(
                "distance" to intProperty("滑动距离，1-100表示屏幕百分比，建议30-70"),
                "speed" to enumProperty("滑动速度", listOf("slow", "normal", "fast")),
                "description" to stringProperty("操作说明，说明为什么滑动")
            ),
            required = listOf("description")
        ),
        
        createTool(
            name = "swipe_down",
            description = """
                向下滑动屏幕，滚动内容向上。
                使用场景：
                - 下拉刷新页面
                - 打开通知栏/下拉菜单
                - 查看之前的内容
                distance: 1-100 表示屏幕百分比，默认 50
            """.trimIndent(),
            properties = mapOf(
                "distance" to intProperty("滑动距离，1-100表示屏幕百分比，刷新页面用50-70"),
                "speed" to enumProperty("滑动速度", listOf("slow", "normal", "fast")),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        createTool(
            name = "swipe_left",
            description = """
                向左滑动屏幕。
                使用场景：
                - 切换到下一个标签页
                - 查看下一张图片/卡片
                - 滑动删除列表项
                distance: 1-100 表示屏幕百分比，默认 30
            """.trimIndent(),
            properties = mapOf(
                "distance" to intProperty("滑动距离，1-100表示屏幕百分比，切换页面用60-80"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        createTool(
            name = "swipe_right",
            description = """
                向右滑动屏幕。
                使用场景：
                - 返回上一个标签页
                - 查看上一张图片/卡片
                - 从屏幕左侧边缘返回
                distance: 1-100 表示屏幕百分比，默认 30
            """.trimIndent(),
            properties = mapOf(
                "distance" to intProperty("滑动距离，1-100表示屏幕百分比，边缘返回用20-40"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        createTool(
            name = "swipe",
            description = """
                自定义滑动操作，从起点滑动到终点。
                使用场景：
                - 需要精确控制滑动轨迹
                - 拖拽元素到指定位置
                - 解锁图案滑动
            """.trimIndent(),
            properties = mapOf(
                "start_x" to intProperty("滑动起点横坐标"),
                "start_y" to intProperty("滑动起点纵坐标"),
                "end_x" to intProperty("滑动终点横坐标"),
                "end_y" to intProperty("滑动终点纵坐标"),
                "duration" to intProperty("滑动时长（毫秒），默认300，较慢滑动用500-1000"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("start_x", "start_y", "end_x", "end_y", "description")
        ),
        
        // ==================== 滚动操作 ====================
        createTool(
            name = "scroll",
            description = """
                在可滚动区域内滚动。
                使用场景：
                - 在列表中查找元素（找不到时用 scroll 而不是 swipe）
                - 浏览长页面内容
                - 多次小幅滚动查找
                direction: up=向上滚动查看更多, down=向下查看之前
            """.trimIndent(),
            properties = mapOf(
                "direction" to enumProperty("滚动方向：up=内容向下滚，down=内容向上滚", listOf("up", "down", "left", "right")),
                "count" to intProperty("滚动次数，默认1，快速浏览可设置2-3"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("direction", "description")
        ),
        
        // ==================== 输入操作 ====================
        createTool(
            name = "input_text",
            description = """
                在当前聚焦的输入框中输入文字。
                重要：
                - 输入前必须先点击输入框(📝)使其获得焦点
                - 若输入框已有内容，可先调用 clear_text
                - 输入完成后可调用 press_enter 确认
                使用场景：
                - 搜索关键词输入
                - 账号/密码输入
                - 消息编辑
            """.trimIndent(),
            properties = mapOf(
                "text" to stringProperty("要输入的文字内容，支持中英文、数字、符号"),
                "description" to stringProperty("操作说明，说明输入的目的")
            ),
            required = listOf("text", "description")
        ),
        
        createTool(
            name = "clear_text",
            description = """
                清空当前输入框中的所有文字。
                使用场景：
                - 重新输入前清除旧内容
                - 清除默认填充的文本
                注意：输入框需要已获得焦点
            """.trimIndent(),
            properties = mapOf(
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        // ==================== 按键操作 ====================
        createTool(
            name = "press_back",
            description = """
                按返回键。
                使用场景：
                - 返回上一页/上一级
                - 关闭当前弹窗/对话框
                - 取消当前操作
                - 收起键盘
            """.trimIndent(),
            properties = mapOf(
                "description" to stringProperty("操作说明，说明为什么要返回")
            ),
            required = listOf("description")
        ),
        
        createTool(
            name = "press_home",
            description = """
                按主页键，返回手机桌面。
                使用场景：
                - 退出当前应用回到桌面
                - 打开新应用前先回桌面
            """.trimIndent(),
            properties = mapOf(
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        createTool(
            name = "press_recent",
            description = """
                打开最近任务/应用切换界面。
                使用场景：
                - 在多个应用间切换
                - 关闭后台应用
            """.trimIndent(),
            properties = mapOf(
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        createTool(
            name = "press_enter",
            description = """
                按确认/回车键。
                使用场景：
                - 确认搜索（输入关键词后）
                - 发送消息（在聊天输入框中）
                - 提交表单
                - 换行（部分应用）
            """.trimIndent(),
            properties = mapOf(
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        // ==================== 应用操作 ====================
        createTool(
            name = "open_app",
            description = """
                打开指定的应用程序。
                
                重要规则：
                1. 必须使用已安装应用列表中显示的完整名称
                2. 用户说别名时，智能匹配完整名称
                
                常见别名映射：
                - 谷歌笔记 → Google Keep / Keep记事本
                - 油管 → YouTube
                - 浏览器 → Chrome / Google Chrome
                - 谷歌地图 → Google Maps
                - 微信 → 微信
                - 淘宝 → 淘宝
                
                一定要在已安装应用列表中找到匹配的应用名。
            """.trimIndent(),
            properties = mapOf(
                "app" to stringProperty("应用名称，必须与已安装应用列表中的名称完全一致"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("app", "description")
        ),
        
        createTool(
            name = "close_app",
            description = """
                强制关闭指定的应用程序。
                使用场景：
                - 应用无响应需要强制关闭
                - 清理后台进程
            """.trimIndent(),
            properties = mapOf(
                "app" to stringProperty("应用名称"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("app", "description")
        ),
        
        // ==================== 视觉操作 ====================
        createTool(
            name = "describe_screen",
            description = """
                调用 VLM 分析当前屏幕截图，获取详细的视觉描述。
                
                使用时机：
                - 屏幕元素列表为空或不足以理解界面
                - 需要识别图片中的内容（验证码、二维码等）
                - 应用无法被无障碍服务抓取（如微信内部页面）
                
                限制：
                - 不能连续调用！获取描述后必须先执行其他操作
                - 每次调用消耗较多资源，谨慎使用
            """.trimIndent(),
            properties = mapOf(
                "focus" to stringProperty("希望重点关注的内容，如：图片内容、验证码、具体位置等"),
                "description" to stringProperty("为什么需要查看截图")
            ),
            required = listOf("description")
        ),
        
        // ==================== 等待操作 ====================
        createTool(
            name = "wait",
            description = """
                等待指定时间。
                使用场景：
                - 等待页面加载完成（1000-3000ms）
                - 等待动画结束（500-1000ms）
                - 等待网络请求（2000-5000ms）
                - 等待应用启动（1500-3000ms）
                
                建议时间范围：1000-5000毫秒
            """.trimIndent(),
            properties = mapOf(
                "time" to intProperty("等待时间（毫秒），建议1000-5000"),
                "reason" to stringProperty("等待原因"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        // ==================== 任务状态 ====================
        createTool(
            name = "finished",
            description = """
                标记任务已成功完成。
                
                调用时机：
                - 目标应用已成功打开
                - 用户请求的操作已全部执行完毕
                - 搜索/发送/设置等操作已确认成功
                
                重要：目标达成后应立即调用，不要多余操作
            """.trimIndent(),
            properties = mapOf(
                "message" to stringProperty("完成说明，描述完成了什么、结果是什么"),
                "summary" to stringProperty("任务执行摘要")
            ),
            required = listOf("message")
        ),
        
        createTool(
            name = "failed",
            description = """
                标记任务失败，无法继续执行。
                
                调用时机：
                - 目标元素多次滚动查找后仍未找到
                - 应用确实未安装（不在已安装列表中）
                - 操作被系统或应用拒绝
                - 尝试多种方法后仍无法完成
                
                不要轻易放弃，先尝试替代方案
            """.trimIndent(),
            properties = mapOf(
                "message" to stringProperty("失败原因，详细说明为什么无法完成"),
                "suggestion" to stringProperty("建议用户如何手动完成")
            ),
            required = listOf("message")
        ),
        
        createTool(
            name = "ask_user",
            description = """
                需要用户提供更多信息才能继续。
                
                调用时机：
                - 任务描述不清晰，需要确认具体意图
                - 有多个可能的选项，需要用户选择
                - 涉及敏感操作（支付、删除等）需要用户授权
                
                问题要清晰具体，便于用户快速回答
            """.trimIndent(),
            properties = mapOf(
                "question" to stringProperty("要问用户的具体问题，要清晰明确"),
                "options" to stringProperty("可选的回答选项，用逗号分隔")
            ),
            required = listOf("question")
        )
    )

    /**
     * 系统提示词
     * 参考 Manus AI 架构设计，采用结构化方法论
     * 双模型架构：LLM (GLM-4.6) + VLM (Qwen3-VL-235B)
     */
    val SYSTEM_PROMPT = """
# Zigent - Android 自动化助手（工具调用）

你通过 Function Calling 操作手机。遵守以下工作流和规则，禁止臆测。

## 工作流
1) **分析**：在 thought 中写清【目标】【计划步骤】【当前要做的步骤】。
2) **执行**：每次只调用 1 个工具，按计划推进。
3) **验证/恢复**：检查结果，失败重试≤3，必要时视觉或ask_user。

## 工具调用规范

### 参数约束
- **坐标 (x, y)**：必须从屏幕元素列表获取，禁止编造
- **滑动距离 (distance)**：1-100 表示屏幕百分比
- **时间 (duration/time)**：毫秒单位，合理范围 100-5000
- **文本 (text)**：最大 1000 字符

### 上下文校验
- **输入前**：必须先 tap 输入框获取焦点
- **describe_screen**：不能连续调用，获取后必须先执行其他操作
- **坐标操作**：屏幕元素为空时考虑调用 describe_screen

### 错误恢复
- 点击失败：滑动查找或调用 describe_screen
- 输入失败：先点击输入框，清空后重输
- 应用未找到：检查完整名称，确认已安装

## 关键规则
- **应用匹配**：open_app 必须用已安装列表的完整名称；别名匹配后仍用完整名；刚打开的应用，不要再声称未安装，可校验当前前台包名。
- **输入前聚焦**：输入前先 tap 输入框（📝）；坐标必须来自元素/视觉。
- **找不到就滚动**：元素找不到先 swipe_up/down；元素为空/不可抓取/需要图片时，调用 describe_screen 获取视觉描述。
- **一步一工具**：禁止把多个动作写在一个描述里。
- **安全**：涉及支付/转账/下单需先询问用户确认；不要编造安装状态或输出坐标文本。
- **结束语义**：目标达成立刻调 finished；无法继续才 failed；信息不足才 ask_user。
- **保持上下文**：记住计划和已完成步骤，基于历史继续，不要重复或重置进度。

## 屏幕元素符号
- 🔘 "文本" (x, y) ← 可点击
- 📝 "文本" (x, y) ← 输入框
- 📜 "文本" (x, y) ← 可滚动
- 📄 "文本" (x, y) ← 只读

## 可用工具（摘要）
- 触摸：tap / long_press / double_tap
- 滑动：swipe_up / swipe_down / swipe_left / swipe_right / scroll
- 输入：input_text / clear_text
- 按键：press_back / press_home / press_recent / press_enter
- 应用：open_app / close_app
- 视觉：describe_screen（需要视觉时调用，禁止连续调用）
- 等待：wait
- 任务：finished / failed / ask_user

## 输出要求
- thought：简述当前目标、计划、当前步骤和选择该动作的理由。
- 只返回工具调用，不要输出坐标或“点击XXX”文字描述。

""".trimIndent()

    // ==================== 辅助方法 ====================

    private fun createTool(
        name: String,
        description: String,
        properties: Map<String, PropertyDef>,
        required: List<String>
    ): Tool {
        return Tool(
            type = "function",
            function = FunctionDef(
                name = name,
                description = description,
                parameters = FunctionParameters(
                    type = "object",
                    properties = properties,
                    required = required
                )
            )
        )
    }

    private fun stringProperty(description: String): PropertyDef {
        return PropertyDef(type = "string", description = description)
    }

    private fun intProperty(description: String): PropertyDef {
        return PropertyDef(type = "integer", description = description)
    }

    private fun enumProperty(description: String, values: List<String>): PropertyDef {
        return PropertyDef(type = "string", description = description, enum = values)
    }

    /**
     * 根据工具名获取工具定义
     */
    fun getTool(name: String): Tool? {
        return ALL_TOOLS.find { it.function.name == name }
    }

    /**
     * 获取工具名列表
     */
    fun getToolNames(): List<String> {
        return ALL_TOOLS.map { it.function.name }
    }
    
    // ==================== 参数验证 ====================
    
    /**
     * 工具参数验证结果
     */
    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val correctedArgs: JsonObject? = null  // 自动修正后的参数
    )
    
    /**
     * 验证工具调用参数
     * 返回验证结果，包含错误信息和可能的自动修正
     */
    fun validateToolCall(toolName: String, args: JsonObject): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val correctedArgs = args.deepCopy()
        
        when (toolName) {
            "tap", "long_press", "double_tap" -> {
                // 验证坐标
                val x = args.get("x")?.asInt
                val y = args.get("y")?.asInt
                
                if (x == null) errors.add("缺少必要参数: x")
                if (y == null) errors.add("缺少必要参数: y")
                
                x?.let {
                    if (it < Constraints.MIN_COORDINATE || it > Constraints.MAX_COORDINATE) {
                        errors.add("x坐标超出范围: $it (应在 ${Constraints.MIN_COORDINATE}-${Constraints.MAX_COORDINATE})")
                    }
                }
                y?.let {
                    if (it < Constraints.MIN_COORDINATE || it > Constraints.MAX_COORDINATE) {
                        errors.add("y坐标超出范围: $it (应在 ${Constraints.MIN_COORDINATE}-${Constraints.MAX_COORDINATE})")
                    }
                }
                
                // 验证 long_press 的 duration
                if (toolName == "long_press") {
                    val duration = args.get("duration")?.asInt ?: 800
                    if (duration < Constraints.MIN_DURATION || duration > Constraints.MAX_DURATION) {
                        warnings.add("duration 建议范围: ${Constraints.MIN_DURATION}-${Constraints.MAX_DURATION}")
                        correctedArgs.addProperty("duration", duration.coerceIn(Constraints.MIN_DURATION, Constraints.MAX_DURATION))
                    }
                }
            }
            
            "swipe" -> {
                val startX = args.get("start_x")?.asInt
                val startY = args.get("start_y")?.asInt
                val endX = args.get("end_x")?.asInt
                val endY = args.get("end_y")?.asInt
                
                if (startX == null) errors.add("缺少必要参数: start_x")
                if (startY == null) errors.add("缺少必要参数: start_y")
                if (endX == null) errors.add("缺少必要参数: end_x")
                if (endY == null) errors.add("缺少必要参数: end_y")
                
                // 验证坐标范围
                listOf(startX to "start_x", startY to "start_y", endX to "end_x", endY to "end_y").forEach { (value, name) ->
                    value?.let {
                        if (it < Constraints.MIN_COORDINATE || it > Constraints.MAX_COORDINATE) {
                            errors.add("$name 坐标超出范围: $it")
                        }
                    }
                }
            }
            
            "swipe_up", "swipe_down", "swipe_left", "swipe_right" -> {
                val distance = args.get("distance")?.asInt ?: 50
                if (distance < Constraints.MIN_SWIPE_DISTANCE || distance > Constraints.MAX_SWIPE_DISTANCE) {
                    warnings.add("滑动距离应在 ${Constraints.MIN_SWIPE_DISTANCE}-${Constraints.MAX_SWIPE_DISTANCE}%")
                    correctedArgs.addProperty("distance", distance.coerceIn(Constraints.MIN_SWIPE_DISTANCE, Constraints.MAX_SWIPE_DISTANCE))
                }
            }
            
            "scroll" -> {
                val direction = args.get("direction")?.asString
                if (direction == null) {
                    errors.add("缺少必要参数: direction")
                } else if (direction !in listOf("up", "down", "left", "right")) {
                    errors.add("无效的滚动方向: $direction (应为 up/down/left/right)")
                }
            }
            
            "input_text" -> {
                val text = args.get("text")?.asString
                if (text.isNullOrEmpty()) {
                    errors.add("缺少必要参数: text")
                } else if (text.length > Constraints.MAX_TEXT_LENGTH) {
                    warnings.add("文本过长，已截断到 ${Constraints.MAX_TEXT_LENGTH} 字符")
                    correctedArgs.addProperty("text", text.take(Constraints.MAX_TEXT_LENGTH))
                }
            }
            
            "open_app" -> {
                val app = args.get("app")?.asString
                if (app.isNullOrEmpty()) {
                    errors.add("缺少必要参数: app")
                }
            }
            
            "wait" -> {
                val time = args.get("time")?.asLong ?: 2000L
                if (time < Constraints.MIN_WAIT_TIME || time > Constraints.MAX_WAIT_TIME) {
                    warnings.add("等待时间应在 ${Constraints.MIN_WAIT_TIME}-${Constraints.MAX_WAIT_TIME}ms")
                    correctedArgs.addProperty("time", time.coerceIn(Constraints.MIN_WAIT_TIME.toLong(), Constraints.MAX_WAIT_TIME.toLong()))
                }
            }
            
            "finished", "failed" -> {
                val message = args.get("message")?.asString
                if (message.isNullOrEmpty()) {
                    warnings.add("建议提供 message 参数说明结果")
                }
            }
            
            "ask_user" -> {
                val question = args.get("question")?.asString
                if (question.isNullOrEmpty()) {
                    errors.add("缺少必要参数: question")
                }
            }
        }
        
        if (errors.isNotEmpty()) {
            Logger.w("Tool validation errors for $toolName: $errors", TAG)
        }
        
        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            correctedArgs = if (correctedArgs != args) correctedArgs else null
        )
    }
    
    /**
     * 上下文校验结果
     */
    data class ContextCheckResult(
        val isValid: Boolean,
        val issues: List<String> = emptyList(),
        val suggestions: List<String> = emptyList()
    )
    
    /**
     * 校验工具调用的上下文合理性
     * 检查工具调用是否符合当前屏幕状态
     */
    fun checkToolContext(
        toolName: String,
        args: JsonObject,
        screenElements: List<String>,  // 当前屏幕元素的文本列表
        hasInputFocus: Boolean,         // 是否有输入框获得焦点
        lastToolName: String? = null    // 上一次调用的工具
    ): ContextCheckResult {
        val issues = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        
        when (toolName) {
            "input_text" -> {
                // 检查输入前是否有焦点
                if (!hasInputFocus && lastToolName != "tap") {
                    issues.add("输入文本前应先点击输入框")
                    suggestions.add("建议先调用 tap 点击输入框获取焦点")
                }
            }
            
            "describe_screen" -> {
                // 检查是否连续调用 describe_screen
                if (lastToolName == "describe_screen") {
                    issues.add("不应连续调用 describe_screen")
                    suggestions.add("获取视觉描述后应先执行其他操作")
                }
            }
            
            "tap", "long_press", "double_tap" -> {
                // 检查坐标是否在屏幕元素范围内
                val x = args.get("x")?.asInt ?: 0
                val y = args.get("y")?.asInt ?: 0
                
                // 检查坐标是否在已知元素附近（简化检查）
                if (screenElements.isEmpty()) {
                    issues.add("屏幕元素列表为空，坐标可能不准确")
                    suggestions.add("考虑先调用 describe_screen 获取视觉信息")
                }
            }
            
            "open_app" -> {
                val appName = args.get("app")?.asString ?: ""
                if (appName.isNotEmpty()) {
                    // 检查应用名是否包含常见别名（应该用完整名称）
                    val commonAliases = mapOf(
                        "谷歌笔记" to "Google Keep",
                        "油管" to "YouTube",
                        "浏览器" to "Chrome"
                    )
                    commonAliases[appName]?.let { fullName ->
                        suggestions.add("应用别名 '$appName' 可能对应 '$fullName'，建议使用完整名称")
                    }
                }
            }
        }
        
        return ContextCheckResult(
            isValid = issues.isEmpty(),
            issues = issues,
            suggestions = suggestions
        )
    }
    
    /**
     * 获取工具调用的建议说明
     * 用于错误恢复时提供替代方案
     */
    fun getToolSuggestion(toolName: String, error: String): String {
        return when {
            toolName == "tap" && error.contains("坐标") -> {
                "坐标可能不准确，建议：1) 调用 describe_screen 获取视觉信息；2) 滑动页面查找目标元素；3) 使用屏幕元素列表中的坐标"
            }
            toolName == "input_text" && error.contains("失败") -> {
                "输入失败，建议：1) 先点击输入框获取焦点；2) 清空现有内容后再输入；3) 检查是否有弹出键盘"
            }
            toolName == "open_app" && error.contains("未找到") -> {
                "应用未找到，建议：1) 检查应用名称是否正确；2) 使用已安装应用列表中的完整名称；3) 询问用户确认应用名"
            }
            toolName == "swipe_up" || toolName == "swipe_down" -> {
                "滑动后可能需要等待页面加载，建议调用 wait 等待 1-2 秒后再继续操作"
            }
            else -> {
                "操作失败，建议重试或尝试其他方案"
            }
        }
    }
}
