package com.zigent.agent

import com.zigent.agent.models.AgentAction
import com.zigent.agent.models.AgentStep
import com.zigent.agent.models.ScreenState

/**
 * Prompt构建器
 * 负责构建发送给AI的提示词
 */
object PromptBuilder {

    /**
     * 系统提示词 - 定义Agent的角色和能力
     */
    val SYSTEM_PROMPT = """
你是一个专业的Android手机自动化助手。你的任务是帮助用户完成手机上的各种操作。

## 你的能力
1. 分析屏幕截图和UI元素信息
2. 理解用户的任务需求
3. 规划执行步骤
4. 生成精确的操作指令

## 可用操作
- TAP: 点击指定坐标 {"action":"TAP","x":100,"y":200,"description":"点击xxx按钮"}
- LONG_PRESS: 长按 {"action":"LONG_PRESS","x":100,"y":200,"duration":500,"description":"长按xxx"}
- SWIPE: 滑动 {"action":"SWIPE","startX":100,"startY":500,"endX":100,"endY":200,"duration":300,"description":"向上滑动"}
- INPUT_TEXT: 输入文字 {"action":"INPUT_TEXT","text":"要输入的文字","description":"输入xxx"}
- PRESS_KEY: 按键 {"action":"PRESS_KEY","key":"BACK/HOME/ENTER","description":"按返回键"}
- OPEN_APP: 打开应用 {"action":"OPEN_APP","package":"com.xxx.xxx","description":"打开xxx应用"}
- SCROLL: 滚动 {"action":"SCROLL","direction":"UP/DOWN/LEFT/RIGHT","description":"向下滚动查找"}
- WAIT: 等待 {"action":"WAIT","time":1000,"description":"等待页面加载"}
- FINISHED: 任务完成 {"action":"FINISHED","message":"完成描述"}
- FAILED: 任务失败 {"action":"FAILED","message":"失败原因"}

## 响应格式
你必须严格按照以下JSON格式响应：
```json
{
    "thought": "你的思考过程，分析当前屏幕状态和下一步应该做什么",
    "action": {操作对象}
}
```

## 重要规则
1. 每次只能执行一个操作
2. 必须基于当前屏幕状态做决策
3. 点击坐标必须精确，使用元素的中心点
4. 如果找不到目标元素，考虑滚动查找
5. 如果连续多次操作失败，返回FAILED
6. 任务完成后必须返回FINISHED
7. 只输出JSON，不要有其他内容
""".trimIndent()

    /**
     * 构建任务分析Prompt
     */
    fun buildTaskAnalysisPrompt(userInput: String): String {
        return """
分析用户需求并制定执行计划：

用户需求：$userInput

请分析这个任务需要：
1. 打开哪个应用
2. 完成哪些步骤
3. 需要注意什么

以JSON格式返回：
```json
{
    "app": "需要打开的应用名或包名",
    "steps": ["步骤1", "步骤2", "步骤3"],
    "notes": "注意事项"
}
```
""".trimIndent()
    }

    /**
     * 构建操作决策Prompt（纯文本版本）
     */
    fun buildActionPrompt(
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>
    ): String {
        val sb = StringBuilder()
        
        sb.appendLine("## 任务")
        sb.appendLine(task)
        sb.appendLine()
        
        sb.appendLine("## 当前屏幕状态")
        sb.appendLine("应用: ${screenState.packageName}")
        screenState.activityName?.let { sb.appendLine("页面: $it") }
        sb.appendLine()
        
        sb.appendLine("## 屏幕元素")
        screenState.uiElements.forEachIndexed { index, elem ->
            val type = when {
                elem.isEditable -> "[输入框]"
                elem.isClickable -> "[按钮]"
                elem.isScrollable -> "[列表]"
                else -> "[文本]"
            }
            val content = elem.text.ifEmpty { elem.description }.ifEmpty { elem.type }
            sb.appendLine("$index. $type \"$content\" 坐标(${elem.bounds.centerX},${elem.bounds.centerY})")
        }
        sb.appendLine()
        
        if (history.isNotEmpty()) {
            sb.appendLine("## 已执行的操作")
            history.takeLast(5).forEach { step ->
                val status = if (step.success) "成功" else "失败"
                sb.appendLine("- ${step.action.description} [$status]")
            }
            sb.appendLine()
        }
        
        sb.appendLine("## 请决定下一步操作")
        sb.appendLine("根据任务目标和当前屏幕状态，输出下一个操作的JSON。")
        
        return sb.toString()
    }

    /**
     * 构建带图片的操作决策Prompt
     */
    fun buildVisionActionPrompt(
        task: String,
        screenState: ScreenState,
        history: List<AgentStep>
    ): String {
        val sb = StringBuilder()
        
        sb.appendLine("## 任务")
        sb.appendLine(task)
        sb.appendLine()
        
        sb.appendLine("## 当前屏幕")
        sb.appendLine("应用: ${screenState.packageName}")
        screenState.activityName?.let { sb.appendLine("页面: $it") }
        sb.appendLine()
        
        sb.appendLine("图片是当前手机屏幕截图，请仔细观察。")
        sb.appendLine()
        
        // 提供元素坐标作为参考
        if (screenState.uiElements.isNotEmpty()) {
            sb.appendLine("## 检测到的可交互元素（参考）")
            screenState.uiElements.take(15).forEachIndexed { index, elem ->
                val content = elem.text.ifEmpty { elem.description }.take(30)
                if (content.isNotEmpty()) {
                    sb.appendLine("- \"$content\" @(${elem.bounds.centerX},${elem.bounds.centerY})")
                }
            }
            sb.appendLine()
        }
        
        if (history.isNotEmpty()) {
            sb.appendLine("## 操作历史")
            history.takeLast(3).forEach { step ->
                sb.appendLine("- ${step.action.description}")
            }
            sb.appendLine()
        }
        
        sb.appendLine("请分析屏幕并决定下一步操作，输出JSON格式的操作指令。")
        
        return sb.toString()
    }

    /**
     * 构建错误恢复Prompt
     */
    fun buildErrorRecoveryPrompt(
        task: String,
        lastAction: AgentAction,
        errorMessage: String,
        screenState: ScreenState
    ): String {
        return """
## 操作失败，需要恢复

任务: $task
上一个操作: ${lastAction.description}
错误信息: $errorMessage

当前屏幕:
${screenState.screenDescription}

请分析失败原因并决定:
1. 重试相同操作
2. 尝试其他方式
3. 放弃任务

输出下一步操作的JSON。
""".trimIndent()
    }

    /**
     * 常用应用包名映射
     */
    val APP_PACKAGE_MAP = mapOf(
        "微信" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "支付宝" to "com.eg.android.AlipayGphone",
        "alipay" to "com.eg.android.AlipayGphone",
        "淘宝" to "com.taobao.taobao",
        "taobao" to "com.taobao.taobao",
        "抖音" to "com.ss.android.ugc.aweme",
        "tiktok" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "bilibili" to "tv.danmaku.bili",
        "b站" to "tv.danmaku.bili",
        "哔哩哔哩" to "tv.danmaku.bili",
        "美团" to "com.sankuai.meituan",
        "饿了么" to "me.ele",
        "京东" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "网易云音乐" to "com.netease.cloudmusic",
        "qq音乐" to "com.tencent.qqmusic",
        "qq" to "com.tencent.mobileqq",
        "微博" to "com.sina.weibo",
        "小红书" to "com.xingin.xhs",
        "钉钉" to "com.alibaba.android.rimet",
        "飞书" to "com.ss.android.lark",
        "设置" to "com.android.settings",
        "相机" to "com.android.camera",
        "相册" to "com.android.gallery3d",
        "浏览器" to "com.android.browser",
        "chrome" to "com.android.chrome",
        "电话" to "com.android.dialer",
        "短信" to "com.android.mms",
        "日历" to "com.android.calendar",
        "计算器" to "com.android.calculator2",
        "时钟" to "com.android.deskclock"
    )

    /**
     * 根据应用名获取包名
     */
    fun getPackageName(appName: String): String? {
        val nameLower = appName.lowercase()
        return APP_PACKAGE_MAP.entries.find { (key, _) ->
            key.lowercase() == nameLower || nameLower.contains(key.lowercase())
        }?.value
    }
}

