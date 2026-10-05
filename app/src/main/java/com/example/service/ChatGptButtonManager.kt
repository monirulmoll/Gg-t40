package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.example.model.BridgeResult
import com.example.util.BridgeLogger
import com.example.util.OpenCVHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

object ChatGptButtonManager {

    const val CHATGPT_PACKAGE = "com.openai.chatgpt"
    private const val CONFIDENCE_THRESHOLD = 0.85
    private const val SEND_CONFIDENCE_THRESHOLD = 0.75

    // Coordinate ratios for ChatGPT Send button (blue circular button with upward arrow ↑)
    // Directly derived from actual screen captures:
    const val SEND_BUTTON_X_RATIO = 0.910f
    const val SEND_BUTTON_Y_KEYBOARD_OPEN_RATIO = 0.573f
    const val SEND_BUTTON_Y_KEYBOARD_CLOSED_RATIO = 0.940f

    /**
     * Executes the button click specifically for ChatGPT:
     * 1. If target is Send/Submit/Enter -> Direct (X, Y) Coordinate Click
     * 2. For other buttons (Copy, etc.) -> Accessibility Node Click & OpenCV Fallback
     */
    suspend fun clickButtonInChatGpt(
        service: BridgeAccessibilityService,
        context: Context,
        targetButton: String = "Copy"
    ): BridgeResult = withContext(Dispatchers.Default) {
        val targetLower = targetButton.lowercase().trim()

        // Send button uses direct coordinate touch gesture (keyboard open vs closed)
        if (targetLower == "send" || targetLower == "submit" || targetLower == "enter") {
            return@withContext clickSendButton(service, context)
        }

        val activePackage = BridgeAccessibilityService.currentForegroundPackage.value
        val rootPkg = try { service.rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        val isChatGpt = activePackage == CHATGPT_PACKAGE || rootPkg == CHATGPT_PACKAGE

        // Safety check: ensure target is strictly ChatGPT
        if (!isChatGpt) {
            BridgeLogger.logCommand("CHATGPT_CLICK", "Active package '$activePackage' is not $CHATGPT_PACKAGE. Running standard tap.")
            return@withContext service.performTap(targetButton)
        }

        BridgeLogger.logCommand("CHATGPT_CLICK", "Starting ChatGPT button click for '$targetButton'")

        // ==================================================
        // METHOD 1 — ACCESSIBILITY NODE CLICK
        // ==================================================
        val method1Result = tryAccessibilityNodeClick(service, targetButton)
        if (method1Result != null && method1Result.success) {
            BridgeLogger.logAccessibility("Method 1 (Accessibility) succeeded for '$targetButton'. Skipping Method 2.")
            return@withContext method1Result
        }

        BridgeLogger.logAccessibility("Method 1 (Accessibility) did not find/click '$targetButton'. Falling back to Method 2 (OpenCV).")

        // ==================================================
        // METHOD 2 — OPENCV IMAGE MATCHING FALLBACK
        // ==================================================
        return@withContext tryOpenCvImageMatching(service, context, targetButton)
    }

    /**
     * Specialized Direct (X, Y) Coordinate Send Button Click for ChatGPT:
     *
     * In ChatGPT, the Send button is often inaccessible via standard AccessibilityNodeInfo
     * action clicks. This implementation uses direct touch gesture tap on verified physical (X, Y)
     * coordinates according to keyboard state:
     *
     * Case 1: Keyboard KHULA HO (Open)
     *   Input bar shifts above the soft keyboard (~42% screen height).
     *   X = 0.910 * screenWidth (Center of blue circular button)
     *   Y = 0.573 * screenHeight (Vertical center of input bar above keyboard)
     *
     * Case 2: Keyboard KHULA NA HO (Closed)
     *   Input bar stays at the screen bottom.
     *   X = 0.910 * screenWidth (Center of blue circular button)
     *   Y = 0.940 * screenHeight (Vertical center of input bar at bottom)
     */
    suspend fun clickSendButton(
        service: BridgeAccessibilityService,
        context: Context
    ): BridgeResult = withContext(Dispatchers.Default) {
        val (screenWidth, screenHeight) = getScreenDimensions(context)
        val keyboardOpen = isKeyboardOpen(service, screenHeight)

        val clickX = (screenWidth * SEND_BUTTON_X_RATIO).roundToInt()
        val clickY = if (keyboardOpen) {
            (screenHeight * SEND_BUTTON_Y_KEYBOARD_OPEN_RATIO).roundToInt()
        } else {
            (screenHeight * SEND_BUTTON_Y_KEYBOARD_CLOSED_RATIO).roundToInt()
        }

        val caseLabel = if (keyboardOpen) "Case 1: Keyboard OPEN (Y=57.3%)" else "Case 2: Keyboard CLOSED (Y=94.0%)"
        BridgeLogger.logCommand(
            "CHATGPT_SEND",
            "Executing Direct (X, Y) Send tap -> $caseLabel at ($clickX, $clickY) on ${screenWidth}x${screenHeight}"
        )

        // Dispatch direct physical touch gesture at the calculated (X, Y) coordinates
        val dispatched = service.dispatchTapGestureDirect(clickX.toFloat(), clickY.toFloat())

        if (dispatched) {
            BridgeLogger.logCommand(
                "CHATGPT_SEND",
                "Touch gesture successfully tapped Send button at ($clickX, $clickY)"
            )
            return@withContext BridgeResult(
                success = true,
                command = "CLICK",
                method = if (keyboardOpen) "COORDINATE_KEYBOARD_OPEN" else "COORDINATE_KEYBOARD_CLOSED",
                x = clickX,
                y = clickY,
                message = "Send button clicked at ($clickX, $clickY) [$caseLabel]",
                code = "SUCCESS",
                data = mapOf(
                    "keyboardOpen" to keyboardOpen,
                    "screenWidth" to screenWidth,
                    "screenHeight" to screenHeight,
                    "x" to clickX,
                    "y" to clickY
                )
            )
        }

        // Retry dispatch if first attempt was unconfirmed
        BridgeLogger.logAccessibility("First gesture tap unconfirmed, retrying tap at ($clickX, $clickY)...")
        val retryDispatched = service.dispatchTapGestureDirect(clickX.toFloat(), clickY.toFloat())
        if (retryDispatched) {
            return@withContext BridgeResult(
                success = true,
                command = "CLICK",
                method = "COORDINATE_RETRY",
                x = clickX,
                y = clickY,
                message = "Send button clicked on retry at ($clickX, $clickY)",
                code = "SUCCESS",
                data = mapOf(
                    "keyboardOpen" to keyboardOpen,
                    "x" to clickX,
                    "y" to clickY
                )
            )
        }

        // OpenCV Template Matching Fallback if gesture tap failed
        BridgeLogger.logOpencv("Attempting OpenCV template match fallback for Send button")
        val sendTemplateMat = OpenCVHelper.getChatGptSendTemplateMat(context, 24)
        val screenshotBitmap = service.captureActiveScreenBitmap()

        if (screenshotBitmap != null && sendTemplateMat != null && !sendTemplateMat.empty()) {
            var screenMat: Mat? = null
            try {
                screenMat = OpenCVHelper.bitmapToMat(screenshotBitmap)
                screenshotBitmap.recycle()

                val match = findBestImageMatch(screenMat, sendTemplateMat, SEND_CONFIDENCE_THRESHOLD)
                if (match != null && match.confidence >= SEND_CONFIDENCE_THRESHOLD) {
                    val x = match.point.x
                    val y = match.point.y
                    service.dispatchTapGestureDirect(x.toFloat(), y.toFloat())

                    BridgeLogger.logOpencv("Matched Send icon with ${(match.confidence * 100).toInt()}% confidence at ($x, $y)")
                    return@withContext BridgeResult.chatGptOpenCvSuccess(
                        command = "CLICK",
                        confidence = match.confidence,
                        x = x,
                        y = y,
                        message = "Clicked ChatGPT Send button via OpenCV at ($x, $y)"
                    )
                }
            } catch (e: Exception) {
                BridgeLogger.logError("OpenCV send match failed: ${e.message}")
            } finally {
                screenMat?.release()
                sendTemplateMat.release()
            }
        }

        // Return failed result if all attempts were exhausted
        return@withContext BridgeResult(
            success = false,
            command = "CLICK",
            method = "COORDINATE_FAILED",
            x = clickX,
            y = clickY,
            error = "Failed to dispatch gesture tap to Send button at ($clickX, $clickY)",
            message = "Gesture dispatch unconfirmed"
        )
    }

    /**
     * Determines real physical screen dimensions (width x height) in pixels.
     */
    fun getScreenDimensions(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return Pair(bounds.width(), bounds.height())
                }
            } else {
                @Suppress("DEPRECATION")
                val display = wm.defaultDisplay
                val realMetrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(realMetrics)
                if (realMetrics.widthPixels > 0 && realMetrics.heightPixels > 0) {
                    return Pair(realMetrics.widthPixels, realMetrics.heightPixels)
                }
            }
        }
        val dm = context.resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    /**
     * Detects whether the soft keyboard (IME) is currently open on screen:
     * 1. Inspects active window types for AccessibilityWindowInfo.TYPE_INPUT_METHOD
     * 2. Inspects vertical position of the editable prompt input node
     * 3. Inspects active window bottom boundary
     */
    fun isKeyboardOpen(service: BridgeAccessibilityService, screenHeight: Int): Boolean {
        // Signal 1: Check IME window in accessibility windows
        try {
            val windows = service.windows
            if (!windows.isNullOrEmpty()) {
                for (window in windows) {
                    if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        val rect = Rect()
                        window.getBoundsInScreen(rect)
                        if (rect.height() > 100 && rect.bottom > 0) {
                            BridgeLogger.logAccessibility("Keyboard detected as OPEN via IME window (height: ${rect.height()})")
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            BridgeLogger.logError("Error reading service windows: ${e.message}")
        }

        // Signal 2: Check active window editable node position
        try {
            val root = service.rootInActiveWindow
            if (root != null) {
                val editable = findEditableInputNode(root)
                if (editable != null) {
                    val rect = Rect()
                    editable.getBoundsInScreen(rect)
                    val centerY = rect.centerY()
                    try { editable.recycle() } catch (_: Exception) {}

                    BridgeLogger.logAccessibility("Editable input node centerY=$centerY (screenHeight=$screenHeight)")
                    // Input bar moved above keyboard: centerY is in middle region (~35% - 78% of screen)
                    if (centerY in (screenHeight * 0.35f).toInt()..(screenHeight * 0.78f).toInt()) {
                        BridgeLogger.logAccessibility("Input bar positioned in upper region -> Keyboard is OPEN")
                        return true
                    } else if (centerY > screenHeight * 0.78f) {
                        BridgeLogger.logAccessibility("Input bar positioned at screen bottom -> Keyboard is CLOSED")
                        return false
                    }
                }

                // Signal 3: Window bottom constrained by soft keyboard
                val winRect = Rect()
                root.getBoundsInScreen(winRect)
                if (winRect.bottom < screenHeight * 0.82f && winRect.bottom > screenHeight * 0.35f) {
                    BridgeLogger.logAccessibility("Window bottom is constrained (${winRect.bottom} < ${screenHeight * 0.82f}) -> Keyboard is OPEN")
                    return true
                }
            }
        } catch (e: Exception) {
            BridgeLogger.logError("Error evaluating editable input position: ${e.message}")
        }

        return false
    }

    private fun findEditableInputNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) {
                val found = findEditableInputNode(child)
                try { child.recycle() } catch (_: Exception) {}
                if (found != null) return found
            }
        }
        return null
    }

    private fun findClickablesOnRow(
        node: AccessibilityNodeInfo,
        targetCenterY: Int,
        resultList: MutableList<AccessibilityNodeInfo>
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val verticalTolerance = 80 // Tolerance around the pill bar row
        if (node.isClickable && Math.abs(rect.centerY() - targetCenterY) <= verticalTolerance) {
            resultList.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) {
                findClickablesOnRow(child, targetCenterY, resultList)
                try { child.recycle() } catch (_: Exception) {}
            }
        }
    }

    // ==================================================
    // METHOD 1 IMPLEMENTATION (Copy & other buttons)
    // ==================================================

    private fun tryAccessibilityNodeClick(
        service: BridgeAccessibilityService,
        targetButton: String
    ): BridgeResult? {
        val root = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            BridgeLogger.logError("Method 1: Failed to obtain rootInActiveWindow: ${e.message}")
            null
        } ?: return null

        val targetVariations = getTargetVariations(targetButton)
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        for (variation in targetVariations) {
            val nodes = try {
                root.findAccessibilityNodeInfosByText(variation)
            } catch (e: Exception) {
                null
            }
            if (!nodes.isNullOrEmpty()) {
                candidates.addAll(nodes)
            }
        }

        findNodesRecursive(root, targetVariations, candidates)

        if (candidates.isEmpty()) {
            return null
        }

        // Pick the candidate closest to the bottom of the screen (latest response item)
        val bestNode = candidates.maxByOrNull {
            val rect = Rect()
            it.getBoundsInScreen(rect)
            rect.bottom
        } ?: candidates[0]

        val targetRect = Rect()
        bestNode.getBoundsInScreen(targetRect)
        val trackedX = targetRect.centerX()
        val trackedY = targetRect.centerY()

        for (node in candidates) {
            if (node != bestNode) {
                try { node.recycle() } catch (_: Exception) {}
            }
        }

        var clicked = false
        if (bestNode.isClickable) {
            clicked = bestNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            var parent: AccessibilityNodeInfo? = bestNode.parent
            while (parent != null) {
                if (parent.isClickable) {
                    clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    try { parent.recycle() } catch (_: Exception) {}
                    break
                }
                val next = parent.parent
                try { parent.recycle() } catch (_: Exception) {}
                parent = next
            }
        }

        // Reinforce with gesture tap at the exact tracked coordinates
        if (trackedX > 0 && trackedY > 0) {
            service.dispatchTapGestureDirect(trackedX.toFloat(), trackedY.toFloat())
            clicked = true
        }

        try { bestNode.recycle() } catch (_: Exception) {}

        return if (clicked) {
            BridgeLogger.logCommand("CHATGPT_CLICK", "Method 1 clicked '$targetButton' at ($trackedX, $trackedY)")
            BridgeResult(
                success = true,
                command = "CLICK",
                method = "ACCESSIBILITY",
                x = trackedX,
                y = trackedY,
                message = "ChatGPT button clicked successfully",
                code = "SUCCESS"
            )
        } else {
            null
        }
    }

    private fun getTargetVariations(target: String): List<String> {
        val lower = target.lowercase().trim()
        return when (lower) {
            "copy" -> listOf("copy", "copy text", "copy code", "copy response", "copy message", "copier", "copiar")
            "send" -> listOf("send", "send prompt", "send message", "submit")
            else -> listOf(lower)
        }
    }

    private fun findNodesRecursive(
        node: AccessibilityNodeInfo,
        variations: List<String>,
        resultList: MutableList<AccessibilityNodeInfo>
    ) {
        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()
        val viewId = node.viewIdResourceName?.lowercase()

        val matches = variations.any { v ->
            (text != null && text.contains(v)) ||
            (desc != null && desc.contains(v)) ||
            (viewId != null && viewId.contains(v))
        }

        if (matches) {
            resultList.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) {
                findNodesRecursive(child, variations, resultList)
                try { child.recycle() } catch (_: Exception) {}
            }
        }
    }

    // ==================================================
    // METHOD 2 IMPLEMENTATION (OpenCV TM_CCOEFF_NORMED)
    // ==================================================

    private suspend fun tryOpenCvImageMatching(
        service: BridgeAccessibilityService,
        context: Context,
        targetButton: String
    ): BridgeResult {
        if (!OpenCVHelper.init()) {
            BridgeLogger.logError("Method 2: OpenCV is not ready or failed to initialize")
            return BridgeResult.chatGptClickFailed("CLICK", "OpenCV initialization failed on device")
        }

        val screenshotBitmap: Bitmap? = service.captureActiveScreenBitmap()
        if (screenshotBitmap == null) {
            BridgeLogger.logError("Method 2: Failed to capture active screen screenshot")
            return BridgeResult.chatGptClickFailed("CLICK", "Failed to capture screen for image matching")
        }

        var screenMat: Mat? = null
        var targetMat: Mat? = null

        try {
            screenMat = OpenCVHelper.bitmapToMat(screenshotBitmap)
            screenshotBitmap.recycle()

            val isSend = targetButton.lowercase().trim() == "send"
            targetMat = if (isSend) {
                OpenCVHelper.getChatGptSendTemplateMat(context, 24)
            } else {
                OpenCVHelper.getChatGptCopyTemplateMat(context, 24)
            }

            if (targetMat == null || targetMat.empty()) {
                BridgeLogger.logError("Method 2: Target icon template could not be loaded")
                return BridgeResult.chatGptClickFailed("CLICK", "Target icon template unavailable")
            }

            val threshold = if (isSend) SEND_CONFIDENCE_THRESHOLD else CONFIDENCE_THRESHOLD
            val match = findBestImageMatch(screenMat, targetMat, threshold)

            if (match != null && match.confidence >= threshold) {
                val pt = match.point
                val confidence = match.confidence

                BridgeLogger.logOpencv("Template matched with ${(confidence * 100).toInt()}% confidence at (${pt.x}, ${pt.y})")

                val clicked = service.dispatchTapGestureDirect(pt.x.toFloat(), pt.y.toFloat())
                if (clicked) {
                    BridgeLogger.logCommand("CHATGPT_CLICK", "Method 2 clicked target at (${pt.x}, ${pt.y}) with confidence $confidence")
                    return BridgeResult.chatGptOpenCvSuccess(
                        command = "CLICK",
                        confidence = confidence,
                        x = pt.x,
                        y = pt.y,
                        message = "ChatGPT button found and clicked using image matching"
                    )
                } else {
                    return BridgeResult.chatGptClickFailed("CLICK", "Failed to dispatch gesture tap at (${pt.x}, ${pt.y})")
                }
            } else {
                val bestConf = match?.confidence ?: 0.0
                BridgeLogger.logOpencv("Target button not found with required confidence (best: ${(bestConf * 100).toInt()}%, threshold: ${(threshold * 100).toInt()}%). No click performed.")
                return BridgeResult.chatGptClickFailed("CLICK", "Button not found using Accessibility or OpenCV")
            }
        } catch (e: Exception) {
            BridgeLogger.logError("Method 2 Exception: ${e.message}")
            return BridgeResult.chatGptClickFailed("CLICK", "OpenCV matching error: ${e.message}")
        } finally {
            screenMat?.release()
            targetMat?.release()
        }
    }

    fun findAndClickImage(
        screenMat: Mat,
        targetIconMat: Mat
    ): Point? {
        val match = findBestImageMatch(screenMat, targetIconMat, CONFIDENCE_THRESHOLD)
        return match?.point
    }

    data class ImageMatchResult(
        val point: Point,
        val confidence: Double
    )

    private fun findBestImageMatch(
        screenMat: Mat,
        targetIconMat: Mat,
        threshold: Double
    ): ImageMatchResult? {
        val grayScreen = Mat()
        val grayTarget = Mat()

        if (screenMat.channels() > 1) {
            Imgproc.cvtColor(screenMat, grayScreen, Imgproc.COLOR_RGBA2GRAY)
        } else {
            screenMat.copyTo(grayScreen)
        }

        if (targetIconMat.channels() > 1) {
            Imgproc.cvtColor(targetIconMat, grayTarget, Imgproc.COLOR_RGBA2GRAY)
        } else {
            targetIconMat.copyTo(grayTarget)
        }

        var bestMatch: ImageMatchResult? = null
        var maxConfidence = -1.0

        val scales = listOf(1.0, 0.85, 1.15, 0.70, 1.30)

        for (scale in scales) {
            val scaledTarget = if (scale == 1.0) {
                grayTarget
            } else {
                val scaled = Mat()
                val newW = (grayTarget.cols() * scale).toInt().coerceAtLeast(8)
                val newH = (grayTarget.rows() * scale).toInt().coerceAtLeast(8)
                Imgproc.resize(grayTarget, scaled, Size(newW.toDouble(), newH.toDouble()))
                scaled
            }

            if (scaledTarget.cols() > grayScreen.cols() || scaledTarget.rows() > grayScreen.rows()) {
                if (scaledTarget != grayTarget) scaledTarget.release()
                continue
            }

            val result = Mat()
            Imgproc.matchTemplate(
                grayScreen,
                scaledTarget,
                result,
                Imgproc.TM_CCOEFF_NORMED
            )

            val mmr = Core.minMaxLoc(result)
            val matchLoc = mmr.maxLoc
            val confidence = mmr.maxVal

            if (confidence > maxConfidence) {
                maxConfidence = confidence
                val centerX = matchLoc.x + (scaledTarget.cols() / 2.0)
                val centerY = matchLoc.y + (scaledTarget.rows() / 2.0)
                bestMatch = ImageMatchResult(
                    point = Point(centerX.toInt(), centerY.toInt()),
                    confidence = confidence
                )
            }

            result.release()
            if (scaledTarget != grayTarget) {
                scaledTarget.release()
            }

            if (maxConfidence >= 0.92) {
                break
            }
        }

        grayScreen.release()
        grayTarget.release()

        return if (maxConfidence >= threshold) bestMatch else null
    }
}
