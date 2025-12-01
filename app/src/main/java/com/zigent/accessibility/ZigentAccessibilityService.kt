package com.zigent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zigent.utils.Logger
import kotlinx.coroutines.*

/**
 * Zigent无障碍服务
 * 负责屏幕数据采集和自动化操作执行
 */
class ZigentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ZigentAccessibility"
        
        // 单例实例（便于外部调用）
        @Volatile
        var instance: ZigentAccessibilityService? = null
            private set
        
        /**
         * 检查服务是否可用
         */
        fun isServiceAvailable(): Boolean = instance != null
    }

    // 协程作用域
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // 当前窗口包名
    private var currentPackageName: String = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        Logger.i("AccessibilityService created", TAG)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
        Logger.i("AccessibilityService destroyed", TAG)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            // 记录当前窗口包名
            it.packageName?.toString()?.let { pkg ->
                if (pkg != currentPackageName) {
                    currentPackageName = pkg
                    Logger.d("Current package: $pkg", TAG)
                }
            }
        }
    }

    override fun onInterrupt() {
        Logger.w("AccessibilityService interrupted", TAG)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.i("AccessibilityService connected", TAG)
    }

    // ==================== 屏幕数据采集 ====================

    /**
     * 获取当前屏幕的UI树
     * @return UI节点列表
     */
    fun getScreenNodes(): List<NodeInfo> {
        val result = mutableListOf<NodeInfo>()
        try {
            val rootNode = rootInActiveWindow ?: return result
            traverseNode(rootNode, result, 0)
            rootNode.recycle()
        } catch (e: Exception) {
            Logger.e("Failed to get screen nodes", e, TAG)
        }
        return result
    }

    /**
     * 递归遍历UI节点树
     */
    private fun traverseNode(node: AccessibilityNodeInfo, result: MutableList<NodeInfo>, depth: Int) {
        try {
            // 获取节点边界
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            
            // 创建节点信息
            val nodeInfo = NodeInfo(
                className = node.className?.toString() ?: "",
                text = node.text?.toString() ?: "",
                contentDescription = node.contentDescription?.toString() ?: "",
                resourceId = node.viewIdResourceName ?: "",
                bounds = bounds,
                isClickable = node.isClickable,
                isLongClickable = node.isLongClickable,
                isScrollable = node.isScrollable,
                isEditable = node.isEditable,
                isEnabled = node.isEnabled,
                isChecked = node.isChecked,
                isFocused = node.isFocused,
                depth = depth
            )
            
            result.add(nodeInfo)
            
            // 遍历子节点
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    traverseNode(child, result, depth + 1)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Logger.e("Error traversing node", e, TAG)
        }
    }

    /**
     * 获取当前窗口包名
     */
    fun getCurrentPackageName(): String = currentPackageName

    /**
     * 获取当前Activity名称
     */
    fun getCurrentActivityName(): String {
        return try {
            rootInActiveWindow?.window?.let { window ->
                // 尝试获取Activity信息
                window.toString()
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // ==================== 操作执行 ====================

    /**
     * 执行点击操作
     * @param x X坐标
     * @param y Y坐标
     * @param callback 结果回调
     */
    fun performClick(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x, y)
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Logger.d("Click completed at ($x, $y)", TAG)
                    callback?.invoke(true)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Logger.w("Click cancelled at ($x, $y)", TAG)
                    callback?.invoke(false)
                }
            }, null)
        } else {
            callback?.invoke(false)
        }
    }

    /**
     * 执行滑动操作
     * @param startX 起始X
     * @param startY 起始Y
     * @param endX 结束X
     * @param endY 结束Y
     * @param duration 持续时间（毫秒）
     * @param callback 结果回调
     */
    fun performSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        duration: Long = 300,
        callback: ((Boolean) -> Unit)? = null
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Logger.d("Swipe completed from ($startX, $startY) to ($endX, $endY)", TAG)
                    callback?.invoke(true)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Logger.w("Swipe cancelled", TAG)
                    callback?.invoke(false)
                }
            }, null)
        } else {
            callback?.invoke(false)
        }
    }

    /**
     * 执行长按操作
     */
    fun performLongClick(x: Float, y: Float, duration: Long = 500, callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x, y)
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Logger.d("Long click completed at ($x, $y)", TAG)
                    callback?.invoke(true)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback?.invoke(false)
                }
            }, null)
        } else {
            callback?.invoke(false)
        }
    }

    /**
     * 执行全局返回操作
     */
    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * 执行全局Home操作
     */
    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * 执行全局最近任务操作
     */
    fun performRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    /**
     * 执行全局通知栏操作
     */
    fun performNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    /**
     * 在指定节点输入文本
     * @param nodeInfo 目标节点
     * @param text 要输入的文本
     */
    fun performSetText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val arguments = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            } else {
                false
            }
        } catch (e: Exception) {
            Logger.e("Failed to set text", e, TAG)
            false
        }
    }

    /**
     * 查找节点并输入文本
     * @param resourceId 资源ID
     * @param text 要输入的文本
     */
    fun findAndSetText(resourceId: String, text: String): Boolean {
        return try {
            val nodes = rootInActiveWindow?.findAccessibilityNodeInfosByViewId(resourceId)
            if (!nodes.isNullOrEmpty()) {
                val result = performSetText(nodes[0], text)
                nodes.forEach { it.recycle() }
                result
            } else {
                false
            }
        } catch (e: Exception) {
            Logger.e("Failed to find and set text", e, TAG)
            false
        }
    }

    /**
     * 通过文本查找并点击节点
     */
    fun findAndClickByText(text: String): Boolean {
        return try {
            val nodes = rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.isClickable) {
                        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        nodes.forEach { it.recycle() }
                        return result
                    }
                    // 尝试点击父节点
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            parent.recycle()
                            nodes.forEach { it.recycle() }
                            return result
                        }
                        val temp = parent
                        parent = parent.parent
                        temp.recycle()
                    }
                }
                nodes.forEach { it.recycle() }
            }
            false
        } catch (e: Exception) {
            Logger.e("Failed to find and click by text", e, TAG)
            false
        }
    }
}

/**
 * UI节点信息数据类
 */
data class NodeInfo(
    val className: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val isEnabled: Boolean,
    val isChecked: Boolean,
    val isFocused: Boolean,
    val depth: Int
) {
    /**
     * 获取节点中心点坐标
     */
    fun getCenterPoint(): Pair<Float, Float> {
        return Pair(bounds.exactCenterX(), bounds.exactCenterY())
    }
    
    /**
     * 转换为可读字符串（用于AI理解）
     */
    fun toReadableString(): String {
        val parts = mutableListOf<String>()
        
        // 类型
        val simpleClassName = className.substringAfterLast(".")
        parts.add(simpleClassName)
        
        // 文本内容
        if (text.isNotEmpty()) {
            parts.add("text=\"$text\"")
        }
        
        // 描述
        if (contentDescription.isNotEmpty() && contentDescription != text) {
            parts.add("desc=\"$contentDescription\"")
        }
        
        // 可交互属性
        val actions = mutableListOf<String>()
        if (isClickable) actions.add("clickable")
        if (isEditable) actions.add("editable")
        if (isScrollable) actions.add("scrollable")
        if (actions.isNotEmpty()) {
            parts.add("[${actions.joinToString(",")}]")
        }
        
        // 位置
        parts.add("@(${bounds.left},${bounds.top},${bounds.right},${bounds.bottom})")
        
        return parts.joinToString(" ")
    }
}

