package com.blurr.voice.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val source: String,
    val createdAt: Long = System.currentTimeMillis()
)