package com.zigent.agent

import com.zigent.ai.models.*

/**
 * Agent 工具定义
 * 定义所有可用的 Function Calling 工具
 * 
 * 架构说明：
 * - 主 LLM (DeepSeek-V3.2-Exp)：任务理解 + Function Calling
 * - 辅助 VLM (Qwen3-Omni-Captioner)：图片描述（当调用 describe_screen 时）
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

    /**
     * 所有可用的工具列表
     */
    val ALL_TOOLS: List<Tool> = listOf(
        // ==================== 触摸操作 ====================
        createTool(
            name = "tap",
            description = "点击屏幕上的指定位置。用于点击按钮、链接、输入框等可点击元素。",
            properties = mapOf(
                "x" to intProperty("点击位置的横坐标（像素）"),
                "y" to intProperty("点击位置的纵坐标（像素）"),
                "description" to stringProperty("操作说明，描述点击的是什么元素")
            ),
            required = listOf("x", "y", "description")
        ),
        
        createTool(
            name = "long_press",
            description = "长按屏幕上的指定位置。用于触发长按菜单、拖拽开始等。",
            properties = mapOf(
                "x" to intProperty("长按位置的横坐标"),
                "y" to intProperty("长按位置的纵坐标"),
                "duration" to intProperty("长按时长（毫秒），默认800ms"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("x", "y", "description")
        ),
        
        createTool(
            name = "double_tap",
            description = "双击屏幕上的指定位置。用于放大图片、快速选择等。",
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
            description = "向上滑动屏幕。用于浏览更多内容、滚动页面、关闭应用等。",
            properties = mapOf(
                "distance" to intProperty("滑动距离，1-100表示屏幕百分比，默认50"),
                "speed" to enumProperty("滑动速度", listOf("slow", "normal", "fast")),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        createTool(
            name = "swipe_down",
            description = "向下滑动屏幕。用于刷新页面、下拉菜单、查看之前内容等。",
            properties = mapOf(
                "distance" to intProperty("滑动距离，1-100表示屏幕百分比，默认50"),
                "speed" to enumProperty("滑动速度", listOf("slow", "normal", "fast")),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        createTool(
            name = "swipe_left",
            description = "向左滑动屏幕。用于切换标签页、查看下一张图片、滑动删除等。",
            properties = mapOf(
                "distance" to intProperty("滑动距离，1-100表示屏幕百分比，默认30"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        createTool(
            name = "swipe_right",
            description = "向右滑动屏幕。用于返回上一页、查看上一张图片等。",
            properties = mapOf(
                "distance" to intProperty("滑动距离，1-100表示屏幕百分比，默认30"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        createTool(
            name = "swipe",
            description = "自定义滑动，从起点滑动到终点。用于精确控制滑动轨迹。",
            properties = mapOf(
                "start_x" to intProperty("起点横坐标"),
                "start_y" to intProperty("起点纵坐标"),
                "end_x" to intProperty("终点横坐标"),
                "end_y" to intProperty("终点纵坐标"),
                "duration" to intProperty("滑动时长（毫秒），默认300"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("start_x", "start_y", "end_x", "end_y", "description")
        ),
        
        // ==================== 滚动操作 ====================
        createTool(
            name = "scroll",
            description = "在可滚动区域内滚动。用于在列表、网页等中查找内容。",
            properties = mapOf(
                "direction" to enumProperty("滚动方向", listOf("up", "down", "left", "right")),
                "count" to intProperty("滚动次数，默认1"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("direction", "description")
        ),
        
        // ==================== 输入操作 ====================
        createTool(
            name = "input_text",
            description = "在当前聚焦的输入框中输入文字。如果需要先点击输入框，请先调用tap。",
            properties = mapOf(
                "text" to stringProperty("要输入的文字内容"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("text", "description")
        ),
        
        createTool(
            name = "clear_text",
            description = "清空当前输入框中的所有文字。",
            properties = mapOf(
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        // ==================== 按键操作 ====================
        createTool(
            name = "press_back",
            description = "按返回键。用于返回上一页、关闭弹窗、取消操作等。",
            properties = mapOf(
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        createTool(
            name = "press_home",
            description = "按主页键，回到手机桌面。",
            properties = mapOf(
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        createTool(
            name = "press_recent",
            description = "打开最近任务/应用切换界面。",
            properties = mapOf(
                "description" to stringProperty("操作说明")
            ),
            required = listOf("description")
        ),
        
        createTool(
            name = "press_enter",
            description = "按确认/回车键。用于提交表单、发送消息、确认输入等。",
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
                重要：必须使用已安装应用列表中显示的完整应用名称（第一列）。
                如果用户说的是别名，请智能匹配：
                - 谷歌笔记 → Google Keep/Keep记事本
                - 浏览器 → Chrome/谷歌浏览器
                - 油管 → YouTube
                - 微信 → 微信
                一定要在已安装应用列表中找到匹配的应用名。
            """.trimIndent(),
            properties = mapOf(
                "app" to stringProperty("应用名称，必须与已安装应用列表中的名称完全一致，如：Google Keep、Chrome、微信等"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("app", "description")
        ),
        
        createTool(
            name = "close_app",
            description = "强制关闭指定的应用程序。",
            properties = mapOf(
                "app" to stringProperty("应用名称"),
                "description" to stringProperty("操作说明")
            ),
            required = listOf("app", "description")
        ),
        
        // ==================== 视觉操作 ====================
        createTool(
            name = "describe_screen",
            description = "获取当前屏幕截图的详细描述。当屏幕元素列表不够详细，需要看到实际界面内容时调用。VLM会分析截图并返回详细描述。",
            properties = mapOf(
                "focus" to stringProperty("希望重点关注的内容，如：图片内容、验证码、具体位置等"),
                "description" to stringProperty("为什么需要查看截图")
            ),
            required = listOf("description")
        ),
        
        // ==================== 等待操作 ====================
        createTool(
            name = "wait",
            description = "等待指定时间。用于等待页面加载、动画完成、网络请求等。",
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
            description = "标记任务已成功完成。当所有步骤都执行完毕且达到目标时调用。",
            properties = mapOf(
                "message" to stringProperty("完成说明，描述做了什么、结果是什么"),
                "summary" to stringProperty("任务执行摘要")
            ),
            required = listOf("message")
        ),
        
        createTool(
            name = "failed",
            description = "标记任务失败，无法继续执行。当遇到无法解决的问题时调用。",
            properties = mapOf(
                "message" to stringProperty("失败原因，详细说明为什么无法完成"),
                "suggestion" to stringProperty("建议用户如何手动完成")
            ),
            required = listOf("message")
        ),
        
        createTool(
            name = "ask_user",
            description = "需要用户提供更多信息才能继续。当任务描述不清晰或需要确认时调用。",
            properties = mapOf(
                "question" to stringProperty("要问用户的具体问题"),
                "options" to stringProperty("可选的回答选项，用逗号分隔")
            ),
            required = listOf("question")
        )
    )

    /**
     * 系统提示词
     * 双模型架构：LLM (DeepSeek-V3.2) + VLM (Qwen3-Omni-Captioner)
     */
    val SYSTEM_PROMPT = """
你是Zigent，一个智能的Android手机自动化助手。你通过Function Calling控制手机。

## 核心能力

1. **智能推理**：当用户说的不是一个标准应用名，你需要优先从已有应用中思考里面有没有用户说的应用，比如谷歌笔记是Google Keep
2. **任务规划**：在执行任务前，先分析任务目标，规划执行步骤
3. **多样尝试**：如果一种方法失败，尝试其他可能的方法，不要轻易放弃
4. **错误重试**：同一操作失败后，可以调整参数再次尝试，最多3次

## 可用工具列表

### 触摸操作
- `tap(x, y, description)` - 点击指定坐标
- `long_press(x, y, duration, description)` - 长按（默认800ms）
- `double_tap(x, y, description)` - 双击

### 滑动操作
- `swipe_up(distance, description)` - 向上滑动（查看更多内容）
- `swipe_down(distance, description)` - 向下滑动（刷新/查看之前内容）
- `swipe_left(distance, description)` - 向左滑动
- `swipe_right(distance, description)` - 向右滑动
- `scroll(direction, count, description)` - 滚动列表

### 输入操作
- `input_text(text, description)` - 输入文字（需先点击输入框）
- `clear_text(description)` - 清空输入框

### 按键操作
- `press_back(description)` - 返回键
- `press_home(description)` - 主页键
- `press_recent(description)` - 最近任务键
- `press_enter(description)` - 回车/确认键

### 应用操作
- `open_app(app, description)` - 打开应用（使用已安装应用列表中的完整名称）
- `close_app(app, description)` - 关闭应用

### 视觉操作
- `describe_screen(focus, description)` - 获取屏幕截图描述（**仅在需要识别图片/验证码时使用，不能连续调用**）

### 等待操作
- `wait(time, reason, description)` - 等待（毫秒）

### 任务状态
- `finished(message)` - 任务完成
- `failed(message)` - 任务失败
- `ask_user(question)` - 询问用户

## 屏幕元素格式

🔘 "按钮文字" (x, y) → 可点击
📝 "提示文字" (x, y) → 输入框
📜 "区域" (x, y) → 可滚动
📄 "文本" (x, y) → 只读

## 关键规则

1. **任务规划优先**：先在 thought 中分析任务/规划步骤，按规划执行，勿忘已完成的步骤。
2. **智能匹配应用**：用已安装应用列表中的完整名称；别名匹配后务必使用完整名（如“谷歌笔记”→“Google Keep”）。
3. **坐标与元素**：使用元素列表坐标；输入前先点击输入框（📝）；找不到就用 swipe_up/down 查找。
4. **一次一个工具**：每次只调用一个工具，避免并发操作。
5. **错误后重试**：失败后可调整参数/换方法，最多 3 次。
6. **视觉兜底**：元素列表为空或应用（如微信）不可抓取时，立即调用 describe_screen 用视觉理解后再决策；describe_screen 不能连续调用。
7. **应用存在性**：如果刚用 open_app 成功，后续不要声称未安装；可通过当前前台包名验证。
8. **及时完成**：目标达成后立刻调用 finished。

## 任务执行流程

1. **分析阶段**：在thought中思考：
   - 任务目标是什么？
   - 需要哪些步骤？
   - 当前处于哪一步？
   - 下一步应该做什么？

2. **执行阶段**：执行当前步骤的操作

3. **验证阶段**：检查操作是否成功，失败则重试或调整

## 任务完成判断

调用 `finished`：
- 应用已打开
- 操作已执行完毕
- 搜索/发送成功

调用 `failed`：
- 元素找不到且滑动多次仍找不到
- 应用未安装
- 操作被拒绝
- 尝试多种方法后仍然失败

## 示例

任务：打开谷歌笔记
thought: 用户说的是谷歌笔记，在已安装应用列表中查找，发现有"Google Keep"，这就是谷歌笔记
→ open_app(app="Google Keep", description="打开谷歌笔记应用")
屏幕变为Google Keep主页
→ finished(message="已成功打开Google Keep（谷歌笔记）")

任务：搜索天气
thought: 需要：1.找到搜索按钮 2.点击搜索 3.输入天气 4.确认搜索。当前步骤：找搜索按钮
🔘 "搜索" (540, 120)
→ tap(x=540, y=120, description="点击搜索按钮")
thought: 搜索框已打开，现在输入天气
📝 "搜索框" (540, 200)
→ input_text(text="天气", description="输入搜索关键词")
thought: 已输入天气，现在按回车搜索
→ press_enter(description="确认搜索")
结果显示
→ finished(message="已搜索天气，显示搜索结果")

任务：打开微信失败后的重试
thought: 第一次尝试失败，可能是应用名不对，在列表中再次查找，确认是"微信"
→ open_app(app="微信", description="重试打开微信")
thought: 如果还是失败，尝试等待一下再打开
→ wait(time=2000, reason="等待系统响应", description="等待2秒")
→ open_app(app="微信", description="第三次尝试打开微信")
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
}
