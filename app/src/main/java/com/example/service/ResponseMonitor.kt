package com.example.service

import com.example.model.BridgeResult
import com.example.model.StabilityState
import com.example.util.BridgeLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ResponseMonitor {

    private val _stabilityState = MutableStateFlow(StabilityState.IDLE)
    val stabilityState = _stabilityState.asStateFlow()

    private val _currentDetectedText = MutableStateFlow("")
    val currentDetectedText = _currentDetectedText.asStateFlow()

    private var activeJob: Job? = null

    suspend fun waitForStableText(
        timeoutMs: Long = 20000L,
        stabilityThresholdMs: Long = 1800L,
        pollIntervalMs: Long = 250L
    ): BridgeResult = withContext(Dispatchers.Default) {
        val service = BridgeAccessibilityService.instance
        if (service == null) {
            _stabilityState.value = StabilityState.IDLE
            return@withContext BridgeResult.failed(
                command = "WAIT_FOR_STABLE_TEXT",
                error = "Accessibility service is not connected",
                code = "SERVICE_UNAVAILABLE"
            )
        }

        _stabilityState.value = StabilityState.MONITORING
        BridgeLogger.logStability(
            "Started response stability monitor",
            "Threshold: ${stabilityThresholdMs}ms, Timeout: ${timeoutMs}ms"
        )

        var lastText = ""
        var lastChangeTime = System.currentTimeMillis()
        val startTime = System.currentTimeMillis()
        var textEverObserved = false

        while (isActive) {
            val now = System.currentTimeMillis()

            // Check overall timeout
            if (now - startTime >= timeoutMs) {
                _stabilityState.value = StabilityState.TIMEOUT
                BridgeLogger.logStability("Monitoring timed out after ${timeoutMs}ms", "Length: ${lastText.length}")
                return@withContext if (lastText.isNotBlank()) {
                    BridgeResult.timeout(
                        command = "WAIT_FOR_STABLE_TEXT",
                        details = "Timed out waiting for stability threshold (${stabilityThresholdMs}ms), returning latest text",
                        partialData = mapOf("text" to lastText, "length" to lastText.length, "codeBlocks" to service.detectCodeBlocks(lastText))
                    )
                } else {
                    BridgeResult.timeout(
                        command = "WAIT_FOR_STABLE_TEXT",
                        details = "Timed out with no readable text detected on screen"
                    )
                }
            }

            // Extract candidate response
            val currentText = service.getLatestAssistantResponse() ?: service.getScreenText(includeDescriptions = false)

            if (currentText.isNotBlank()) {
                textEverObserved = true
                _currentDetectedText.value = currentText

                if (currentText != lastText) {
                    val prevLen = lastText.length
                    lastText = currentText
                    lastChangeTime = now
                    _stabilityState.value = StabilityState.CHANGING
                    BridgeLogger.logStability(
                        "Response updating...",
                        "Len: $prevLen -> ${currentText.length} chars"
                    )
                } else {
                    val unchangedDuration = now - lastChangeTime
                    if (unchangedDuration >= stabilityThresholdMs) {
                        _stabilityState.value = StabilityState.STABLE
                        val codeBlocks = service.detectCodeBlocks(lastText)
                        BridgeLogger.logStability(
                            "Response reached stable state!",
                            "Length: ${lastText.length} chars, Code blocks: ${codeBlocks.size}"
                        )
                        return@withContext BridgeResult.success(
                            command = "WAIT_FOR_STABLE_TEXT",
                            message = "Response stabilized after ${unchangedDuration}ms unchanged",
                            data = mapOf(
                                "text" to lastText,
                                "length" to lastText.length,
                                "stableDurationMs" to unchangedDuration,
                                "hasCodeBlocks" to codeBlocks.isNotEmpty(),
                                "codeBlocks" to codeBlocks.map { mapOf("language" to it.language, "code" to it.code) }
                            )
                        )
                    }
                }
            } else {
                if (!textEverObserved) {
                    _stabilityState.value = StabilityState.MONITORING
                }
            }

            delay(pollIntervalMs)
        }

        _stabilityState.value = StabilityState.IDLE
        BridgeResult.failed("WAIT_FOR_STABLE_TEXT", "Monitoring was cancelled")
    }

    fun cancelActiveMonitoring() {
        activeJob?.cancel()
        _stabilityState.value = StabilityState.IDLE
        BridgeLogger.logStability("Monitoring cancelled by user")
    }
}
