package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
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

    /**
     * Executes the 2-tier button click specifically for ChatGPT:
     * 1. METHOD 1 — ACCESSIBILITY NODE CLICK
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

    // ==================================================
    // METHOD 1 IMPLEMENTATION
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

        // 1. First search via findAccessibilityNodeInfosByText for exact/partial text
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

        // 2. Also search entire hierarchy for contentDescription and viewId matches
        findNodesRecursive(root, targetVariations, candidates)

        if (candidates.isEmpty()) {
            return null
        }

        // For ChatGPT chat items (like the "Copy" button under the newest response),
        // pick the candidate closest to the bottom of the screen
        val bestNode = candidates.maxByOrNull {
            val rect = android.graphics.Rect()
            it.getBoundsInScreen(rect)
            rect.bottom
        } ?: candidates[0]

        // Clean up other candidates
        for (node in candidates) {
            if (node != bestNode) {
                try { node.recycle() } catch (_: Exception) {}
            }
        }

        // Execute click on node or its clickable parent
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

        try { bestNode.recycle() } catch (_: Exception) {}

        return if (clicked) {
            BridgeLogger.logCommand("CHATGPT_CLICK", "Method 1 clicked '$targetButton' successfully via Accessibility")
            BridgeResult.chatGptAccessibilitySuccess(
                command = "CLICK",
                message = "ChatGPT button clicked successfully"
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
        // Ensure OpenCV is initialized
        if (!OpenCVHelper.init()) {
            BridgeLogger.logError("Method 2: OpenCV is not ready or failed to initialize")
            return BridgeResult.chatGptClickFailed("CLICK", "OpenCV initialization failed on device")
        }

        // 1. Capture current screenshot of ChatGPT screen
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

            // 2. Obtain template icon Mat
            targetMat = OpenCVHelper.getChatGptCopyTemplateMat(context, 24)
            if (targetMat == null || targetMat.empty()) {
                BridgeLogger.logError("Method 2: Target icon template could not be loaded")
                return BridgeResult.chatGptClickFailed("CLICK", "Target icon template unavailable")
            }

            // 3. Match template using OpenCV Imgproc.TM_CCOEFF_NORMED with multi-scale support
            val match = findBestImageMatch(screenMat, targetMat, CONFIDENCE_THRESHOLD)

            if (match != null && match.confidence >= CONFIDENCE_THRESHOLD) {
                val pt = match.point
                val confidence = match.confidence

                BridgeLogger.logOpencv("Template matched with ${(confidence * 100).toInt()}% confidence at (${pt.x}, ${pt.y})")

                // 4. Perform coordinate gesture click via dispatchGesture
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
                // Strict safety: Confidence < 0.85 -> NO CLICK, NO RANDOM TAP!
                val bestConf = match?.confidence ?: 0.0
                BridgeLogger.logOpencv("Target button not found with required confidence (best: ${(bestConf * 100).toInt()}%, threshold: ${(CONFIDENCE_THRESHOLD * 100).toInt()}%). No click performed.")
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

    /**
     * Core OpenCV findAndClickImage matching function requested by user specification:
     * Uses Imgproc.matchTemplate with Imgproc.TM_CCOEFF_NORMED and Core.minMaxLoc.
     * Evaluates multiple scales (0.8x, 1.0x, 1.2x) to support different display densities and zooms.
     */
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

        // Test scales: 1.0x (base), 0.85x, 1.15x, 0.7x, 1.3x to adapt to varied phone densities
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

            // Early exit if high confidence already reached
            if (maxConfidence >= 0.92) {
                break
            }
        }

        grayScreen.release()
        grayTarget.release()

        return if (maxConfidence >= threshold) bestMatch else null
    }
}
