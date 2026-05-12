package com.curiokid.app.data.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny in-memory ring buffer of recent log entries. Used by the in-app
 * "Debug log" panel (visible in Settings when developer mode is on).
 *
 * Always captures, regardless of the developer-mode flag — toggling the
 * flag only controls whether the entries are *shown*. That way enabling
 * developer mode immediately reveals the most recent failures without
 * needing to reproduce them.
 */
object DebugLog {

    private const val MAX_ENTRIES = 200

    enum class Level { INFO, WARN, ERROR }

    data class Entry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: Level,
        val tag: String,
        val message: String,
        val stackTrace: String? = null,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun i(tag: String, message: String) = append(Level.INFO, tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        append(Level.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        append(Level.ERROR, tag, message, throwable)

    fun clear() {
        _entries.value = emptyList()
    }

    /**
     * Format the given entries (or the current buffer) as plain text
     * suitable for pasting into a bug report or chat message.
     */
    fun formatForClipboard(entries: List<Entry> = _entries.value): String {
        if (entries.isEmpty()) return ""
        val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return buildString {
            entries.forEach { entry ->
                append(timeFormat.format(Date(entry.timestamp)))
                append("  ")
                append(entry.level.name.padEnd(5))
                append("  ")
                append(entry.tag)
                append('\n')
                append(entry.message)
                entry.stackTrace?.let { trace ->
                    append('\n')
                    append(trace.trimEnd())
                }
                append("\n\n")
            }
        }.trimEnd()
    }

    private fun append(level: Level, tag: String, message: String, throwable: Throwable?) {
        val entry = Entry(
            level = level,
            tag = tag,
            message = message,
            stackTrace = throwable?.let { it.stackTraceString() },
        )
        _entries.update { current ->
            (current + entry).takeLast(MAX_ENTRIES)
        }
    }

    private fun Throwable.stackTraceString(): String {
        val sw = StringWriter()
        PrintWriter(sw).use { printStackTrace(it) }
        return sw.toString()
    }
}
