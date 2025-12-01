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
你是Zigent，一个专业的Android手机自动化助手。你可以看到手机屏幕并执行各种操作来帮助用户完成任务。

## 核心能力
1. 理解用户意图并制定执行计划
2. 分析屏幕截图识别UI元素
3. 执行精确的触摸和手势操作
4. 智能处理异常情况

## 可用操作工具

### 触摸操作
- TAP: 点击 {"action":"TAP","x":540,"y":1200,"description":"点击登录按钮"}
- DOUBLE_TAP: 双击 {"action":"DOUBLE_TAP","x":540,"y":1200,"description":"双击放大图片"}
- LONG_PRESS: 长按 {"action":"LONG_PRESS","x":540,"y":1200,"duration":800,"description":"长按消息复制"}

### 滑动操作
- SWIPE_UP: 上滑 {"action":"SWIPE_UP","distance":50,"description":"上滑查看更多"}
- SWIPE_DOWN: 下滑 {"action":"SWIPE_DOWN","distance":50,"description":"下滑刷新页面"}
- SWIPE_LEFT: 左滑 {"action":"SWIPE_LEFT","distance":30,"description":"左滑删除"}
- SWIPE_RIGHT: 右滑 {"action":"SWIPE_RIGHT","distance":30,"description":"右滑返回"}
- SWIPE: 自定义滑动 {"action":"SWIPE","startX":100,"startY":500,"endX":900,"endY":500,"duration":300,"description":"滑动解锁"}

### 滚动操作
- SCROLL: 滚动 {"action":"SCROLL","direction":"DOWN","count":3,"description":"向下滚动3次"}
- SCROLL_TO_TOP: 滚动到顶部 {"action":"SCROLL_TO_TOP","description":"返回顶部"}
- SCROLL_TO_BOTTOM: 滚动到底部 {"action":"SCROLL_TO_BOTTOM","description":"滚动到底部"}

### 输入操作
- INPUT_TEXT: 输入文字 {"action":"INPUT_TEXT","text":"要输入的内容","x":540,"y":800,"description":"输入搜索关键词"}
- CLEAR_TEXT: 清空输入框 {"action":"CLEAR_TEXT","description":"清空当前输入"}

### 按键操作
- PRESS_BACK: 返回 {"action":"PRESS_BACK","description":"返回上一页"}
- PRESS_HOME: 回到主页 {"action":"PRESS_HOME","description":"回到桌面"}
- PRESS_RECENT: 最近任务 {"action":"PRESS_RECENT","description":"打开最近任务"}
- PRESS_KEY: 按键 {"action":"PRESS_KEY","key":"ENTER","description":"按确认键"}

### 应用操作
- OPEN_APP: 打开应用 {"action":"OPEN_APP","app":"微信","description":"打开微信"}
- CLOSE_APP: 关闭应用 {"action":"CLOSE_APP","app":"微信","description":"关闭微信"}
- OPEN_URL: 打开网址 {"action":"OPEN_URL","url":"https://example.com","description":"打开网页"}
- OPEN_SETTINGS: 打开设置 {"action":"OPEN_SETTINGS","setting":"wifi","description":"打开WiFi设置"}

### 通知操作
- OPEN_NOTIFICATION: 打开通知栏 {"action":"OPEN_NOTIFICATION","description":"下拉通知栏"}
- CLEAR_NOTIFICATION: 清除通知 {"action":"CLEAR_NOTIFICATION","description":"清除所有通知"}

### 等待操作
- WAIT: 等待 {"action":"WAIT","time":2000,"description":"等待页面加载"}
- WAIT_FOR_ELEMENT: 等待元素 {"action":"WAIT_FOR_ELEMENT","text":"加载完成","timeout":10000,"description":"等待加载完成"}

### 任务状态
- FINISHED: 完成 {"action":"FINISHED","message":"已成功发送消息"}
- FAILED: 失败 {"action":"FAILED","message":"未找到联系人"}
- ASK_USER: 询问用户 {"action":"ASK_USER","question":"请确认要发送给哪位好友？"}

## 响应格式（严格JSON）
```json
{
    "thought": "分析当前屏幕状态，说明你看到了什么，以及为什么选择这个操作",
    "action": {操作JSON对象}
}
```

## 重要规则
1. 每次只执行一个操作，不要预测后续操作
2. 点击坐标必须精确，使用元素的中心点
3. 如果找不到目标元素，先尝试滚动查找
4. 打开应用后需要等待加载完成
5. 输入文字前确保输入框已聚焦（先点击输入框）
6. 连续3次相同操作失败则返回FAILED
7. 任务完成后必须返回FINISHED，说明结果
8. 不确定时使用ASK_USER询问用户
9. 只输出JSON，不要有其他内容
10. 保持耐心，复杂任务可能需要多步骤完成

## 常用应用识别
- 微信、支付宝、淘宝、抖音、快手
- 美团、饿了么、京东、拼多多
- 高德地图、百度地图、网易云音乐
- QQ、微博、小红书、哔哩哔哩
- 钉钉、飞书、设置、相机、相册
""".trimIndent()

    /**
     * 简单对话系统提示词（不需要屏幕操作时使用）
     */
    val SIMPLE_CHAT_PROMPT = """
你是Zigent，一个友好的AI助手。用户可能会问你各种问题，请用简洁、有帮助的方式回答。

如果用户请求需要操作手机（如打开应用、发送消息等），请告诉用户你可以帮忙执行这些操作。

回答要求：
1. 简洁明了，不要太长
2. 友好自然的语气
3. 如果是手机操作请求，说明你会帮忙执行
4. 如果不确定，可以询问用户
""".trimIndent()

    /**
     * 构建任务分析Prompt
     */
    fun buildTaskAnalysisPrompt(userInput: String): String {
        return """
分析用户需求并制定执行计划：

用户需求：$userInput

请分析这个任务需要：
1. 是否需要打开某个应用？哪个应用？
2. 主要的操作步骤是什么？
3. 可能遇到的问题和处理方式？

以JSON格式返回：
```json
{
    "needsApp": true/false,
    "app": "需要打开的应用名",
    "steps": ["步骤1", "步骤2", "步骤3"],
    "potentialIssues": "可能的问题",
    "isSimpleChat": true/false  // 是否是简单对话，不需要操作手机
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
        
        sb.appendLine("## 用户任务")
        sb.appendLine(task)
        sb.appendLine()
        
        sb.appendLine("## 当前屏幕")
        sb.appendLine("应用: ${screenState.packageName}")
        screenState.activityName?.let { sb.appendLine("页面: $it") }
        sb.appendLine()
        
        sb.appendLine("## 可交互元素")
        if (screenState.uiElements.isEmpty()) {
            sb.appendLine("未检测到元素，请根据截图判断")
        } else {
            screenState.uiElements.take(20).forEachIndexed { index, elem ->
                val type = when {
                    elem.isEditable -> "📝"
                    elem.isClickable -> "👆"
                    elem.isScrollable -> "📜"
                    else -> "📄"
                }
                val content = elem.text.ifEmpty { elem.description }.ifEmpty { elem.type }.take(30)
                sb.appendLine("$index. $type \"$content\" @(${elem.bounds.centerX},${elem.bounds.centerY})")
            }
        }
        sb.appendLine()
        
        if (history.isNotEmpty()) {
            sb.appendLine("## 已执行操作")
            history.takeLast(5).forEach { step ->
                val status = if (step.success) "✓" else "✗"
                sb.appendLine("$status ${step.action.description}")
            }
            sb.appendLine()
        }
        
        sb.appendLine("请根据任务目标和当前屏幕，决定下一步操作。输出JSON格式。")
        
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
        
        sb.appendLine("## 用户任务")
        sb.appendLine(task)
        sb.appendLine()
        
        sb.appendLine("## 当前屏幕信息")
        sb.appendLine("应用: ${screenState.packageName}")
        screenState.activityName?.let { sb.appendLine("页面: $it") }
        sb.appendLine()
        
        sb.appendLine("上图是当前手机屏幕截图，请仔细观察。")
        sb.appendLine()
        
        // 提供元素坐标作为参考
        if (screenState.uiElements.isNotEmpty()) {
            sb.appendLine("## 检测到的元素（参考坐标）")
            screenState.uiElements.take(15).forEach { elem ->
                val content = elem.text.ifEmpty { elem.description }.take(25)
                if (content.isNotEmpty()) {
                    val clickable = if (elem.isClickable) "可点击" else ""
                    sb.appendLine("- \"$content\" @(${elem.bounds.centerX},${elem.bounds.centerY}) $clickable")
                }
            }
            sb.appendLine()
        }
        
        if (history.isNotEmpty()) {
            sb.appendLine("## 操作历史")
            history.takeLast(3).forEach { step ->
                val status = if (step.success) "成功" else "失败"
                sb.appendLine("- ${step.action.description} [$status]")
            }
            sb.appendLine()
        }
        
        sb.appendLine("分析屏幕并决定下一步操作，直接输出JSON。")
        
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
当前应用: ${screenState.packageName}

请分析失败原因并决定下一步：
1. 是否重试相同操作（可能坐标不准）
2. 尝试其他方式完成
3. 需要用户帮助（ASK_USER）
4. 放弃任务（FAILED）

输出JSON格式的操作指令。
""".trimIndent()
    }

    /**
     * 常用应用包名映射
     */
    val APP_PACKAGE_MAP = mapOf(
        // 社交通讯
        "微信" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "qq" to "com.tencent.mobileqq",
        "QQ" to "com.tencent.mobileqq",
        "钉钉" to "com.alibaba.android.rimet",
        "飞书" to "com.ss.android.lark",
        "企业微信" to "com.tencent.wework",
        "telegram" to "org.telegram.messenger",
        
        // 支付购物
        "支付宝" to "com.eg.android.AlipayGphone",
        "alipay" to "com.eg.android.AlipayGphone",
        "淘宝" to "com.taobao.taobao",
        "taobao" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "闲鱼" to "com.taobao.idlefish",
        "美团" to "com.sankuai.meituan",
        "饿了么" to "me.ele",
        
        // 短视频社交
        "抖音" to "com.ss.android.ugc.aweme",
        "tiktok" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "微博" to "com.sina.weibo",
        "weibo" to "com.sina.weibo",
        "小红书" to "com.xingin.xhs",
        "bilibili" to "tv.danmaku.bili",
        "b站" to "tv.danmaku.bili",
        "哔哩哔哩" to "tv.danmaku.bili",
        
        // 地图出行
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "滴滴" to "com.sdu.didi.psnger",
        "携程" to "ctrip.android.view",
        
        // 音乐视频
        "网易云音乐" to "com.netease.cloudmusic",
        "qq音乐" to "com.tencent.qqmusic",
        "酷狗音乐" to "com.kugou.android",
        "酷我音乐" to "cn.kuwo.player",
        "喜马拉雅" to "com.ximalaya.ting.android",
        "腾讯视频" to "com.tencent.qqlive",
        "爱奇艺" to "com.qiyi.video",
        "优酷" to "com.youku.phone",
        
        // 系统应用
        "设置" to "com.android.settings",
        "settings" to "com.android.settings",
        "相机" to "com.android.camera",
        "camera" to "com.android.camera",
        "相册" to "com.android.gallery3d",
        "gallery" to "com.android.gallery3d",
        "photos" to "com.google.android.apps.photos",
        "浏览器" to "com.android.browser",
        "chrome" to "com.android.chrome",
        "电话" to "com.android.dialer",
        "短信" to "com.android.mms",
        "日历" to "com.android.calendar",
        "计算器" to "com.android.calculator2",
        "时钟" to "com.android.deskclock",
        "文件管理" to "com.android.fileexplorer",
        
        // 办公学习
        "wps" to "cn.wps.moffice_eng",
        "有道词典" to "com.youdao.dict",
        "百度网盘" to "com.baidu.netdisk",
        
        // 其他
        "keep" to "com.gotokeep.keep",
        "知乎" to "com.zhihu.android"
    )

    /**
     * 根据应用名获取包名
     */
    fun getPackageName(appName: String): String? {
        val nameLower = appName.lowercase().trim()
        
        // 精确匹配
        APP_PACKAGE_MAP[appName]?.let { return it }
        APP_PACKAGE_MAP[nameLower]?.let { return it }
        
        // 模糊匹配
        return APP_PACKAGE_MAP.entries.find { (key, _) ->
            key.lowercase() == nameLower || 
            nameLower.contains(key.lowercase()) ||
            key.lowercase().contains(nameLower)
        }?.value
    }

    /**
     * 根据包名获取应用名
     */
    fun getAppName(packageName: String): String? {
        return APP_PACKAGE_MAP.entries.find { it.value == packageName }?.key
    }
}
