package com.panda.ai.intents

import android.content.Context
import android.content.Intent

interface AppIntent {
    val name: String
    fun description(): String
    fun parametersSpec(): List<ParameterSpec>
    fun buildIntent(context: Context, params: Map<String, Any?>): Intent?
}

data class ParameterSpec(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String
)