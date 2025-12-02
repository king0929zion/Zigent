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
            description = "打开指定的应用程序。支持常见应用名称如：微信、支付宝、抖音、淘宝、设置等。",
            properties = mapOf(
                "app" to stringProperty("应用名称，如：微信、支付宝、抖音、淘宝、京东、美团、设置等"),
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
     */
    val SYSTEM_PROMPT = """
你是Zigent，一个智能的Android手机自动化助手。

## 架构说明

你是主控制器（LLM），通过工具调用来操作手机。
当需要理解屏幕视觉内容时，可以调用 describe_screen 获取 VLM 的图片描述。

## 信息来源

1. **屏幕元素列表**（主要）- 提供可交互元素的坐标和属性
2. **VLM 图片描述**（按需）- 当需要理解视觉内容时调用 describe_screen

## 工具选择策略

### 情况1：任务明确，可以直接执行
直接调用操作工具：tap、input_text、swipe_up、open_app 等

### 情况2：需要看清屏幕内容
调用 describe_screen 获取 VLM 对截图的描述：
- 需要识别图片/图标内容
- 需要确认界面状态
- 屏幕元素列表信息不足

### 情况3：需要与用户确认
调用 ask_user 询问用户：
- 任务描述不清楚
- 需要用户选择
- 需要用户确认

### 情况4：任务完成或失败
- 成功完成 → 调用 finished
- 无法继续 → 调用 failed

## 操作指南

### 使用屏幕元素
屏幕元素格式：🔘 "文字" (x, y)
- 🔘 可点击  📝 可输入  📜 可滚动  📄 文本

### 点击操作
tap(x=540, y=120, description="点击搜索按钮")

### 输入文字
1. 确认输入框已聚焦（📝标记）
2. input_text(text="内容", description="说明")

### 滑动查找
- swipe_up：向上滑动，查看下方内容
- swipe_down：向下滑动，查看上方内容

### 查看屏幕
当需要理解屏幕视觉内容时：
describe_screen(focus="关注点", description="原因")

## 重要规则

1. **优先使用元素坐标** - 屏幕元素列表提供精确坐标
2. **需要视觉信息才调用 VLM** - 不要每步都调用
3. **坐标要准确** - 使用元素列表中的坐标
4. **不确定就问用户** - 用 ask_user 确认
5. **及时汇报完成** - 用 finished 告知结果

## 示例

用户："打开微信"
→ open_app(app="微信", description="打开微信应用")

屏幕元素显示搜索按钮在(540, 120)
→ tap(x=540, y=120, description="点击搜索按钮")

需要识别验证码图片
→ describe_screen(focus="验证码", description="需要识别验证码内容")

任务完成
→ finished(message="已成功打开微信")
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
