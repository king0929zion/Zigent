package com.zigent.utils

/**
 * 应用工具类
 * 包含应用包名映射和相关工具方法
 */
object AppUtils {

    /**
     * 常用应用包名映射
     */
    private val APP_PACKAGE_MAP = mapOf(
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
     * 支持各种变体，如 "抖音"、"抖音app"、"打开抖音" 等
     * 
     * @param appName 应用名称
     * @param context 可选的 Context，用于从已安装应用中查找
     */
    fun getPackageName(appName: String, context: android.content.Context? = null): String? {
        // 清理应用名：移除常见修饰词
        val cleanName = appName
            .replace("打开", "")
            .replace("启动", "")
            .replace("应用", "")
            .replace("app", "")
            .replace("APP", "")
            .replace("软件", "")
            .lowercase()
            .trim()
        
        // 1. 精确匹配原始名称
        APP_PACKAGE_MAP[appName]?.let { return it }
        
        // 2. 精确匹配清理后的名称
        APP_PACKAGE_MAP[cleanName]?.let { return it }
        APP_PACKAGE_MAP.entries.find { it.key.lowercase() == cleanName }?.let { return it.value }
        
        // 3. 模糊匹配（包含关系）
        APP_PACKAGE_MAP.entries.find { (key, _) ->
            cleanName.contains(key.lowercase()) || key.lowercase().contains(cleanName)
        }?.let { return it.value }
        
        // 4. 别名匹配
        val aliases = mapOf(
            "dy" to "抖音",
            "douyin" to "抖音",
            "tiktok" to "抖音",
            "wx" to "微信",
            "weixin" to "微信",
            "wechat" to "微信",
            "zfb" to "支付宝",
            "zhifubao" to "支付宝",
            "tb" to "淘宝",
            "jd" to "京东",
            "mt" to "美团",
            "elm" to "饿了么",
            "bili" to "哔哩哔哩",
            "ks" to "快手"
        )
        aliases[cleanName]?.let { aliasName ->
            APP_PACKAGE_MAP[aliasName]?.let { return it }
        }
        
        // 5. 如果提供了 Context，从已安装应用中查找
        context?.let { ctx ->
            InstalledAppsHelper.findPackageByName(ctx, cleanName)?.let { return it }
        }
        
        return null
    }

    /**
     * 根据包名获取应用显示名称
     */
    fun getAppName(packageName: String): String {
        // 先从映射中查找
        APP_PACKAGE_MAP.entries.find { it.value == packageName }?.let {
            return it.key
        }
        
        // 根据包名推断
        val lowerPackage = packageName.lowercase()
        return when {
            lowerPackage.contains("wechat") || lowerPackage.contains("mm") -> "微信"
            lowerPackage.contains("alipay") -> "支付宝"
            lowerPackage.contains("taobao") -> "淘宝"
            lowerPackage.contains("jd") || lowerPackage.contains("jingdong") -> "京东"
            lowerPackage.contains("douyin") || lowerPackage.contains("tiktok") -> "抖音"
            lowerPackage.contains("kuaishou") -> "快手"
            lowerPackage.contains("bilibili") -> "B站"
            lowerPackage.contains("weibo") -> "微博"
            lowerPackage.contains("meituan") -> "美团"
            lowerPackage.contains("eleme") || lowerPackage.contains("ele") -> "饿了么"
            lowerPackage.contains("didi") -> "滴滴"
            lowerPackage.contains("baidu") -> "百度"
            lowerPackage.contains("qq") -> "QQ"
            lowerPackage.contains("chrome") -> "Chrome"
            lowerPackage.contains("settings") -> "设置"
            lowerPackage.contains("launcher") -> "桌面"
            lowerPackage.contains("dialer") || lowerPackage.contains("phone") -> "电话"
            lowerPackage.contains("contacts") -> "联系人"
            lowerPackage.contains("messaging") || lowerPackage.contains("mms") -> "短信"
            lowerPackage.contains("camera") -> "相机"
            lowerPackage.contains("gallery") || lowerPackage.contains("photos") -> "相册"
            lowerPackage.contains("calendar") -> "日历"
            lowerPackage.contains("clock") || lowerPackage.contains("alarm") -> "时钟"
            lowerPackage.contains("calculator") -> "计算器"
            lowerPackage.contains("filemanager") || lowerPackage.contains("files") -> "文件管理"
            else -> packageName.substringAfterLast(".")
        }
    }
}

