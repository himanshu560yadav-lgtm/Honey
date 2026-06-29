package com.panda.ai.v2.perception

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.panda.ai.RawScreenData
import com.panda.ai.api.Eyes
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async

@RequiresApi(Build.VERSION_CODES.R)
class Perception(
    private val eyes: Eyes,
    private val semanticParser: SemanticParser
) {
    suspend fun analyze(previousState: Set<String>? = null, all: Boolean? = false): ScreenAnalysis {
        return coroutineScope {
            val rawDataDeferred = if (all == true) {
                async { eyes.getAllRawScreenData() }
            } else {
                async { eyes.getRawScreenData() }
            }
            val keyboardStatusDeferred = async { eyes.getKeyBoardStatus() }
            val currentActivity = async { eyes.getCurrentActivityName() }

            val rawTree = rawDataDeferred.await() ?: RawScreenData(null, 0, 0, 0, 0)
            val isKeyboardOpen = keyboardStatusDeferred.await()
            val activityName = currentActivity.await()
            val rootNode = rawTree.rootNode

            if (rootNode != null) {
                var (uiRepresentation, elementMap) = semanticParser.parseNodeTree(
                    rootNode,
                    previousState,
                    rawTree.screenWidth,
                    rawTree.screenHeight
                )

                val hasContentAbove = rawTree.pixelsAbove > 0
                val hasContentBelow = rawTree.pixelsBelow > 0

                if (uiRepresentation.isNotBlank()) {
                    uiRepresentation = if (hasContentAbove)
                        "... ${rawTree.pixelsAbove} pixels above - scroll up to see more ...\n$uiRepresentation"
                    else "[Start of page]\n$uiRepresentation"

                    uiRepresentation = if (hasContentBelow)
                        "$uiRepresentation\n... ${rawTree.pixelsBelow} pixels below - scroll down to see more ..."
                    else "$uiRepresentation\n[End of page]"
                } else {
                    uiRepresentation = "The screen is empty or contains no interactive elements."
                }

                ScreenAnalysis(
                    uiRepresentation = uiRepresentation,
                    isKeyboardOpen = isKeyboardOpen,
                    activityName = activityName,
                    elementMap = elementMap,
                    scrollUp = rawTree.pixelsAbove,
                    scrollDown = rawTree.pixelsBelow
                )
            } else {
                ScreenAnalysis(
                    uiRepresentation = "The screen is empty or contains no interactive elements.",
                    isKeyboardOpen = isKeyboardOpen,
                    activityName = activityName,
                    elementMap = mutableMapOf(),
                    scrollUp = rawTree.pixelsAbove,
                    scrollDown = rawTree.pixelsBelow
                )
            }
        }
    }
}