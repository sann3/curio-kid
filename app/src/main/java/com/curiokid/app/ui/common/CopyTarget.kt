package com.curiokid.app.ui.common

/**
 * Which slice of a question/answer pair the user wants on their clipboard.
 * Shared by every screen that surfaces a copy action.
 */
enum class CopyTarget {
    QUESTION,
    ANSWER,
    BOTH;

    fun build(question: String, answer: String): String = when (this) {
        QUESTION -> question
        ANSWER -> answer
        BOTH -> "Q: $question\nA: $answer"
    }
}
