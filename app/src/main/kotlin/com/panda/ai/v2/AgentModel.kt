package com.panda.ai.v2

import com.panda.ai.v2.actions.Action
import com.panda.ai.v2.message_manager.MemoryState
import com.panda.ai.v2.perception.ScreenAnalysis
import kotlinx.serialization.Serializable

typealias ScreenState = ScreenAnalysis

@Serializable
data class FileSystemState(val files: Map<String, String>)
@Serializable
data class UsageSummary(val totalTokens: Int)

enum class ToolCallingMethod {
    FUNCTION_CALLING, JSON_MODE, RAW, AUTO, TOOLS
}

enum class VisionDetailLevel {
    AUTO, LOW, HIGH
}

@Serializable
data class AgentSettings(
    val saveConversationPath: String? = null,
    val saveConversationPathEncoding: String = "utf-8",
    val maxFailures: Int = 3,
    val retryDelay: Int = 10,
    val validateOutput: Boolean = false,
    val calculateCost: Boolean = false,
    val llmTimeout: Int = 60,
    val stepTimeout: Int = 180,
    val overrideSystemMessage: String? = null,
    val extendSystemMessage: String? = null,
    val maxHistoryItems: Int? = null,
    val maxActionsPerStep: Int = 10,
    val useThinking: Boolean = true,
    val flashMode: Boolean = false,
    val toolCallingMethod: ToolCallingMethod? = ToolCallingMethod.AUTO,
    val includeToolCallExamples: Boolean = false,
    val pageExtractionLlm: String? = null
)

@Serializable
data class AgentState(
    val agentId: String = java.util.UUID.randomUUID().toString(),
    var nSteps: Int = 1,
    var consecutiveFailures: Int = 0,
    var lastResult: List<ActionResult>? = null,
    var lastPlan: String? = null,
    var lastModelOutput: AgentOutput? = null,
    var paused: Boolean = false,
    var stopped: Boolean = false,
    val memoryManagerState: MemoryState = MemoryState(),
    val fileSystemState: FileSystemState? = null
)

@Serializable
data class AgentStepInfo(
    val stepNumber: Int,
    val maxSteps: Int
) {
    fun isLastStep(): Boolean = stepNumber >= maxSteps - 1
}

@Serializable
data class ActionResult(
    val isDone: Boolean? = false,
    val success: Boolean? = null,
    val error: String? = null,
    val attachments: List<String>? = null,
    val longTermMemory: String? = null,
    val extractedContent: String? = null,
    val includeExtractedContentOnlyOnce: Boolean = false
) {
    init {
        if (success == true && isDone != true) {
            throw IllegalArgumentException(
                "success=true can only be set when isDone=true."
            )
        }
    }
}

@Serializable
data class AgentBrain(
    val thinking: String?,
    val evaluationPreviousGoal: String?,
    val memory: String?,
    val nextGoal: String?
)

@Serializable
data class AgentOutput(
    val thinking: String? = null,
    val evaluationPreviousGoal: String? = null,
    val memory: String? = null,
    val nextGoal: String? = null,
    val action: List<Action>
) {
    val currentState: AgentBrain
        get() = AgentBrain(
            thinking = this.thinking,
            evaluationPreviousGoal = this.evaluationPreviousGoal,
            memory = this.memory,
            nextGoal = this.nextGoal
        )
}

@Serializable
data class StepMetadata(
    val stepStartTime: Double,
    val stepEndTime: Double,
    val stepNumber: Int,
    val inputTokens: Int
) {
    val durationSeconds: Double
        get() = stepEndTime - stepStartTime
}

data class AgentHistory(
    val modelOutput: AgentOutput?,
    val result: List<ActionResult>,
    val state: ScreenState,
    val metadata: StepMetadata? = null
)

data class AgentHistoryList<T>(
    val history: MutableList<AgentHistory> = mutableListOf(),
    val usage: UsageSummary? = null
) {
    val totalDurationSeconds: Double
        get() = history.sumOf { it.metadata?.durationSeconds ?: 0.0 }

    val totalInputTokens: Int
        get() = history.sumOf { it.metadata?.inputTokens ?: 0 }

    fun addItem(item: AgentHistory) {
        history.add(item)
    }
}