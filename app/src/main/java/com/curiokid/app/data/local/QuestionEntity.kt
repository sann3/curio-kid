package com.curiokid.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "questions")
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val question: String,
    val answer: String,
    val attachmentType: String = "none", // none|image|audio
    val flagged: Boolean = false,
    val topic: String? = null,
)
