package com.panda.ai

//import android.graphics.drawable.GradientDrawable
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Xml
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlSerializer
import java.io.StringReader
import java.io.StringWriter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private data class SimplifiedElement(
    val description: String,
    val bounds: Rect,
    val center: Point,
    val isClickable: Boolean,
    val className: String
)

data class RawScreenData(
    val rootNode: AccessibilityNodeInfo?,
    val pixelsAbove: Int,
    val pixelsBelow: Int,
    val screenWidth: Int,
    val screenHeight: Int
)

class ScreenInteractionService : AccessibilityService() {

    companion object {
        var instance: ScreenInteractionService? = null
        const val DEBUG_SHOW_TAPS = true
        const val DEBUG_SHOW_BOUNDING_BOXES = false
    }

    private var windowManager: WindowManager? = null
    private var statusBarHeight = -1
    private var currentActivityName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        this.windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.d("InteractionService", "Accessibility Service connected.")
    }

    fun getForegroundAppPackageName(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    fun showDebugTap(tapX: Float, tapY: Float) {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val overlayView = ImageView(this)
        val tapIndicator = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x80FF0000.toInt())
            setSize(100, 100)
            setStroke(4, 0xFFFF0000.toInt())
        }
        overlayView.setImageDrawable(tapIndicator)
        val params = WindowManager.LayoutParams(
            100, 100,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = tapX.toInt() - 50
            y = tapY.toInt() - 50
        }
        Handler(Looper.getMainLooper()).post {
            try {
                windowManager.addView(overlayView, params)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (overlayView.isAttachedToWindow) windowManager.removeView(overlayView)
                }, 500L)
            } catch (e: Exception) {
                Log.e("InteractionService", "Failed to add debug tap view", e)
            }
        }
    }

    fun getWindowHierarchySignature(): String {
        val rootNode = rootInActiveWindow ?: return "null_root"
        val stringWriter = StringWriter()
        return try {
            val serializer: XmlSerializer = Xml.newSerializer()
            serializer.setOutput(stringWriter)
            serializer.startDocument("UTF-8", true)
            serializer.startTag(null, "hierarchy")
            dumpNode(rootNode, serializer, 0)
            serializer.endTag(null, "hierarchy")
            serializer.endDocument()
            stringWriter.toString()
        } catch (e: Exception) {
            Log.e("InteractionService", "Error generating signature", e)
            "error_generating_signature"
        }
    }

    suspend fun getAllScreenAnalysisData(): RawScreenData {
        val (screenWidth, screenHeight) = getScreenDimensions()
        val maxRetries = 5
        val retryDelay = 200L
        for (attempt in 1..maxRetries) {
            val allWindows = windows
            val targetWindow = allWindows
                .filter { it.type == TYPE_APPLICATION }
                .maxByOrNull {
                    val bounds = Rect()
                    it.getBoundsInScreen(bounds)
                    bounds.width() * bounds.height()
                }
            val rootNode = targetWindow?.root ?: rootInActiveWindow
            if (rootNode != null) {
                val (pixelsAbove, pixelsBelow) = findScrollableNodeAndGetInfo(rootNode)
                return RawScreenData(rootNode, pixelsAbove, pixelsBelow, screenWidth, screenHeight)
            }
            if (attempt < maxRetries) delay(retryDelay)
        }
        return RawScreenData(null, 0, 0, screenWidth, screenHeight)
    }

    suspend fun dumpWindowHierarchy(pureXML: Boolean = false): String {
        return withContext(Dispatchers.Default) {
            val rootNode = rootInActiveWindow ?: run {
                Log.e("InteractionService", "Root node is null.")
                return@withContext "Error: UI hierarchy is not available."
            }
            val stringWriter = StringWriter()
            try {
                val serializer: XmlSerializer = Xml.newSerializer()
                serializer.setOutput(stringWriter)
                serializer.startDocument("UTF-8", true)
                serializer.startTag(null, "hierarchy")
                dumpNode(rootNode, serializer, 0)
                serializer.endTag(null, "hierarchy")
                serializer.endDocument()
                val rawXml = stringWriter.toString()
                if (pureXML) return@withContext rawXml
                val simplifiedElements = parseXmlToSimplifiedElements(rawXml)
                return@withContext formatElementsForLlm(simplifiedElements)
            } catch (e: Exception) {
                Log.e("InteractionService", "Error dumping UI hierarchy", e)
                return@withContext "Error processing UI."
            }
        }
    }

    private fun parseXmlToSimplifiedElements(xmlString: String): List<SimplifiedElement> {
        val allElements = mutableListOf<SimplifiedElement>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(android.util.Xml.FEATURE_RELAXED, false)
            parser.setInput(StringReader(xmlString))
            var eventType = parser.eventType
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "node") {
                    val boundsString = parser.getAttributeValue(null, "bounds")
                    val bounds = try {
                        val numbers = boundsString?.replace(Regex("[\\[\\]]"), ",")?.split(",")?.filter { it.isNotEmpty() }
                        if (numbers?.size == 4) Rect(numbers[0].toInt(), numbers[1].toInt(), numbers[2].toInt(), numbers[3].toInt()) else Rect()
                    } catch (e: Exception) { Rect() }
                    if (bounds.width() <= 0 || bounds.height() <= 0) { eventType = parser.next(); continue }
                    val isClickable = parser.getAttributeValue(null, "clickable") == "true"
                    val text = parser.getAttributeValue(null, "text")
                    val contentDesc = parser.getAttributeValue(null, "content-desc")
                    val resourceId = parser.getAttributeValue(null, "resource-id")
                    val className = parser.getAttributeValue(null, "class") ?: "Element"
                    if (isClickable || !text.isNullOrEmpty() || (!contentDesc.isNullOrEmpty() && contentDesc != "null")) {
                        val description = when {
                            !contentDesc.isNullOrEmpty() && contentDesc != "null" -> contentDesc
                            !text.isNullOrEmpty() -> text
                            !resourceId.isNullOrEmpty() -> resourceId.substringAfterLast('/')
                            else -> ""
                        }
                        if (description.isNotEmpty()) {
                            allElements.add(SimplifiedElement(description, bounds, Point(bounds.centerX(), bounds.centerY()), isClickable, className))
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e("InteractionService", "Error parsing XML", e)
        }
        return allElements
    }

    private fun formatElementsForLlm(elements: List<SimplifiedElement>): String {
        if (elements.isEmpty()) return "No interactable or textual elements found on the screen."
        val elementStrings = elements.map {
            val action = if (it.isClickable) "Action: Clickable" else "Action: Not-Clickable (Text only)"
            val elementType = it.className.substringAfterLast('.')
            "- $elementType: \"${it.description}\" | $action | Center: (${it.center.x}, ${it.center.y})"
        }
        return "Interactable Screen Elements:\n" + elementStrings.joinToString("\n")
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, serializer: XmlSerializer, index: Int) {
        if (node == null) return
        serializer.startTag(null, "node")
        serializer.attribute(null, "index", index.toString())
        serializer.attribute(null, "text", node.text?.toString() ?: "")
        serializer.attribute(null, "resource-id", node.viewIdResourceName ?: "")
        serializer.attribute(null, "class", node.className?.toString() ?: "")
        serializer.attribute(null, "package", node.packageName?.toString() ?: "")
        serializer.attribute(null, "content-desc", node.contentDescription?.toString() ?: "")
        serializer.attribute(null, "checkable", node.isCheckable.toString())
        serializer.attribute(null, "checked", node.isChecked.toString())
        serializer.attribute(null, "clickable", node.isClickable.toString())
        serializer.attribute(null, "enabled", node.isEnabled.toString())
        serializer.attribute(null, "focusable", node.isFocusable.toString())
        serializer.attribute(null, "focused", node.isFocused.toString())
        serializer.attribute(null, "scrollable", node.isScrollable.toString())
        serializer.attribute(null, "long-clickable", node.isLongClickable.toString())
        serializer.attribute(null, "password", node.isPassword.toString())
        serializer.attribute(null, "selected", node.isSelected.toString())
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        serializer.attribute(null, "bounds", bounds.toShortString())
        for (i in 0 until node.childCount) dumpNode(node.getChild(i), serializer, i)
        serializer.endTag(null, "node")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            val className = event.className?.toString()
            if (!packageName.isNullOrBlank() && !className.isNullOrBlank()) {
                this.currentActivityName = ComponentName(packageName, className).flattenToString()
            }
        }
    }

    fun getCurrentActivityName(): String = this.currentActivityName ?: "Unknown"

    override fun onInterrupt() {
        Log.e("InteractionService", "Accessibility Service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d("InteractionService", "Accessibility Service destroyed.")
    }

    fun isTypingAvailable(): Boolean {
        val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return focusedNode != null && focusedNode.isEditable && focusedNode.isEnabled
    }

    fun clickOnPoint(x: Float, y: Float) {
        if (DEBUG_SHOW_TAPS) showDebugTap(x, y)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 10))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun longClickOnPoint(x: Float, y: Float) {
        if (DEBUG_SHOW_TAPS) showDebugTap(x, y)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 2000L))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun scrollDownPrecisely(pixels: Int, pixelsPerSecond: Int = 1000) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val x = screenWidth / 2
        val y1 = (screenHeight * 0.8).toInt()
        val y2 = (y1 - pixels).coerceAtLeast(0)
        val distance = y1 - y2
        if (distance <= 0) return
        val duration = (distance.toFloat() / pixelsPerSecond * 1000).toInt()
        swipe(x.toFloat(), y1.toFloat(), x.toFloat(), y2.toFloat(), duration.toLong())
    }

    fun typeTextInFocusedField(textToType: String) {
        val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && focusedNode.isEditable) {
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } else {
            Log.e("InteractionService", "Could not find a focused editable field.")
        }
    }

    fun performBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    @RequiresApi(Build.VERSION_CODES.R)
    fun performEnter() {
        val rootNode = rootInActiveWindow ?: return
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        try {
            val supportedActions = focusedNode.actionList
            val imeAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
            if (supportedActions.contains(imeAction)) {
                val success = focusedNode.performAction(imeAction.id)
                if (success) return
            }
            val clickAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK
            if (supportedActions.contains(clickAction)) {
                focusedNode.performAction(clickAction.id)
            }
        } catch (e: Exception) {
            Log.e("InteractionService", "Exception while performing Enter", e)
        } finally {
            focusedNode.recycle()
        }
    }

    private fun findScrollableNodeAndGetInfo(rootNode: AccessibilityNodeInfo?): Pair<Int, Int> {
        if (rootNode == null) return Pair(0, 0)
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.addLast(rootNode)
        var bestNode: AccessibilityNodeInfo? = null
        var maxNodeSize = -1
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val size = rect.width() * rect.height()
                if (size > maxNodeSize) { maxNodeSize = size; bestNode = node }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
        }
        var pixelsAbove = 0
        var pixelsBelow = 0
        bestNode?.let {
            val rangeInfo = it.rangeInfo
            if (rangeInfo != null) {
                pixelsAbove = (rangeInfo.current - rangeInfo.min).toInt()
                pixelsBelow = (rangeInfo.max - rangeInfo.current).toInt()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pixelsAbove = 10
                pixelsBelow = 5
            }
            it.recycle()
        }
        return Pair(pixelsAbove, pixelsBelow)
    }

    private fun getScreenDimensions(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            Pair(metrics.bounds.width(), metrics.bounds.height())
        } else {
            val displayMetrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(displayMetrics)
            Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
    }

    suspend fun getScreenAnalysisData(): RawScreenData {
        val (screenWidth, screenHeight) = getScreenDimensions()
        val maxRetries = 5
        val retryDelay = 800L
        for (attempt in 1..maxRetries) {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                val (pixelsAbove, pixelsBelow) = findScrollableNodeAndGetInfo(rootNode)
                return RawScreenData(rootNode, pixelsAbove, pixelsBelow, screenWidth, screenHeight)
            }
            if (attempt < maxRetries) delay(retryDelay)
        }
        return RawScreenData(null, 0, 0, screenWidth, screenHeight)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun captureScreenshot(): Bitmap? {
        return try {
            suspendCancellableCoroutine { continuation ->
                val executor = ContextCompat.getMainExecutor(this)
                takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        if (hardwareBuffer == null) {
                            continuation.resumeWithException(Exception("Screenshot hardware buffer was null."))
                            return
                        }
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshotResult.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBuffer.close()
                        if (bitmap != null) continuation.resume(bitmap)
                        else continuation.resumeWithException(Exception("Failed to wrap hardware buffer."))
                    }
                    override fun onFailure(errorCode: Int) {
                        continuation.resumeWithException(Exception("Screenshot failed with error code: $errorCode"))
                    }
                })
            }
        } catch (e: Exception) {
            Log.e("ScreenshotUtil", "Screenshot capture failed", e)
            null
        }
    }
}

data class InteractableElement(
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val className: String?,
    val bounds: Rect,
    val node: AccessibilityNodeInfo
) {
    fun getCenter(): Point = Point(bounds.centerX(), bounds.centerY())
}