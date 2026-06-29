package com.panda.ai.v2.logging

import kotlinx.serialization.Serializable

@Serializable
data class TaskLog(
    val uid: String,
    val timestamp: Long,
    val input: String,
    val output: String
)