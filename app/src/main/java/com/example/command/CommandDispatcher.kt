package com.example.command

import android.content.Context
import com.example.model.BridgeCommand
import com.example.model.BridgeResult
import com.example.model.CommandAction
import com.example.service.BridgeAccessibilityService
import com.example.service.ResponseMonitor
import com.example.util.BridgeClipboard
import com.example.util.BridgeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommandDispatcher(private val context: Context) {

    suspend fun execute(command: BridgeCommand): BridgeResult = withContext(Dispatchers.Default) {
        val action = command.action
        BridgeLogger.logCommand("Dispatching action: ${action.name}")

        // Commands that do not strictly require an active AccessibilityService
        when (action) {
            CommandAction.CLEAR_LOGS -> {
                BridgeLogger.clear()
                return@withContext BridgeResult.success("CLEAR_LOGS", "Logs cleared")
            }
            CommandAction.STATUS -> {
                val service = BridgeAccessibilityService.instance
                val isConnected = service != null
                val fgPkg = BridgeAccessibilityService.currentForegroundPackage.value
                val data = mapOf(
                    "accessibilityConnected" to isConnected,
                    "foregroundPackage" to fgPkg,
                    "stabilityState" to ResponseMonitor.stabilityState.value.name,
                    "serverPort" to 8765
                )
                return@withContext BridgeResult.success("STATUS", "Service status retrieved", data)
            }
            CommandAction.OPEN_APP -> {
                val pkg = command.packageName
                if (pkg.isNullOrBlank()) {
                    return@withContext BridgeResult.failed("OPEN_APP", "Missing 'package' argument in command")
                }
                val service = BridgeAccessibilityService.instance
                return@withContext if (service != null) {
                    service.openApp(pkg)
                } else {
                    // Fallback using context
                    try {
                        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                        if (intent != null) {
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            context.startActivity(intent)
                            BridgeLogger.logCommand("OPEN_APP", "Launched $pkg via context")
                            BridgeResult.success("OPEN_APP", "Application '$pkg' opened", mapOf("package" to pkg))
                        } else {
                            BridgeResult.failed("OPEN_APP", "Package '$pkg' is not installed", "NOT_FOUND")
                        }
                    } catch (e: Exception) {
                        BridgeResult.failed("OPEN_APP", "Failed to launch $pkg: ${e.message}")
                    }
                }
            }
            else -> {}
        }

        // For all other UI automation actions, AccessibilityService must be active
        val service = BridgeAccessibilityService.instance
        if (service == null) {
            BridgeLogger.logError("Cannot execute ${action.name}: AccessibilityService not connected")
            return@withContext BridgeResult.failed(
                command = action.name,
                error = "Accessibility service is not connected. Please enable Bridge Controller in Android Settings > Accessibility.",
                code = "SERVICE_DISABLED"
            )
        }

        return@withContext when (action) {
            CommandAction.TAP, CommandAction.CLICK -> {
                service.performTap(command.target, command.x, command.y)
            }
            CommandAction.TYPE -> {
                val text = command.text
                if (text == null) {
                    BridgeResult.failed("TYPE", "Missing 'text' argument to type")
                } else {
                    service.performType(text)
                }
            }
            CommandAction.PASTE -> {
                service.performPaste()
            }
            CommandAction.COPY -> {
                service.performCopy()
            }
            CommandAction.ENTER -> {
                service.performEnter()
            }
            CommandAction.BACK -> {
                service.performBack()
            }
            CommandAction.GET_SCREEN_TEXT -> {
                val text = service.getScreenText(includeDescriptions = true)
                if (text.isNotBlank()) {
                    BridgeLogger.logAccessibility("GET_SCREEN_TEXT returned ${text.length} chars")
                    BridgeResult.success(
                        command = "GET_SCREEN_TEXT",
                        message = "Extracted visible screen text",
                        data = mapOf(
                            "length" to text.length,
                            "text" to text
                        )
                    )
                } else {
                    BridgeResult.failed(
                        command = "GET_SCREEN_TEXT",
                        error = "No readable text found on current screen",
                        code = "EMPTY_SCREEN"
                    )
                }
            }
            CommandAction.GET_LATEST_RESPONSE -> {
                val response = service.getLatestAssistantResponse()
                if (!response.isNullOrBlank()) {
                    val codeBlocks = service.detectCodeBlocks(response)
                    BridgeLogger.logAccessibility("GET_LATEST_RESPONSE found ${response.length} chars, ${codeBlocks.size} code blocks")
                    BridgeResult.success(
                        command = "GET_LATEST_RESPONSE",
                        message = "Extracted latest assistant response",
                        data = mapOf(
                            "length" to response.length,
                            "text" to response,
                            "hasCodeBlocks" to codeBlocks.isNotEmpty(),
                            "codeBlocks" to codeBlocks.map { mapOf("language" to it.language, "code" to it.code) }
                        )
                    )
                } else {
                    BridgeResult.failed(
                        command = "GET_LATEST_RESPONSE",
                        error = "No readable assistant response found on screen",
                        code = "NOT_FOUND"
                    )
                }
            }
            CommandAction.WAIT_FOR_STABLE_TEXT -> {
                ResponseMonitor.waitForStableText(
                    timeoutMs = command.timeoutMs,
                    stabilityThresholdMs = command.stabilityThresholdMs
                )
            }
            CommandAction.OPEN_APP, CommandAction.STATUS, CommandAction.CLEAR_LOGS -> {
                BridgeResult.success(action.name, "Already handled")
            }
        }
    }
}
