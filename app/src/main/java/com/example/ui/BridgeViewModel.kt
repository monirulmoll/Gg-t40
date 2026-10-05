package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BridgeApplication
import com.example.model.*
import com.example.service.BridgeAccessibilityService
import com.example.service.ResponseMonitor
import com.example.util.BridgeClipboard
import com.example.util.BridgeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledAppInfo(
    val appName: String,
    val packageName: String,
    val isPreset: Boolean = false
)

enum class BridgeTab {
    CONSOLE,
    LOGS,
    GUIDE
}

class BridgeViewModel(application: Application) : AndroidViewModel(application) {

    private val dispatcher = BridgeApplication.instance.commandDispatcher
    private val httpServer = BridgeApplication.instance.httpServer

    val isAccessibilityConnected: StateFlow<Boolean> = BridgeAccessibilityService.isConnected
    val currentForegroundPackage: StateFlow<String> = BridgeAccessibilityService.currentForegroundPackage
    val isServerRunning: StateFlow<Boolean> = httpServer.isRunning
    val stabilityState: StateFlow<StabilityState> = ResponseMonitor.stabilityState

    private val _selectedPackage = MutableStateFlow("com.openai.chatgpt")
    val selectedPackage = _selectedPackage.asStateFlow()

    private val _rawOutput = MutableStateFlow("Bridge Controller ready.\nSelect an app or run a test action.")
    val rawOutput = _rawOutput.asStateFlow()

    private val _lastResult = MutableStateFlow<BridgeResult?>(null)
    val lastResult = _lastResult.asStateFlow()

    private val _codeBlocks = MutableStateFlow<List<CodeBlock>>(emptyList())
    val codeBlocks = _codeBlocks.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    private val _activeTab = MutableStateFlow(BridgeTab.CONSOLE)
    val activeTab = _activeTab.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps = _installedApps.asStateFlow()

    private val _selectedLogFilter = MutableStateFlow<LogType?>(null)
    val selectedLogFilter = _selectedLogFilter.asStateFlow()

    val filteredLogs: StateFlow<List<LogEntry>> = BridgeLogger.logsFlow
        .map { logs ->
            val filter = _selectedLogFilter.value
            if (filter == null) logs else logs.filter { it.type == filter }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        loadInstalledApps()
    }

    fun setTab(tab: BridgeTab) {
        _activeTab.value = tab
    }

    fun setSelectedPackage(pkg: String) {
        _selectedPackage.value = pkg
        BridgeLogger.logSystem("Target app selected: $pkg")
    }

    fun setLogFilter(type: LogType?) {
        _selectedLogFilter.value = type
    }

    fun clearLogs() {
        viewModelScope.launch {
            dispatcher.execute(BridgeCommand(action = CommandAction.CLEAR_LOGS))
        }
    }

    fun toggleServer() {
        if (httpServer.isRunning.value) {
            httpServer.stop()
        } else {
            httpServer.start()
        }
    }

    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            BridgeLogger.logSystem("Navigated to Android Accessibility Settings")
        } catch (e: Exception) {
            BridgeLogger.logError("Failed to open accessibility settings: ${e.message}")
        }
    }

    fun openAppDetailsSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            BridgeLogger.logSystem("Navigated to App Info for Restricted Settings authorization")
        } catch (e: Exception) {
            BridgeLogger.logError("Failed to open app details: ${e.message}")
        }
    }

    fun testAccessibility(): Boolean {
        val connected = isAccessibilityConnected.value
        val msg = if (connected) "Accessibility Service is ACTIVE and CONNECTED." else "Accessibility Service is NOT CONNECTED. Please enable in Settings."
        _rawOutput.value = "Status Check:\n$msg\nForeground Package: ${currentForegroundPackage.value.ifBlank { "None" }}"
        return connected
    }

    fun openSelectedApp() {
        executeCommand(BridgeCommand(action = CommandAction.OPEN_APP, packageName = _selectedPackage.value))
    }

    fun readScreenText() {
        executeCommand(BridgeCommand(action = CommandAction.GET_SCREEN_TEXT))
    }

    fun getLatestResponse() {
        executeCommand(BridgeCommand(action = CommandAction.GET_LATEST_RESPONSE))
    }

    fun startResponseMonitor(timeoutMs: Long = 15000L, stabilityMs: Long = 1800L) {
        executeCommand(BridgeCommand(action = CommandAction.WAIT_FOR_STABLE_TEXT, timeoutMs = timeoutMs, stabilityThresholdMs = stabilityMs))
    }

    fun typeText(text: String) {
        executeCommand(BridgeCommand(action = CommandAction.TYPE, text = text))
    }

    fun pressEnter() {
        executeCommand(BridgeCommand(action = CommandAction.ENTER))
    }

    fun performPaste() {
        executeCommand(BridgeCommand(action = CommandAction.PASTE))
    }

    fun performCopy() {
        executeCommand(BridgeCommand(action = CommandAction.COPY))
    }

    fun clickChatGptButton(target: String = "Copy") {
        executeCommand(BridgeCommand(action = CommandAction.CLICK, target = target, packageName = "com.openai.chatgpt"))
    }

    fun pressBack() {
        executeCommand(BridgeCommand(action = CommandAction.BACK))
    }

    fun copyClipboard(text: String) {
        BridgeClipboard.copyToClipboard(getApplication(), text)
        _rawOutput.value = "Copied to clipboard:\n$text"
    }

    fun readClipboard() {
        viewModelScope.launch {
            val text = BridgeClipboard.readClipboard(getApplication())
            if (text != null) {
                _rawOutput.value = "Clipboard Content:\n$text"
                _lastResult.value = BridgeResult.success("READ_CLIPBOARD", "Read ${text.length} chars", mapOf("text" to text))
            } else {
                _rawOutput.value = "Clipboard is empty or inaccessible"
                _lastResult.value = BridgeResult.failed("READ_CLIPBOARD", "Clipboard is empty or inaccessible")
            }
        }
    }

    private fun executeCommand(command: BridgeCommand) {
        viewModelScope.launch {
            _isBusy.value = true
            _rawOutput.value = "Executing command: ${command.action.name}..."
            try {
                val result = dispatcher.execute(command)
                _lastResult.value = result

                val service = BridgeAccessibilityService.instance
                val rawText = when (val d = result.data) {
                    is Map<*, *> -> d["text"]?.toString() ?: ""
                    is String -> d
                    else -> ""
                }

                if (rawText.isNotBlank() && service != null) {
                    _codeBlocks.value = service.detectCodeBlocks(rawText)
                } else {
                    _codeBlocks.value = emptyList()
                }

                _rawOutput.value = result.toString()
            } catch (e: Exception) {
                val errResult = BridgeResult.failed(command.action.name, e.message ?: "Unknown error")
                _lastResult.value = errResult
                _rawOutput.value = errResult.toString()
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)

            val presets = listOf(
                InstalledAppInfo("ChatGPT", "com.openai.chatgpt", isPreset = true),
                InstalledAppInfo("Termux", "com.termux", isPreset = true),
                InstalledAppInfo("Chrome", "com.android.chrome", isPreset = true)
            )

            val installed = packages.mapNotNull { pkg ->
                val name = pkg.applicationInfo?.loadLabel(pm)?.toString()
                val pkgName = pkg.packageName
                if (!name.isNullOrBlank() && !pkgName.isNullOrBlank() && !pkgName.startsWith("com.android.internal")) {
                    InstalledAppInfo(appName = name, packageName = pkgName, isPreset = false)
                } else null
            }.sortedBy { it.appName.lowercase() }

            val combined = (presets + installed).distinctBy { it.packageName }
            _installedApps.value = combined
        }
    }
}
