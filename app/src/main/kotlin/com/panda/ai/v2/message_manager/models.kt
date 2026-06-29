package com.panda.ai.v2.message_manager

import com.panda.ai.v2.llm.GeminiMessage
import kotlinx.serialization.Serializable

@Serializable
data class HistoryItem(
    val stepNumber: Int? = null,
    val evaluation: String? = null,
    val memory: String? = null,
    val nextGoal: String? = null,
    val actionResults: String? = null,
    val error: String? = null,
    val systemMessage: String? = null
) {
    fun toPromptString(): String {
        val stepStr = stepNumber?.let { "step_$it" } ?: "step_unknown"
        val content = when {
            error != null -> error
            systemMessage != null -> systemMessage
            else -> listOfNotNull(
                evaluation?.let { "Evaluation of Previous Step: $it" },
                memory?.let { "Memory: $it" },
                nextGoal?.let { "Next Goal: $it" },
                actionResults
            ).joinToString("\n")
        }
        return "<$stepStr>\n$content\n</$stepStr>"
    }
}

@Serializable
data class MessageHistory(
    var systemMessage: GeminiMessage?,
    var stateMessage: GeminiMessage?,
    val contextMessages: MutableList<GeminiMessage> = mutableListOf()
) {
    fun getMessages(): List<GeminiMessage> {
        return listOfNotNull(systemMessage, stateMessage) + contextMessages
    }
}

@Serializable
data class MemoryState(
    val history: MessageHistory = MessageHistory(null, null),
    val toolId: Int = 1,
    val agentHistoryItems: MutableList<HistoryItem> = mutableListOf(
        HistoryItem(stepNumber = 0, systemMessage = "Agent initialized")
    ),
    var readStateDescription: String = ""
)