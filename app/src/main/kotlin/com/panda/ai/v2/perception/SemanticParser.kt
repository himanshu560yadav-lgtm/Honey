package com.panda.ai.v2.perception

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

@Serializable
data class XmlNode(
    val attributes: MutableMap<String, String> = mutableMapOf(),
    val children: MutableList<XmlNode> = mutableListOf(),
    var parent: XmlNode? = null
) {
    override fun toString(): String {
        val text = getVisibleText().let { if (it.isNotBlank()) "text='$it'" else "" }
        val resId = attributes["resource-id"]?.let { "id='$it'" } ?: ""
        return "XmlNode($text $resId, children=${children.size})"
    }

    val extraInfo: String
        get() {
            val infoParts = mutableListOf<String>()
            val propertiesToCheck = listOf(
                "checkable", "checked", "clickable", "enabled", "focusable",
                "focused", "scrollable", "long-clickable", "selected"
            )
            propertiesToCheck.forEach { prop ->
                if (attributes[prop] == "true") infoParts.add(prop.replace("-", " "))
            }
            return if (infoParts.isNotEmpty()) "This element is ${infoParts.joinToString(", ")}." else ""
        }

    fun isSemanticallyImportant(): Boolean {
        return attributes["resource-id"]?.isNotBlank() == true ||
                attributes["text"]?.isNotBlank() == true ||
                attributes["content-desc"]?.isNotBlank() == true
    }

    fun isInteractive(): Boolean {
        if (attributes["enabled"] == "false") return false
        return attributes["clickable"] == "true" ||
                attributes["long-clickable"] == "true" ||
                attributes["checkable"] == "true" ||
                attributes["scrollable"] == "true" ||
                attributes["class"] == "android.widget.EditText" ||
                attributes["password"] == "true" ||
                attributes["focusable"] == "true"
    }

    fun getVisibleText(): String {
        return attributes["text"]?.takeIf { it.isNotBlank() }
            ?: attributes["content-desc"]?.takeIf { it.isNotBlank() }
            ?: ""
    }

    fun isVisibleOnScreen(screenWidth: Int, screenHeight: Int): Boolean {
        val boundsStr = attributes["bounds"] ?: return false
        val regex = """\[(\d+),(\d+)\]\[(\d+),(\d+)\]""".toRegex()
        val matchResult = regex.find(boundsStr) ?: return false
        return try {
            val (left, top, right, bottom) = matchResult.destructured.toList().map { it.toInt() }
            if (right <= 0 || left >= screenWidth || bottom <= 0 || top >= screenHeight) false else true
        } catch (e: NumberFormatException) {
            false
        }
    }
}

class SemanticParser {
    private val interactiveNodeMap = mutableMapOf<Int, AccessibilityNodeInfo>()
    private var interactiveElementCounter = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    fun parseNodeTree(
        rootNode: AccessibilityNodeInfo,
        previousNodes: Set<String>?,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<String, Map<Int, AccessibilityNodeInfo>> {
        this.screenWidth = screenWidth
        this.screenHeight = screenHeight
        interactiveNodeMap.clear()
        interactiveElementCounter = 0
        val stringBuilder = StringBuilder()
        buildStringFromNodeRecursive(rootNode, 0, stringBuilder, previousNodes ?: emptySet())
        return Pair(stringBuilder.toString(), interactiveNodeMap)
    }

    private fun getExtraInfo(node: AccessibilityNodeInfo): String {
        val infoParts = mutableListOf<String>()
        if (node.isCheckable) infoParts.add("checkable")
        if (node.isChecked) infoParts.add("checked")
        if (node.isClickable) infoParts.add("clickable")
        if (node.isEnabled) infoParts.add("enabled")
        if (node.isFocusable) infoParts.add("focusable")
        if (node.isFocused) infoParts.add("focused")
        if (node.isScrollable) infoParts.add("scrollable")
        if (node.isLongClickable) infoParts.add("long clickable")
        if (node.isSelected) infoParts.add("selected")
        return if (infoParts.isNotEmpty()) "This element is ${infoParts.joinToString(", ")}." else ""
    }

    private fun buildStringFromNodeRecursive(
        node: AccessibilityNodeInfo,
        indentLevel: Int,
        builder: StringBuilder,
        previousNodes: Set<String>
    ) {
        if (!node.isVisibleToUser) return

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val visibleText = if (text.isNotBlank()) text else contentDesc
        val resourceId = node.viewIdResourceName ?: ""
        val className = node.className?.toString() ?: ""

        val isSemanticallyImportant = resourceId.isNotBlank() || visibleText.isNotBlank()
        val isInteractive = (node.isClickable ||
                node.isLongClickable ||
                node.isCheckable ||
                node.isScrollable ||
                node.isEditable ||
                node.isFocusable) && node.isEnabled

        val shouldPrintNode = isInteractive || isSemanticallyImportant

        if (shouldPrintNode) {
            val nodeKey = "$visibleText|$resourceId|$className"
            val isNew = !previousNodes.contains(nodeKey) && isSemanticallyImportant
            val indent = "\t".repeat(indentLevel)

            if (isInteractive) {
                interactiveElementCounter++
                interactiveNodeMap[interactiveElementCounter] = node
                val newMarker = if (isNew) "* " else ""
                val extraInfo = getExtraInfo(node)
                val simpleClassName = className.removePrefix("android.widget.")
                builder.append("$indent$newMarker[$interactiveElementCounter] ")
                    .append("text:\"${visibleText.replace("\n", " ")}\" ")
                    .append("<$resourceId> ")
                    .append("<$extraInfo> ")
                    .append("<$simpleClassName>\n")
            } else {
                val newMarker = if (isNew) "* " else ""
                builder.append("$indent$newMarker${visibleText.replace("\n", " ")}\n")
            }
        }

        val nextIndent = if (shouldPrintNode) indentLevel + 1 else indentLevel
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) buildStringFromNodeRecursive(child, nextIndent, builder, previousNodes)
        }
    }

    fun parseAndFilter(xmlString: String): String {
        val rootNode = buildTreeFromXml(xmlString) ?: return "<hierarchy/>"
        val newChildren = rootNode.children.flatMap { prune(it) }
        rootNode.children.clear()
        rootNode.children.addAll(newChildren)
        return toXmlString(rootNode)
    }

    private fun prune(node: XmlNode): List<XmlNode> {
        val newChildren = node.children.flatMap { prune(it) }
        node.children.clear()
        node.children.addAll(newChildren)
        newChildren.forEach { it.parent = node }
        if (!node.isVisibleOnScreen(screenWidth, screenHeight)) return node.children
        return if (node.isSemanticallyImportant() || node.isInteractive() || node.children.isNotEmpty()) {
            listOf(node)
        } else {
            node.children
        }
    }

    private fun buildTreeFromXml(xmlString: String): XmlNode? {
        val cleanedXml = xmlString.replace('\u00A0', ' ')
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(cleanedXml))
        var root: XmlNode? = null
        val nodeStack = ArrayDeque<XmlNode>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "node") {
                        val newNode = XmlNode()
                        for (i in 0 until parser.attributeCount) {
                            newNode.attributes[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                        }
                        if (root == null) root = newNode
                        else {
                            val parent = nodeStack.lastOrNull()
                            parent?.children?.add(newNode)
                            newNode.parent = parent
                        }
                        nodeStack.addLast(newNode)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "node") nodeStack.removeLastOrNull()
                }
            }
            eventType = parser.next()
        }
        return root
    }

    private fun toXmlString(root: XmlNode): String {
        val stringBuilder = StringBuilder()
        stringBuilder.append("<hierarchy")
        root.attributes.forEach { (key, value) ->
            stringBuilder.append(" ").append(key).append("=\"").append(escapeXml(value)).append("\"")
        }
        stringBuilder.appendLine(">")
        root.children.forEach { child -> buildXmlStringRecursive(child, stringBuilder, 1) }
        stringBuilder.appendLine("</hierarchy>")
        return stringBuilder.toString()
    }

    private fun buildXmlStringRecursive(node: XmlNode, builder: StringBuilder, indentLevel: Int) {
        val indent = "  ".repeat(indentLevel)
        builder.append(indent).append("<node")
        node.attributes.forEach { (key, value) ->
            builder.append(" ").append(key).append("=\"").append(escapeXml(value)).append("\"")
        }
        if (node.children.isEmpty()) {
            builder.appendLine("/>")
        } else {
            builder.appendLine(">")
            for (child in node.children) buildXmlStringRecursive(child, builder, indentLevel + 1)
            builder.append(indent).appendLine("</node>")
        }
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}