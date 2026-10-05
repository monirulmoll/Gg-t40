package com.example.model

import org.json.JSONObject

enum class CommandAction {
    OPEN_APP,
    TAP,
    TYPE,
    PASTE,
    COPY,
    ENTER,
    BACK,
    GET_SCREEN_TEXT,
    GET_LATEST_RESPONSE,
    WAIT_FOR_STABLE_TEXT,
    STATUS,
    CLEAR_LOGS;

    companion object {
        fun fromString(str: String?): CommandAction? {
            if (str == null) return null
            return entries.firstOrNull { it.name.equals(str.trim(), ignoreCase = true) }
        }
    }
}

enum class StabilityState {
    IDLE,
    MONITORING,
    CHANGING,
    STABLE,
    TIMEOUT
}

enum class LogType {
    SYSTEM,
    COMMAND,
    ACCESSIBILITY,
    STABILITY,
    CLIPBOARD,
    ERROR
}

data class LogEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: LogType,
    val message: String,
    val details: String? = null
)

data class CodeBlock(
    val language: String,
    val code: String
)

data class BridgeResult(
    val success: Boolean,
    val command: String,
    val message: String,
    val code: String = if (success) "SUCCESS" else "FAILED",
    val data: Any? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("success", success)
        json.put("command", command)
        json.put("code", code)
        json.put("message", message)
        if (data != null) {
            when (data) {
                is JSONObject -> json.put("data", data)
                is List<*> -> json.put("data", org.json.JSONArray(data))
                is Map<*, *> -> json.put("data", JSONObject(data))
                else -> json.put("data", data)
            }
        }
        if (error != null) {
            json.put("error", error)
        }
        json.put("timestamp", timestamp)
        return json
    }

    override fun toString(): String {
        return toJson().toString(2)
    }

    companion object {
        fun success(command: String, message: String, data: Any? = null): BridgeResult =
            BridgeResult(success = true, command = command, message = message, code = "SUCCESS", data = data)

        fun failed(command: String, error: String, code: String = "FAILED"): BridgeResult =
            BridgeResult(success = false, command = command, message = error, code = code, error = error)

        fun notFound(command: String, details: String): BridgeResult =
            BridgeResult(success = false, command = command, message = "Target node not found: $details", code = "NOT_FOUND", error = details)

        fun timeout(command: String, details: String, partialData: Any? = null): BridgeResult =
            BridgeResult(success = false, command = command, message = "Operation timed out: $details", code = "TIMEOUT", error = details, data = partialData)

        fun unsupported(command: String, details: String): BridgeResult =
            BridgeResult(success = false, command = command, message = "Operation unsupported: $details", code = "UNSUPPORTED", error = details)
    }
}

data class BridgeCommand(
    val action: CommandAction,
    val packageName: String? = null,
    val text: String? = null,
    val target: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val timeoutMs: Long = 15000L,
    val stabilityThresholdMs: Long = 1800L
) {
    companion object {
        fun fromJson(jsonStr: String): BridgeCommand {
            val json = JSONObject(jsonStr)
            val actionStr = json.optString("action", json.optString("command", ""))
            val action = CommandAction.fromString(actionStr)
                ?: throw IllegalArgumentException("Unknown or missing action: '$actionStr'")

            return BridgeCommand(
                action = action,
                packageName = if (json.has("package")) json.getString("package") else json.optString("packageName", null),
                text = json.optString("text", null),
                target = json.optString("target", json.optString("node", null)),
                x = if (json.has("x")) json.getDouble("x").toFloat() else null,
                y = if (json.has("y")) json.getDouble("y").toFloat() else null,
                timeoutMs = json.optLong("timeoutMs", 15000L),
                stabilityThresholdMs = json.optLong("stabilityMs", json.optLong("stabilityThresholdMs", 1800L))
            )
        }
    }
}
