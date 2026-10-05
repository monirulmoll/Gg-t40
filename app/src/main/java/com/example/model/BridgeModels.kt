package com.example.model

import org.json.JSONObject

enum class CommandAction {
    OPEN_APP,
    TAP,
    CLICK,
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
            val trimmed = str.trim()
            if (trimmed.equals("SEND", ignoreCase = true)) return ENTER
            return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
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
    OPENCV,
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
    val method: String? = null,
    val confidence: Double? = null,
    val x: Int? = null,
    val y: Int? = null,
    val data: Any? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("success", success)
        if (method != null) {
            json.put("method", method)
        }
        json.put("command", command)
        if (confidence != null) {
            // Round confidence to 2 decimal places for clean display
            val rounded = (confidence * 100).toInt() / 100.0
            json.put("confidence", rounded)
        }
        if (x != null) {
            json.put("x", x)
        }
        if (y != null) {
            json.put("y", y)
        }
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

        fun chatGptAccessibilitySuccess(command: String = "CLICK", message: String = "ChatGPT button clicked successfully"): BridgeResult =
            BridgeResult(
                success = true,
                command = command,
                method = "ACCESSIBILITY",
                message = message,
                code = "SUCCESS"
            )

        fun chatGptOpenCvSuccess(
            command: String = "CLICK",
            confidence: Double,
            x: Int,
            y: Int,
            message: String = "ChatGPT button found and clicked using image matching"
        ): BridgeResult =
            BridgeResult(
                success = true,
                command = command,
                method = "OPENCV",
                confidence = confidence,
                x = x,
                y = y,
                message = message,
                code = "SUCCESS"
            )

        fun chatGptClickFailed(command: String = "CLICK", message: String = "Button not found using Accessibility or OpenCV"): BridgeResult =
            BridgeResult(
                success = false,
                command = command,
                message = message,
                code = "FAILED",
                error = message
            )
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
                packageName = when {
                    json.has("package") && !json.isNull("package") -> json.getString("package")
                    json.has("packageName") && !json.isNull("packageName") -> json.getString("packageName")
                    else -> null
                },
                text = if (json.has("text") && !json.isNull("text")) json.getString("text") else null,
                target = when {
                    json.has("target") && !json.isNull("target") -> json.getString("target")
                    json.has("node") && !json.isNull("node") -> json.getString("node")
                    else -> null
                },
                x = if (json.has("x")) json.getDouble("x").toFloat() else null,
                y = if (json.has("y")) json.getDouble("y").toFloat() else null,
                timeoutMs = json.optLong("timeoutMs", 15000L),
                stabilityThresholdMs = json.optLong("stabilityMs", json.optLong("stabilityThresholdMs", 1800L))
            )
        }
    }
}
