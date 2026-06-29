package com.panda.ai.v2.llm

import kotlinx.serialization.Serializable

enum class MessageRole {
    USER,
    MODEL,
    TOOL
}

@Serializable
sealed interface ContentPart

@Serializable
data class TextPart(val text: String) : ContentPart

@Serializable
data class GeminiMessage(
    val role: MessageRole,
    val parts: List<ContentPart>,
    val toolCode: String? = null
) {
    constructor(text: String) : this(
        role = MessageRole.USER,
        parts = listOf(TextPart(text))
    )
}