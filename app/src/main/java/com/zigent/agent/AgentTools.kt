package com.zigent.agent

import com.zigent.ai.models.*

/**
 * Agent 工具定义
 * 定义所有可用的 Function Calling 工具
 * 
 * 工具分类：
 * 1. 触摸操作：tap, long_press, double_tap
 * 2. 滑动操作：swipe_up, swipe_down, swipe_left, swipe_right, swipe
 * 3. 滚动操作：scroll
 * 4. 输入操作：input_text, clear_text
 * 5. 按键操作：press_back, press_home, press_recent, press_enter
 * 6. 应用操作：open_app, close_app
 * 7. 等待操作：wait
 * 8. 任务状态：finished, failed, ask_user
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
你是Zigent，一个Android手机自动化助手。你的任务是根据用户需求和当前屏幕状态，调用合适的工具来完成操作。

## 核心规则

1. **每次只调用一个工具** - 不要尝试一次完成多步操作
2. **坐标必须精确** - 使用屏幕元素列表中提供的坐标
3. **输入前先聚焦** - 输入文字前，先用tap点击输入框
4. **找不到就滑动** - 如果目标元素不在屏幕上，用swipe_up/swipe_down查找
5. **等待加载完成** - 打开应用或新页面后，用wait等待加载
6. **明确完成状态** - 任务完成必须调用finished，失败调用failed
7. **不确定就询问** - 如果任务不明确，用ask_user询问用户

## 操作技巧

- 点击按钮：使用元素中心坐标
- 滑动浏览：swipe_up/swipe_down，distance=50
- 快速滑动：设置distance=80
- 轻微滑动：设置distance=20
- 等待加载：通常wait 2000ms即可
- 长按菜单：long_press，duration=800

## 常见场景

- 发送微信消息：open_app → tap搜索 → input_text联系人 → tap联系人 → tap输入框 → input_text消息 → tap发送 → finished
- 搜索内容：tap搜索框 → input_text关键词 → press_enter → wait → 处理结果
- 浏览列表：swipe_up多次查看更多内容
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
