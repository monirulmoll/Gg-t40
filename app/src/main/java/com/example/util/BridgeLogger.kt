package com.example.util

import com.example.model.LogEntry
import com.example.model.LogType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque

object BridgeLogger {
    private const val MAX_LOGS = 500
    private val deque = ConcurrentLinkedDeque<LogEntry>()
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private val SENSITIVE_PATTERNS = listOf(
        Regex("(?i)password\\s*[:=]\\s*\\S+"),
        Regex("(?i)bearer\\s+[A-Za-z0-9-_.]+"),
        Regex("(?i)token\\s*[:=]\\s*\\S+"),
        Regex("(?i)cookie\\s*[:=]\\s*\\S+"),
        Regex("(?i)api[_-]?key\\s*[:=]\\s*\\S+")
    )

    private fun sanitize(input: String?): String? {
        if (input == null) return null
        var result: String = input
        for (pattern in SENSITIVE_PATTERNS) {
            result = pattern.replace(result, "[REDACTED]")
        }
        return result
    }

    fun log(type: LogType, message: String, details: String? = null) {
        val entry = LogEntry(
            type = type,
            message = sanitize(message) ?: "",
            details = sanitize(details)
        )
        deque.addFirst(entry)
        while (deque.size > MAX_LOGS) {
            deque.pollLast()
        }
        _logsFlow.value = deque.toList()
    }

    fun logSystem(message: String, details: String? = null) = log(LogType.SYSTEM, message, details)
    fun logCommand(message: String, details: String? = null) = log(LogType.COMMAND, message, details)
    fun logAccessibility(message: String, details: String? = null) = log(LogType.ACCESSIBILITY, message, details)
    fun logStability(message: String, details: String? = null) = log(LogType.STABILITY, message, details)
    fun logClipboard(message: String, details: String? = null) = log(LogType.CLIPBOARD, message, details)
    fun logOpencv(message: String, details: String? = null) = log(LogType.OPENCV, message, details)
    fun logError(message: String, details: String? = null) = log(LogType.ERROR, message, details)

    fun clear() {
        deque.clear()
        _logsFlow.value = emptyList()
    }
}
