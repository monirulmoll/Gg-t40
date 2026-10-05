package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.model.BridgeResult
import com.example.model.CodeBlock
import com.example.util.BridgeClipboard
import com.example.util.BridgeLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class BridgeAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastEventTime = 0L
    private val isProcessingEvent = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        BridgeLogger.logAccessibility("Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkgName = event.packageName?.toString()
        if (!pkgName.isNullOrEmpty() && pkgName != "com.android.systemui") {
            _currentForegroundPackage.value = pkgName
        }

        // Debounce high frequency events (e.g. repeated scroll/content changes)
        val now = System.currentTimeMillis()
        if (now - lastEventTime < 150) {
            return
        }
        lastEventTime = now

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            BridgeLogger.logAccessibility("Window changed", "Package: $pkgName")
        }
    }

    override fun onInterrupt() {
        BridgeLogger.logAccessibility("Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isConnected.value = false
        serviceScope.cancel()
        BridgeLogger.logAccessibility("Accessibility Service Destroyed")
    }

    // ==========================================
    // TREE READING & TEXT EXTRACTION
    // ==========================================

    fun getScreenText(includeDescriptions: Boolean = true): String {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            BridgeLogger.logError("Failed to access active window: ${e.message}")
            null
        } ?: return ""

        val resultList = mutableListOf<String>()
        val seenSet = HashSet<String>()

        try {
            collectTextRecursive(root, seenSet, resultList, includeDescriptions)
        } catch (e: Exception) {
            BridgeLogger.logError("Error walking node tree: ${e.message}")
        }

        return resultList.joinToString("\n").trim()
    }

    private fun collectTextRecursive(
        node: AccessibilityNodeInfo?,
        seenSet: MutableSet<String>,
        resultList: MutableList<String>,
        includeDescriptions: Boolean
    ) {
        if (node == null) return

        try {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && !seenSet.contains(text)) {
                // Avoid tiny status elements like clock unless meaningful
                seenSet.add(text)
                resultList.add(text)
            }

            if (includeDescriptions) {
                val desc = node.contentDescription?.toString()?.trim()
                if (!desc.isNullOrEmpty() && !seenSet.contains(desc)) {
                    seenSet.add(desc)
                    resultList.add(desc)
                }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try {
                    node.getChild(i)
                } catch (e: Exception) {
                    null
                }
                if (child != null) {
                    collectTextRecursive(child, seenSet, resultList, includeDescriptions)
                }
            }
        } catch (e: Exception) {
            // Protect against recycled or disappearing nodes during traversal
        }
    }

    // ==========================================
    // CHATGPT & ASSISTANT RESPONSE EXTRACTION
    // ==========================================

    /**
     * Extracts only the latest assistant response from the active screen.
     * Analyzes node hierarchy, roles, package names, and identifies the newest assistant message bubble.
     */
    fun getLatestAssistantResponse(): String? {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return null

        val messageCandidates = mutableListOf<MessageNodeCandidate>()
        collectMessageCandidates(root, messageCandidates)

        if (messageCandidates.isEmpty()) {
            // Fallback: collect all texts, filter out UI buttons/inputs, return last substantial text block
            val screenTexts = getScreenText(includeDescriptions = false)
                .lines()
                .map { it.trim() }
                .filter { isCandidateAssistantText(it) }

            return screenTexts.lastOrNull()
        }

        // Filter candidates: ignore editable inputs, known user labels, send button labels
        val assistantCandidates = messageCandidates.filter { candidate ->
            !candidate.isEditable &&
            !candidate.isButton &&
            isCandidateAssistantText(candidate.text)
        }

        val bestCandidate = assistantCandidates.maxByOrNull { it.bounds.bottom }
        return bestCandidate?.text?.trim()
    }

    private data class MessageNodeCandidate(
        val text: String,
        val bounds: Rect,
        val isEditable: Boolean,
        val isButton: Boolean,
        val className: String?,
        val viewId: String?
    )

    private fun collectMessageCandidates(
        node: AccessibilityNodeInfo?,
        candidates: MutableList<MessageNodeCandidate>
    ) {
        if (node == null) return

        try {
            val text = node.text?.toString()?.trim()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val isEditable = node.isEditable
            val isClickable = node.isClickable
            val className = node.className?.toString()
            val viewId = node.viewIdResourceName

            val isButton = isClickable && (className?.contains("Button") == true || viewId?.contains("button") == true)

            if (!text.isNullOrEmpty() && text.length > 2) {
                candidates.add(
                    MessageNodeCandidate(
                        text = text,
                        bounds = bounds,
                        isEditable = isEditable,
                        isButton = isButton,
                        className = className,
                        viewId = viewId
                    )
                )
            }

            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) {
                    collectMessageCandidates(child, candidates)
                }
            }
        } catch (e: Exception) {
            // Handle disappearing nodes gracefully
        }
    }

    private fun isCandidateAssistantText(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        // Skip common UI buttons and short status texts
        val ignoreList = listOf(
            "send", "send prompt", "message", "ask anything", "chatgpt", "termux",
            "cancel", "attach", "mic", "voice", "copy", "share", "search",
            "stop generating", "regenerate", "new chat", "history"
        )
        if (ignoreList.any { lower == it }) return false
        if (text.length <= 1) return false
        return true
    }

    // ==========================================
    // CODE DETECTION HELPER
    // ==========================================

    fun detectCodeBlocks(text: String): List<CodeBlock> {
        val blocks = mutableListOf<CodeBlock>()
        val regex = Regex("```(\\w*)\\r?\\n([\\s\\S]*?)```")
        val matches = regex.findAll(text)

        for (match in matches) {
            val language = match.groupValues[1].ifBlank { "text" }
            val code = match.groupValues[2] // exact preserved indentation and quotes
            blocks.add(CodeBlock(language = language, code = code))
        }
        return blocks
    }

    // ==========================================
    // UI ACTIONS IMPLEMENTATION
    // ==========================================

    fun openApp(packageName: String): BridgeResult {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launchIntent)
                BridgeLogger.logCommand("OPEN_APP", "Launched $packageName")
                BridgeResult.success("OPEN_APP", "Application '$packageName' opened", mapOf("package" to packageName))
            } else {
                BridgeLogger.logError("OPEN_APP failed", "Package not installed: $packageName")
                BridgeResult.failed("OPEN_APP", "Application package '$packageName' is not installed or cannot be launched", "NOT_FOUND")
            }
        } catch (e: Exception) {
            BridgeLogger.logError("OPEN_APP error: ${e.message}")
            BridgeResult.failed("OPEN_APP", "Failed to launch $packageName: ${e.message}")
        }
    }

    fun performTap(target: String?, x: Float? = null, y: Float? = null): BridgeResult {
        val root = try { rootInActiveWindow } catch (e: Exception) { null }

        // Strategy 1: Find node by target identifier (text, contentDescription, or viewId)
        if (!target.isNullOrBlank() && root != null) {
            val matchedNode = findNodeByTarget(root, target)
            if (matchedNode != null) {
                val clicked = performClickOnNode(matchedNode)
                if (clicked) {
                    BridgeLogger.logCommand("TAP", "Tapped node matching '$target'")
                    return BridgeResult.success("TAP", "Clicked node matching '$target'")
                }
            }
        }

        // Strategy 2: If coordinates provided, use gesture dispatching
        if (x != null && y != null) {
            val gestureDispatched = dispatchTapGesture(x, y)
            if (gestureDispatched) {
                BridgeLogger.logCommand("TAP", "Dispatched coordinate tap at ($x, $y)")
                return BridgeResult.success("TAP", "Dispatched tap at ($x, $y)")
            } else {
                return BridgeResult.failed("TAP", "Failed to dispatch tap gesture at ($x, $y)")
            }
        }

        return BridgeResult.notFound("TAP", target ?: "Unknown target (no coordinates provided)")
    }

    private fun findNodeByTarget(node: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val targetLower = target.lowercase().trim()

        // 1. By view ID
        val viewId = node.viewIdResourceName
        if (viewId != null && viewId.lowercase().contains(targetLower)) {
            return node
        }

        // 2. By text
        val text = node.text?.toString()?.lowercase()?.trim()
        if (text != null && text.contains(targetLower)) {
            return node
        }

        // 3. By content description
        val desc = node.contentDescription?.toString()?.lowercase()?.trim()
        if (desc != null && desc.contains(targetLower)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                val found = findNodeByTarget(child, target)
                if (found != null) return found
            }
        }
        return null
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        // If not clickable directly, try click anyway
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun dispatchTapGesture(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        var dispatched = false
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                dispatched = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                dispatched = false
                latch.countDown()
            }
        }, null)

        latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        return dispatched
    }

    fun performType(text: String): BridgeResult {
        val root = try { rootInActiveWindow } catch (e: Exception) { null }
            ?: return BridgeResult.failed("TYPE", "No active window found to type text")

        val editableNode = findFocusedOrFirstEditableNode(root)
            ?: return BridgeResult.notFound("TYPE", "No editable input field found on screen")

        // Primary method: ACTION_SET_TEXT
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        val success = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (success) {
            BridgeLogger.logCommand("TYPE", "Typed ${text.length} characters via ACTION_SET_TEXT")
            return BridgeResult.success("TYPE", "Text typed into editable field", mapOf("length" to text.length))
        }

        // Documented Fallback: Clipboard Paste
        BridgeLogger.logAccessibility("ACTION_SET_TEXT returned false, attempting clipboard fallback")
        BridgeClipboard.copyToClipboard(this, text)
        val pasteSuccess = editableNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        return if (pasteSuccess) {
            BridgeLogger.logCommand("TYPE", "Typed text via Clipboard Paste fallback")
            BridgeResult.success("TYPE", "Typed text via Clipboard Paste fallback", mapOf("fallback" to "ACTION_PASTE"))
        } else {
            BridgeLogger.logError("TYPE failed: ACTION_SET_TEXT and ACTION_PASTE unsupported on target node")
            BridgeResult.failed("TYPE", "Editable node rejected ACTION_SET_TEXT and ACTION_PASTE", "UNSUPPORTED")
        }
    }

    fun performPaste(): BridgeResult {
        val root = try { rootInActiveWindow } catch (e: Exception) { null }
            ?: return BridgeResult.failed("PASTE", "No active window found")

        val editableNode = findFocusedOrFirstEditableNode(root)
            ?: return BridgeResult.notFound("PASTE", "No editable field found to receive paste")

        val pasted = editableNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        return if (pasted) {
            BridgeLogger.logCommand("PASTE", "Successfully pasted clipboard into editable field")
            BridgeResult.success("PASTE", "Pasted clipboard content into active field")
        } else {
            BridgeLogger.logError("PASTE action rejected by field")
            BridgeResult.failed("PASTE", "Target editable field rejected ACTION_PASTE", "UNSUPPORTED")
        }
    }

    fun performCopy(): BridgeResult {
        val root = try { rootInActiveWindow } catch (e: Exception) { null }
            ?: return BridgeResult.failed("COPY", "No active window found")

        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        if (focusedNode != null) {
            val copied = focusedNode.performAction(AccessibilityNodeInfo.ACTION_COPY)
            if (copied) {
                BridgeLogger.logCommand("COPY", "Performed ACTION_COPY on focused node")
                return BridgeResult.success("COPY", "Copied selected text to clipboard")
            }
        }

        // Secondary fallback: Copy latest extracted assistant response or screen text
        val response = getLatestAssistantResponse() ?: getScreenText()
        if (response.isNotBlank()) {
            BridgeClipboard.copyToClipboard(this, response)
            BridgeLogger.logCommand("COPY", "Copied screen text to clipboard (${response.length} chars)")
            return BridgeResult.success("COPY", "Copied visible screen text to clipboard", mapOf("length" to response.length))
        }

        return BridgeResult.failed("COPY", "No text could be copied from current screen")
    }

    fun performEnter(): BridgeResult {
        val root = try { rootInActiveWindow } catch (e: Exception) { null }
            ?: return BridgeResult.failed("ENTER", "No active window found")

        // 1. Try ACTION_IME_ENTER on focused editable field
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && focusedNode.isEditable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val imeEnter = focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                if (imeEnter) {
                    BridgeLogger.logCommand("ENTER", "Dispatched ACTION_IME_ENTER on focused field")
                    return BridgeResult.success("ENTER", "Dispatched ACTION_IME_ENTER")
                }
            }
        }

        // 2. Locate Send button in the accessibility tree and perform ACTION_CLICK
        val sendNode = findSendButton(root)
        if (sendNode != null) {
            val clicked = performClickOnNode(sendNode)
            if (clicked) {
                BridgeLogger.logCommand("ENTER", "Clicked Send button in active window")
                return BridgeResult.success("ENTER", "Clicked Send button (${sendNode.contentDescription ?: sendNode.text ?: sendNode.viewIdResourceName})")
            }
        }

        return BridgeResult.failed("ENTER", "Could not trigger Enter or find a Send button", "NOT_FOUND")
    }

    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase()
        val text = node.text?.toString()?.lowercase()
        val viewId = node.viewIdResourceName?.lowercase()

        val isSendMatch = (desc != null && (desc.contains("send") || desc.contains("submit") || desc == "send prompt")) ||
                (text != null && (text.contains("send") || text.contains("submit"))) ||
                (viewId != null && (viewId.contains("send") || viewId.contains("submit")))

        if (isSendMatch && node.isClickable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                val found = findSendButton(child)
                if (found != null) return found
            }
        }
        return null
    }

    fun performBack(): BridgeResult {
        val success = performGlobalAction(GLOBAL_ACTION_BACK)
        return if (success) {
            BridgeLogger.logCommand("BACK", "Performed GLOBAL_ACTION_BACK")
            BridgeResult.success("BACK", "GLOBAL_ACTION_BACK executed successfully")
        } else {
            BridgeLogger.logError("BACK action failed")
            BridgeResult.failed("BACK", "performGlobalAction(GLOBAL_ACTION_BACK) returned false")
        }
    }

    private fun findFocusedOrFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Check input focus first
        val focused = node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused

        // Traversal search for editable node
        if (node.isEditable) return node

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                val found = findFocusedOrFirstEditableNode(child)
                if (found != null) return found
            }
        }
        return null
    }

    companion object {
        @Volatile
        var instance: BridgeAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected = _isConnected.asStateFlow()

        private val _currentForegroundPackage = MutableStateFlow("")
        val currentForegroundPackage = _currentForegroundPackage.asStateFlow()
    }
}
