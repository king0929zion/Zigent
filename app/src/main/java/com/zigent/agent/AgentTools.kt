package com.zigent.agent

import com.zigent.ai.models.*

/**
 * Agent 工具定义
 * 定义所有可用的 Function Calling 工具
 */
object AgentTools {

    /**
     * 所有可用的工具列表
     */
    val ALL_TOOLS: List<Tool> = listOf(
        // 点击操作
        Tool(function = FunctionDef(
            name = "tap",
            description = "点击屏幕指定坐标",
            parameters = FunctionParameters(
                properties = mapOf(
                    "x" to PropertyDef("integer", "横坐标"),
                    "y" to PropertyDef("integer", "纵坐标"),
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("x", "y", "description")
            )
        )),
        
        Tool(function = FunctionDef(
            name = "long_press",
            description = "长按屏幕指定坐标",
            parameters = FunctionParameters(
                properties = mapOf(
                    "x" to PropertyDef("integer", "横坐标"),
                    "y" to PropertyDef("integer", "纵坐标"),
                    "duration" to PropertyDef("integer", "长按时长(毫秒)，默认800"),
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("x", "y", "description")
            )
        )),
        
        Tool(function = FunctionDef(
            name = "double_tap",
            description = "双击屏幕指定坐标",
            parameters = FunctionParameters(
                properties = mapOf(
                    "x" to PropertyDef("integer", "横坐标"),
                    "y" to PropertyDef("integer", "纵坐标"),
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("x", "y", "description")
            )
        )),
        
        // 滑动操作
        Tool(function = FunctionDef(
            name = "swipe_up",
            description = "向上滑动屏幕",
            parameters = FunctionParameters(
                properties = mapOf(
                    "distance" to PropertyDef("integer", "滑动距离百分比(1-100)，默认50"),
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("description")
            )
        )),
        
        Tool(function = FunctionDef(
            name = "swipe_down",
            description = "向下滑动屏幕",
            parameters = FunctionParameters(
                properties = mapOf(
                    "distance" to PropertyDef("integer", "滑动距离百分比(1-100)，默认50"),
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("description")
            )
        )),
        
        Tool(function = FunctionDef(
            name = "swipe_left",
            description = "向左滑动屏幕",
            parameters = FunctionParameters(
                properties = mapOf(
                    "distance" to PropertyDef("integer", "滑动距离百分比(1-100)，默认30"),
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("description")
            )
        )),
        
        Tool(function = FunctionDef(
            name = "swipe_right",
            description = "向右滑动屏幕",
            parameters = FunctionParameters(
                properties = mapOf(
                    "distance" to PropertyDef("integer", "滑动距离百分比(1-100)，默认30"),
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("description")
            )
        )),
        
        Tool(function = FunctionDef(
            name = "swipe",
            description = "自定义滑动，从起点滑动到终点",
            parameters = FunctionParameters(
                properties = mapOf(
                    "start_x" to PropertyDef("integer", "起点横坐标"),
                    "start_y" to PropertyDef("integer", "起点纵坐标"),
                    "end_x" to PropertyDef("integer", "终点横坐标"),
                    "end_y" to PropertyDef("integer", "终点纵坐标"),
                    "duration" to PropertyDef("integer", "滑动时长(毫秒)，默认300"),
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("start_x", "start_y", "end_x", "end_y", "description")
            )
        )),
        
        // 输入操作
        Tool(function = FunctionDef(
            name = "input_text",
            description = "在当前输入框输入文字",
            parameters = FunctionParameters(
                properties = mapOf(
                    "text" to PropertyDef("string", "要输入的文字"),
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("text", "description")
            )
        )),
        
        Tool(function = FunctionDef(
            name = "clear_text",
            description = "清空当前输入框",
            parameters = FunctionParameters(
                properties = mapOf(
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("description")
            )
        )),
        
        // 按键操作
        Tool(function = FunctionDef(
            name = "press_back",
            description = "按返回键",
            parameters = FunctionParameters(
                properties = mapOf(
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("description")
            )
        )),
        
        Tool(function = FunctionDef(
            name = "press_home",
            description = "按主页键，回到桌面",
            parameters = FunctionParameters(
                properties = mapOf(
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("description")
            )
        )),
        
        Tool(function = FunctionDef(
            name = "press_recent",
            description = "打开最近任务",
            parameters = FunctionParameters(
                properties = mapOf(
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("description")
            )
        )),
        
        // 应用操作
        Tool(function = FunctionDef(
            name = "open_app",
            description = "打开指定应用",
            parameters = FunctionParameters(
                properties = mapOf(
                    "app" to PropertyDef("string", "应用名称，如：微信、支付宝、抖音等"),
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("app", "description")
            )
        )),
        
        Tool(function = FunctionDef(
            name = "close_app",
            description = "关闭指定应用",
            parameters = FunctionParameters(
                properties = mapOf(
                    "app" to PropertyDef("string", "应用名称"),
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("app", "description")
            )
        )),
        
        // 等待操作
        Tool(function = FunctionDef(
            name = "wait",
            description = "等待指定时间",
            parameters = FunctionParameters(
                properties = mapOf(
                    "time" to PropertyDef("integer", "等待时间(毫秒)，默认2000"),
                    "description" to PropertyDef("string", "操作描述")
                ),
                required = listOf("description")
            )
        )),
        
        // 任务状态
        Tool(function = FunctionDef(
            name = "finished",
            description = "任务已完成",
            parameters = FunctionParameters(
                properties = mapOf(
                    "message" to PropertyDef("string", "完成说明")
                ),
                required = listOf("message")
            )
        )),
        
        Tool(function = FunctionDef(
            name = "failed",
            description = "任务失败，无法继续",
            parameters = FunctionParameters(
                properties = mapOf(
                    "message" to PropertyDef("string", "失败原因")
                ),
                required = listOf("message")
            )
        )),
        
        Tool(function = FunctionDef(
            name = "ask_user",
            description = "需要询问用户获取更多信息",
            parameters = FunctionParameters(
                properties = mapOf(
                    "question" to PropertyDef("string", "要问用户的问题")
                ),
                required = listOf("question")
            )
        ))
    )

    /**
     * 系统提示词（简化版，因为工具已经自带描述）
     */
    val SYSTEM_PROMPT = """
你是Zigent，一个Android手机自动化助手。根据用户任务和当前屏幕状态，调用合适的工具执行操作。

规则：
1. 每次只调用一个工具
2. 点击坐标必须精确，使用元素中心点
3. 输入文字前先点击输入框
4. 找不到元素就滑动查找
5. 任务完成必须调用 finished
6. 不确定就用 ask_user
""".trimIndent()
}

