package com.zigent.agent

import android.content.Context
import com.zigent.accessibility.NodeInfo
import com.zigent.accessibility.ZigentAccessibilityService
import com.zigent.adb.AdbManager
import com.zigent.agent.models.ElementBounds
import com.zigent.agent.models.ScreenState
import com.zigent.agent.models.UiElement
import com.zigent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 屏幕分析器
 * 负责采集和分析当前屏幕状态
 */
@Singleton
class ScreenAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adbManager: AdbManager
) {
    companion object {
        private const val TAG = "ScreenAnalyzer"
    }

    /**
     * 获取当前屏幕状态
     * 优先使用无障碍服务，备选使用ADB
     * 使用并行操作提高效率
     */
    suspend fun captureScreenState(): ScreenState = withContext(Dispatchers.IO) {
        coroutineScope {
            // 并行获取各项数据
            val packageNameDeferred = async { getPackageName() }
            val activityNameDeferred = async { getActivityName() }
            val uiElementsDeferred = async { captureUiElements() }
            val screenshotDeferred = async { captureScreenshotBase64() }
            
            // 等待所有结果
            val packageName = packageNameDeferred.await()
            val activityName = activityNameDeferred.await()
            val uiElements = uiElementsDeferred.await()
            val screenshotBase64 = screenshotDeferred.await()
            
            // 生成屏幕描述
            val description = generateScreenDescription(packageName, activityName, uiElements)
            
            ScreenState(
                packageName = packageName,
                activityName = activityName,
                screenDescription = description,
                uiElements = uiElements,
                screenshotBase64 = screenshotBase64
            )
        }
    }

    /**
     * 获取当前包名
     */
    private suspend fun getPackageName(): String {
        // 优先从无障碍服务获取
        ZigentAccessibilityService.instance?.let { service ->
            val pkg = service.getCurrentPackageName()
            if (pkg.isNotEmpty()) return pkg
        }
        
        // 备选：从ADB获取
        return adbManager.getCurrentPackage() ?: "unknown"
    }

    /**
     * 获取当前Activity
     */
    private suspend fun getActivityName(): String? {
        return adbManager.getCurrentActivity()
    }

    /**
     * 采集UI元素
     */
    private suspend fun captureUiElements(): List<UiElement> {
        // 优先从无障碍服务获取
        ZigentAccessibilityService.instance?.let { service ->
            val nodes = service.getScreenNodes()
            if (nodes.isNotEmpty()) {
                return convertNodesToElements(nodes)
            }
        }
        
        // 备选：从ADB UI dump获取
        val uiDump = adbManager.dumpUiHierarchy()
        if (!uiDump.isNullOrEmpty()) {
            return parseUiDump(uiDump)
        }
        
        return emptyList()
    }

    /**
     * 将无障碍节点转换为UI元素
     */
    private fun convertNodesToElements(nodes: List<NodeInfo>): List<UiElement> {
        var elementId = 0
        return nodes
            .filter { it.bounds.width() > 0 && it.bounds.height() > 0 }
            .filter { it.isClickable || it.isEditable || it.text.isNotEmpty() || it.contentDescription.isNotEmpty() }
            .map { node ->
                UiElement(
                    id = "elem_${elementId++}",
                    type = node.className.substringAfterLast("."),
                    text = node.text,
                    description = node.contentDescription,
                    bounds = ElementBounds(
                        left = node.bounds.left,
                        top = node.bounds.top,
                        right = node.bounds.right,
                        bottom = node.bounds.bottom
                    ),
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isFocused = node.isFocused
                )
            }
    }

    /**
     * 解析UI Automator dump的XML
     */
    private fun parseUiDump(xmlContent: String): List<UiElement> {
        val elements = mutableListOf<UiElement>()
        var elementId = 0
        
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))
            
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "node") {
                    val className = parser.getAttributeValue(null, "class") ?: ""
                    val text = parser.getAttributeValue(null, "text") ?: ""
                    val contentDesc = parser.getAttributeValue(null, "content-desc") ?: ""
                    val boundsStr = parser.getAttributeValue(null, "bounds") ?: "[0,0][0,0]"
                    val clickable = parser.getAttributeValue(null, "clickable") == "true"
                    val editable = parser.getAttributeValue(null, "editable") == "true"
                    val scrollable = parser.getAttributeValue(null, "scrollable") == "true"
                    val focused = parser.getAttributeValue(null, "focused") == "true"
                    
                    // 解析bounds: [left,top][right,bottom]
                    val bounds = parseBounds(boundsStr)
                    
                    if (bounds.width > 0 && bounds.height > 0) {
                        if (clickable || editable || text.isNotEmpty() || contentDesc.isNotEmpty()) {
                            elements.add(
                                UiElement(
                                    id = "elem_${elementId++}",
                                    type = className.substringAfterLast("."),
                                    text = text,
                                    description = contentDesc,
                                    bounds = bounds,
                                    isClickable = clickable,
                                    isEditable = editable,
                                    isScrollable = scrollable,
                                    isFocused = focused
                                )
                            )
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Logger.e("Failed to parse UI dump", e, TAG)
        }
        
        return elements
    }

    /**
     * 解析bounds字符串
     */
    private fun parseBounds(boundsStr: String): ElementBounds {
        try {
            val regex = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
            val match = regex.find(boundsStr)
            if (match != null) {
                val (left, top, right, bottom) = match.destructured
                return ElementBounds(
                    left = left.toInt(),
                    top = top.toInt(),
                    right = right.toInt(),
                    bottom = bottom.toInt()
                )
            }
        } catch (e: Exception) {
            Logger.e("Failed to parse bounds: $boundsStr", e, TAG)
        }
        return ElementBounds(0, 0, 0, 0)
    }

    /**
     * 获取屏幕截图的Base64编码
     */
    private suspend fun captureScreenshotBase64(): String? {
        return try {
            adbManager.takeScreenshotBase64()
        } catch (e: Exception) {
            Logger.e("Failed to capture screenshot", e, TAG)
            null
        }
    }

    /**
     * 生成屏幕描述（用于AI理解）
     */
    private fun generateScreenDescription(
        packageName: String,
        activityName: String?,
        elements: List<UiElement>
    ): String {
        val sb = StringBuilder()
        
        // 基本信息
        sb.appendLine("=== 当前屏幕 ===")
        sb.appendLine("应用: $packageName")
        activityName?.let { sb.appendLine("页面: $it") }
        sb.appendLine()
        
        // 可交互元素
        val clickableElements = elements.filter { it.isClickable }
        val editableElements = elements.filter { it.isEditable }
        val textElements = elements.filter { it.text.isNotEmpty() && !it.isClickable && !it.isEditable }
        
        if (clickableElements.isNotEmpty()) {
            sb.appendLine("=== 可点击元素 ===")
            clickableElements.forEachIndexed { index, elem ->
                val label = elem.text.ifEmpty { elem.description }.ifEmpty { elem.type }
                sb.appendLine("[$index] $label @(${elem.bounds.centerX}, ${elem.bounds.centerY})")
            }
            sb.appendLine()
        }
        
        if (editableElements.isNotEmpty()) {
            sb.appendLine("=== 输入框 ===")
            editableElements.forEachIndexed { index, elem ->
                val hint = elem.description.ifEmpty { elem.text }.ifEmpty { "输入框" }
                sb.appendLine("[$index] $hint @(${elem.bounds.centerX}, ${elem.bounds.centerY})")
            }
            sb.appendLine()
        }
        
        if (textElements.isNotEmpty()) {
            sb.appendLine("=== 文本内容 ===")
            textElements.take(20).forEach { elem ->
                if (elem.text.length <= 100) {
                    sb.appendLine("- ${elem.text}")
                }
            }
        }
        
        return sb.toString()
    }

    /**
     * 生成简化的屏幕描述（用于AI Prompt）
     */
    fun generateCompactDescription(state: ScreenState): String {
        val sb = StringBuilder()
        
        sb.appendLine("当前应用: ${state.packageName}")
        state.activityName?.let { sb.appendLine("当前页面: $it") }
        sb.appendLine()
        
        sb.appendLine("屏幕元素:")
        state.uiElements.forEachIndexed { index, elem ->
            val type = when {
                elem.isEditable -> "[输入框]"
                elem.isClickable -> "[按钮]"
                elem.isScrollable -> "[列表]"
                else -> "[文本]"
            }
            val content = elem.text.ifEmpty { elem.description }.ifEmpty { elem.type }
            val coords = "(${elem.bounds.centerX},${elem.bounds.centerY})"
            sb.appendLine("$index. $type $content $coords")
        }
        
        return sb.toString()
    }

    /**
     * 根据描述查找元素
     */
    fun findElementByDescription(
        state: ScreenState,
        description: String
    ): UiElement? {
        val descLower = description.lowercase()
        
        // 精确匹配
        state.uiElements.find { 
            it.text.lowercase() == descLower || 
            it.description.lowercase() == descLower 
        }?.let { return it }
        
        // 包含匹配
        state.uiElements.find { 
            it.text.lowercase().contains(descLower) || 
            it.description.lowercase().contains(descLower) 
        }?.let { return it }
        
        return null
    }

    /**
     * 根据索引获取元素
     */
    fun getElementByIndex(state: ScreenState, index: Int): UiElement? {
        return state.uiElements.getOrNull(index)
    }
}

