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
     * 重点强调输出格式的规范性
     */
    val SYSTEM_PROMPT = """
你是Zigent，一个Android手机自动化助手。你可以看到屏幕并执行操作帮助用户完成任务。

## 【重要】响应格式要求
你必须且只能输出一个JSON对象，格式如下：
{"thought":"你的分析","action":{"action":"操作类型","其他参数":"值","description":"操作描述"}}

绝对不要输出其他任何内容，不要有解释文字，不要有```json标记，只输出纯JSON！

## 可用操作

### 点击类
{"action":"TAP","x":540,"y":1200,"description":"点击按钮"}
{"action":"LONG_PRESS","x":540,"y":1200,"duration":800,"description":"长按元素"}
{"action":"DOUBLE_TAP","x":540,"y":1200,"description":"双击元素"}

### 滑动类
{"action":"SWIPE_UP","description":"向上滑动"}
{"action":"SWIPE_DOWN","description":"向下滑动"}
{"action":"SWIPE_LEFT","description":"向左滑动"}
{"action":"SWIPE_RIGHT","description":"向右滑动"}
{"action":"SCROLL","direction":"DOWN","count":2,"description":"向下滚动2次"}

### 输入类
{"action":"INPUT_TEXT","text":"要输入的文字","description":"输入文字"}
{"action":"CLEAR_TEXT","description":"清空输入框"}

### 按键类
{"action":"PRESS_BACK","description":"返回"}
{"action":"PRESS_HOME","description":"回主页"}
{"action":"PRESS_RECENT","description":"最近任务"}

### 应用类
{"action":"OPEN_APP","app":"微信","description":"打开微信"}
{"action":"CLOSE_APP","app":"微信","description":"关闭微信"}

### 等待类
{"action":"WAIT","time":2000,"description":"等待2秒"}

### 任务结束
{"action":"FINISHED","message":"任务完成的描述"}
{"action":"FAILED","message":"失败原因"}
{"action":"ASK_USER","question":"需要问用户的问题"}

## 执行规则
1. 每次只返回一个操作
2. 坐标必须是数字，使用元素中心点
3. 输入文字前先点击输入框
4. 找不到元素就滑动查找
5. 任务完成必须返回FINISHED
6. 不确定就用ASK_USER
7. 响应必须是单行有效JSON
""".trimIndent()

    /**
     * 简单对话系统提示词
     */
    val SIMPLE_CHAT_PROMPT = """
你是Zigent，一个友好的AI助手。简洁回答用户问题。
如果用户需要操作手机，告诉他你可以帮忙执行。
回答简短，不超过100字。
""".trimIndent()

    /**
     * 构建任务分析Prompt
     */
    fun buildTaskAnalysisPrompt(userInput: String): String {
        return """
分析用户需求，返回JSON：
用户说：$userInput

只输出这个格式（不要其他内容）：
{"needsApp":true,"app":"应用名","steps":["步骤1","步骤2"],"isSimpleChat":false}

如果只是聊天不需要操作手机：
{"needsApp":false,"app":"","steps":[],"isSimpleChat":true}
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
        
        sb.appendLine("任务：$task")
        sb.appendLine()
        sb.appendLine("当前应用：${screenState.packageName}")
        screenState.activityName?.let { sb.appendLine("当前页面：$it") }
        sb.appendLine()
        
        // 简化元素列表
        if (screenState.uiElements.isNotEmpty()) {
            sb.appendLine("屏幕元素：")
            screenState.uiElements.take(15).forEachIndexed { index, elem ->
                val content = elem.text.ifEmpty { elem.description }.take(20)
                if (content.isNotEmpty() || elem.isClickable || elem.isEditable) {
                    val type = when {
                        elem.isEditable -> "[输入框]"
                        elem.isClickable -> "[可点击]"
                        else -> ""
                    }
                    sb.appendLine("$index.\"$content\" (${elem.bounds.centerX},${elem.bounds.centerY}) $type")
                }
            }
            sb.appendLine()
        }
        
        // 简化历史
        if (history.isNotEmpty()) {
            sb.appendLine("已执行：")
            history.takeLast(3).forEach { step ->
                val s = if (step.success) "✓" else "✗"
                sb.appendLine("$s ${step.action.type}: ${step.action.description}")
            }
            sb.appendLine()
        }
        
        sb.appendLine("返回下一步操作的JSON（只返回JSON，无其他内容）：")
        
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
        
        sb.appendLine("任务：$task")
        sb.appendLine("应用：${screenState.packageName}")
        sb.appendLine()
        
        sb.appendLine("这是当前屏幕截图，分析后返回下一步操作。")
        sb.appendLine()
        
        // 提供参考坐标
        if (screenState.uiElements.isNotEmpty()) {
            sb.appendLine("参考坐标：")
            screenState.uiElements.take(10).forEach { elem ->
                val content = elem.text.ifEmpty { elem.description }.take(15)
                if (content.isNotEmpty()) {
                    sb.appendLine("\"$content\" (${elem.bounds.centerX},${elem.bounds.centerY})")
                }
            }
            sb.appendLine()
        }
        
        if (history.isNotEmpty()) {
            sb.appendLine("已执行：${history.takeLast(2).joinToString(" → ") { it.action.type.name }}")
            sb.appendLine()
        }
        
        sb.appendLine("直接返回JSON操作指令：")
        
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
操作失败，请调整：
任务：$task
失败操作：${lastAction.type} - ${lastAction.description}
错误：$errorMessage
当前应用：${screenState.packageName}

选择：
1.调整坐标重试
2.尝试其他方式
3.{"action":"ASK_USER","question":"问题"}
4.{"action":"FAILED","message":"原因"}

返回JSON：
""".trimIndent()
    }

    /**
     * 验证JSON格式的辅助Prompt
     */
    val JSON_FORMAT_REMINDER = """
只返回JSON，格式：{"thought":"...","action":{...}}
""".trimIndent()

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
