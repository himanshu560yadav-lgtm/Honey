package com.blurr.voice.data

import java.util.Date

data class UserMemory(
    val id: String,
    val text: String,
    val source: String,
    val createdAt: Date
)