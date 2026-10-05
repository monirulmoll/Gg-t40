package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
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

object ChatGptButtonManager {

    const val CHATGPT_PACKAGE = "com.openai.chatgpt"
    private const val CONFIDENCE_THRESHOLD = 0.85
    private const val SEND_CONFIDENCE_THRESHOLD = 0.75

    /**
     * Executes the 2-tier button click specifically for ChatGPT:
     * 1. METHOD 1 — ACCESSIBILITY NODE CLICK & COORDINATE TRACKING
     * 2. METHOD 2 — OPENCV IMAGE MATCHING FALLBACK
     */
    suspend fun clickButtonInChatGpt(
        service: BridgeAccessibilityService,
        context: Context,
        targetButton: String = "Copy"
    ): BridgeResult = withContext(Dispatchers.Default) {
        val activePackage = BridgeAccessibilityService.currentForegroundPackage.value

        // Safety check: ensure target is strictly ChatGPT
        if (activePackage != CHATGPT_PACKAGE) {
            BridgeLogger.logCommand("CHATGPT_CLICK", "Active package '$activePackage' is not $CHATGPT_PACKAGE. Running standard tap.")
            return@withContext service.performTap(targetButton)
        }

        val targetLower = targetButton.lowercase().trim()
        if (targetLower == "send" || targetLower == "submit" || targetLower == "enter") {
            return@withContext clickSendButton(service, context)
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
     * Specialized Send Button Tracking & Coordinate Click for ChatGPT:
     * Tracks the exact (X, Y) coordinates of the blue circular Send button with the upward arrow (↑).
     */
    suspend fun clickSendButton(
        service: BridgeAccessibilityService,
        context: Context
    ): BridgeResult = withContext(Dispatchers.Default) {
        BridgeLogger.logCommand("CHATGPT_SEND", "Tracking Send button (X, Y) coordinates in ChatGPT")

        val root = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            null
        }

        var inputBounds: Rect? = null

        if (root != null) {
            // Step 1: Find editable text input to establish the bottom pill baseline
            val editableNode = findEditableInputNode(root)
            if (editableNode != null) {
                val b = Rect()
                editableNode.getBoundsInScreen(b)
                inputBounds = b
                try { editableNode.recycle() } catch (_: Exception) {}
            }

            // Step 2: Search for any node with explicit send label
            val sendVariations = listOf("send", "send prompt", "send message", "submit")
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            for (v in sendVariations) {
                val nodes = root.findAccessibilityNodeInfosByText(v)
                if (!nodes.isNullOrEmpty()) {
                    candidates.addAll(nodes)
                }
            }
            findNodesRecursive(root, sendVariations, candidates)

            val explicitSendNode = candidates.maxByOrNull {
                val r = Rect()
                it.getBoundsInScreen(r)
                r.bottom
            }

            if (explicitSendNode != null) {
                val rect = Rect()
                explicitSendNode.getBoundsInScreen(rect)
                val x = rect.centerX()
                val y = rect.centerY()

                // Perform both node action and physical touch injection
                explicitSendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                service.dispatchTapGestureDirect(x.toFloat(), y.toFloat())

                try { explicitSendNode.recycle() } catch (_: Exception) {}
                for (c in candidates) { try { c.recycle() } catch (_: Exception) {} }

                BridgeLogger.logCommand("CHATGPT_SEND", "Method 1 clicked explicit Send node at ($x, $y)")
                return@withContext BridgeResult(
                    success = true,
                    command = "CLICK",
                    method = "ACCESSIBILITY",
                    x = x,
                    y = y,
                    message = "Tracked and clicked ChatGPT Send button at ($x, $y)"
                )
            }

            // Step 3: Find the rightmost clickable button on the input bar row (the blue circle with ↑)
            if (inputBounds != null) {
                val rowClickables = mutableListOf<AccessibilityNodeInfo>()
                findClickablesOnRow(root, inputBounds.centerY(), rowClickables)

                val rightmost = rowClickables.maxByOrNull {
                    val r = Rect()
                    it.getBoundsInScreen(r)
                    r.right
                }

                if (rightmost != null) {
                    val rect = Rect()
                    rightmost.getBoundsInScreen(rect)
                    val x = rect.centerX()
                    val y = rect.centerY()

                    rightmost.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    service.dispatchTapGestureDirect(x.toFloat(), y.toFloat())

                    for (c in rowClickables) { try { c.recycle() } catch (_: Exception) {} }

                    BridgeLogger.logCommand("CHATGPT_SEND", "Tracked rightmost Send button on input row at ($x, $y)")
                    return@withContext BridgeResult(
                        success = true,
                        command = "CLICK",
                        method = "ACCESSIBILITY_COORDINATE",
                        x = x,
                        y = y,
                        message = "Tracked and clicked ChatGPT Send button at ($x, $y)"
                    )
                }
            }
        }

        // Step 4: Method 2 Fallback — OpenCV Template Matching for the Upward Arrow Icon
        BridgeLogger.logOpencv("Attempting OpenCV template match for ChatGPT Send button")
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
                        message = "Tracked and clicked ChatGPT Send button via OpenCV at ($x, $y)"
                    )
                }
            } catch (e: Exception) {
                BridgeLogger.logError("OpenCV send match failed: ${e.message}")
            } finally {
                screenMat?.release()
                sendTemplateMat.release()
            }
        }

        // Step 5: Dynamic Geometrical Coordinate Fallback
        // Based on the verified ChatGPT mobile layout (Image 2):
        val dm = context.resources.displayMetrics
        val density = dm.density
        val screenWidth = dm.widthPixels

        val clickX = (screenWidth - (34 * density)).toInt()
        val clickY = if (inputBounds != null) {
            inputBounds.centerY()
        } else {
            // When keyboard is open vs closed
            (dm.heightPixels * 0.58).toInt()
        }

        service.dispatchTapGestureDirect(clickX.toFloat(), clickY.toFloat())
        BridgeLogger.logCommand("CHATGPT_SEND", "Dispatched coordinate tap to Send position ($clickX, $clickY)")

        return@withContext BridgeResult(
            success = true,
            command = "CLICK",
            method = "RELATIVE_COORDINATE",
            x = clickX,
            y = clickY,
            message = "Tracked and clicked ChatGPT Send button via relative geometry at ($clickX, $clickY)"
        )
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
