package com.panda.ai.v2.perception

import android.view.accessibility.AccessibilityNodeInfo

data class ScreenAnalysis(
    val uiRepresentation: String,
    val isKeyboardOpen: Boolean,
    val activityName: String,
    val elementMap: Map<Int, AccessibilityNodeInfo>,
    val scrollUp: Int?,
    val scrollDown: Int?
)