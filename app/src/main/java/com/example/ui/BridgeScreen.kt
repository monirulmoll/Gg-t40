package com.example.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.CodeBlock
import com.example.model.LogEntry
import com.example.model.LogType
import com.example.model.StabilityState
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeScreen(viewModel: BridgeViewModel) {
    val context = LocalContext.current
    val isAccessibilityConnected by viewModel.isAccessibilityConnected.collectAsState()
    val isServerRunning by viewModel.isServerRunning.collectAsState()
    val currentFgPkg by viewModel.currentForegroundPackage.collectAsState()
    val stabilityState by viewModel.stabilityState.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()
    val selectedPackage by viewModel.selectedPackage.collectAsState()
    val rawOutput by viewModel.rawOutput.collectAsState()
    val codeBlocks by viewModel.codeBlocks.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()

    var showTypeDialog by remember { mutableStateOf(false) }
    var typeInputText by remember { mutableStateOf("print('Hello from Termux AI Agent!')") }
    var showAppPickerDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isAccessibilityConnected) NeonEmerald else NeonRed)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "BRIDGE CONTROLLER",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        )
                    }
                },
                actions = {
                    AssistChip(
                        onClick = { viewModel.toggleServer() },
                        label = {
                            Text(
                                if (isServerRunning) "SERVER: 8765" else "SERVER: OFF",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isServerRunning) NeonCyan else TextTertiary
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isServerRunning) Icons.Default.Sensors else Icons.Default.SensorsOff,
                                contentDescription = "Server Toggle",
                                tint = if (isServerRunning) NeonCyan else TextTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = CyberSurfaceVariant
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CyberNavy
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = CyberSurface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = activeTab == BridgeTab.CONSOLE,
                    onClick = { viewModel.setTab(BridgeTab.CONSOLE) },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Console") },
                    label = { Text("Console") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberNavy,
                        selectedTextColor = NeonCyan,
                        indicatorColor = NeonCyan
                    )
                )
                NavigationBarItem(
                    selected = activeTab == BridgeTab.LOGS,
                    onClick = { viewModel.setTab(BridgeTab.LOGS) },
                    icon = { Icon(Icons.Default.ListAlt, contentDescription = "Logs") },
                    label = { Text("Logs") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberNavy,
                        selectedTextColor = NeonCyan,
                        indicatorColor = NeonCyan
                    )
                )
                NavigationBarItem(
                    selected = activeTab == BridgeTab.GUIDE,
                    onClick = { viewModel.setTab(BridgeTab.GUIDE) },
                    icon = { Icon(Icons.Default.IntegrationInstructions, contentDescription = "Guide") },
                    label = { Text("AI Guide") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberNavy,
                        selectedTextColor = NeonCyan,
                        indicatorColor = NeonCyan
                    )
                )
            }
        },
        containerColor = CyberNavy
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Service Status Card
            StatusDashboard(
                isConnected = isAccessibilityConnected,
                isServerRunning = isServerRunning,
                fgPkg = currentFgPkg,
                stabilityState = stabilityState,
                onEnableAccessibility = { viewModel.openAccessibilitySettings(context) }
            )

            // Busy indicator
            if (isBusy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = NeonCyan,
                    trackColor = CyberSurfaceVariant
                )
            }

            // Tab Content
            when (activeTab) {
                BridgeTab.CONSOLE -> {
                    ConsoleTab(
                        viewModel = viewModel,
                        selectedPackage = selectedPackage,
                        rawOutput = rawOutput,
                        codeBlocks = codeBlocks,
                        onOpenAppPicker = { showAppPickerDialog = true },
                        onOpenTypeDialog = { showTypeDialog = true }
                    )
                }
                BridgeTab.LOGS -> {
                    LogsTab(viewModel = viewModel)
                }
                BridgeTab.GUIDE -> {
                    GuideTab()
                }
            }
        }
    }

    // Type text dialog
    if (showTypeDialog) {
        AlertDialog(
            onDismissRequest = { showTypeDialog = false },
            title = { Text("Type Test Text", color = TextPrimary) },
            text = {
                Column {
                    Text(
                        "Enter text to dispatch via ACTION_SET_TEXT to the currently focused or active input field:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = typeInputText,
                        onValueChange = { typeInputText = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = CyberBorder
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showTypeDialog = false
                        viewModel.typeText(typeInputText)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = CyberNavy)
                ) {
                    Text("Type Text")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTypeDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CyberSurface
        )
    }

    // App Picker Dialog
    if (showAppPickerDialog) {
        val installedApps by viewModel.installedApps.collectAsState()
        AppPickerDialog(
            apps = installedApps,
            selectedPkg = selectedPackage,
            onSelect = {
                viewModel.setSelectedPackage(it)
                showAppPickerDialog = false
            },
            onDismiss = { showAppPickerDialog = false }
        )
    }
}

@Composable
fun StatusDashboard(
    isConnected: Boolean,
    isServerRunning: Boolean,
    fgPkg: String,
    stabilityState: StabilityState,
    onEnableAccessibility: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = CyberSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = "Status",
                        tint = if (isConnected) NeonEmerald else NeonRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isConnected) "Accessibility Service: ACTIVE" else "Accessibility Service: INACTIVE",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isConnected) NeonEmerald else NeonRed
                            )
                        )
                        Text(
                            text = if (isConnected) "Window content & gestures ready" else "Must enable in Android settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                if (!isConnected) {
                    Button(
                        onClick = onEnableAccessibility,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonRed, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Enable", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = CyberBorder.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Foreground:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = fgPkg.ifBlank { "Detecting..." },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }

                val (stabilityColor, stabilityLabel) = when (stabilityState) {
                    StabilityState.IDLE -> TextTertiary to "IDLE"
                    StabilityState.MONITORING -> NeonCyan to "MONITORING"
                    StabilityState.CHANGING -> NeonAmber to "CHANGING..."
                    StabilityState.STABLE -> NeonEmerald to "STABLE"
                    StabilityState.TIMEOUT -> NeonRed to "TIMEOUT"
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(stabilityColor.copy(alpha = 0.15f))
                        .border(1.dp, stabilityColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stabilityLabel,
                        color = stabilityColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun ConsoleTab(
    viewModel: BridgeViewModel,
    selectedPackage: String,
    rawOutput: String,
    codeBlocks: List<CodeBlock>,
    onOpenAppPicker: () -> Unit,
    onOpenTypeDialog: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        // App Selector
        Text(
            text = "TARGET APPLICATION",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = TextSecondary
            )
        )
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedPackage == "com.openai.chatgpt",
                onClick = { viewModel.setSelectedPackage("com.openai.chatgpt") },
                label = { Text("ChatGPT") },
                leadingIcon = { Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
            FilterChip(
                selected = selectedPackage == "com.termux",
                onClick = { viewModel.setSelectedPackage("com.termux") },
                label = { Text("Termux") },
                leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
            AssistChip(
                onClick = onOpenAppPicker,
                label = {
                    Text(
                        if (selectedPackage != "com.openai.chatgpt" && selectedPackage != "com.termux")
                            selectedPackage.substringAfterLast(".")
                        else "Pick App..."
                    )
                },
                leadingIcon = { Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Automation Action Deck
        Text(
            text = "AUTOMATION ACTIONS",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = TextSecondary
            )
        )
        Spacer(modifier = Modifier.height(6.dp))

        // Action Grid Buttons
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionBtn(
                    text = "Open App",
                    icon = Icons.Default.Launch,
                    tint = NeonCyan,
                    modifier = Modifier.weight(1f)
                ) { viewModel.openSelectedApp() }

                ActionBtn(
                    text = "Test Service",
                    icon = Icons.Default.Accessibility,
                    tint = NeonEmerald,
                    modifier = Modifier.weight(1f)
                ) { viewModel.testAccessibility() }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionBtn(
                    text = "Read Screen Text",
                    icon = Icons.Default.DocumentScanner,
                    tint = NeonCyan,
                    modifier = Modifier.weight(1f)
                ) { viewModel.readScreenText() }

                ActionBtn(
                    text = "Get Response",
                    icon = Icons.Default.Forum,
                    tint = NeonEmerald,
                    modifier = Modifier.weight(1f)
                ) { viewModel.getLatestResponse() }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionBtn(
                    text = "Wait Stable Text",
                    icon = Icons.Default.HourglassBottom,
                    tint = NeonAmber,
                    modifier = Modifier.weight(1f)
                ) { viewModel.startResponseMonitor() }

                ActionBtn(
                    text = "Type Text",
                    icon = Icons.Default.Keyboard,
                    tint = NeonCyan,
                    modifier = Modifier.weight(1f)
                ) { onOpenTypeDialog() }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionBtn(
                    text = "Press Enter / Send",
                    icon = Icons.AutoMirrored.Filled.Send,
                    tint = NeonEmerald,
                    modifier = Modifier.weight(1f)
                ) { viewModel.pressEnter() }

                ActionBtn(
                    text = "Paste Field",
                    icon = Icons.Default.ContentPaste,
                    tint = NeonCyan,
                    modifier = Modifier.weight(1f)
                ) { viewModel.performPaste() }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionBtn(
                    text = "Read Clipboard",
                    icon = Icons.Default.ContentCopy,
                    tint = NeonCyan,
                    modifier = Modifier.weight(1f)
                ) { viewModel.readClipboard() }

                ActionBtn(
                    text = "Press Back",
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    tint = TextSecondary,
                    modifier = Modifier.weight(1f)
                ) { viewModel.pressBack() }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Code Blocks detected
        if (codeBlocks.isNotEmpty()) {
            Text(
                text = "DETECTED CODE BLOCKS (${codeBlocks.size})",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = NeonEmerald
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                codeBlocks.forEachIndexed { idx, block ->
                    AssistChip(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(block.code))
                            Toast.makeText(context, "Copied ${block.language} snippet #${idx + 1}", Toast.LENGTH_SHORT).show()
                        },
                        label = { Text("${block.language.uppercase()} Snippet #${idx + 1}") },
                        leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = CyberSurfaceVariant)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Terminal Output Viewer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "BRIDGE OUTPUT STREAM",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = TextSecondary
                )
            )

            Row {
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(rawOutput))
                        Toast.makeText(context, "Output copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = NeonCyan)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy", fontSize = 11.sp, color = NeonCyan)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 340.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF06090F)),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder),
            shape = RoundedCornerShape(8.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val outputScroll = rememberScrollState()
                Text(
                    text = rawOutput,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(outputScroll),
                    color = ConsoleGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun ActionBtn(
    text: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = CyberSurfaceVariant,
            contentColor = TextPrimary
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun LogsTab(viewModel: BridgeViewModel) {
    val logs by viewModel.filteredLogs.collectAsState()
    val currentFilter by viewModel.selectedLogFilter.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Filter Chips Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = currentFilter == null,
                onClick = { viewModel.setLogFilter(null) },
                label = { Text("ALL (${logs.size})") }
            )
            LogType.entries.forEach { type ->
                FilterChip(
                    selected = currentFilter == type,
                    onClick = { viewModel.setLogFilter(type) },
                    label = { Text(type.name) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RECORDED EVENTS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary
                )
            )
            Row {
                TextButton(
                    onClick = {
                        val text = logs.joinToString("\n") {
                            "[${dateFormat.format(Date(it.timestamp))}] [${it.type}] ${it.message} ${it.details ?: ""}"
                        }
                        clipboardManager.setText(AnnotatedString(text))
                        Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Copy All", fontSize = 11.sp, color = NeonCyan)
                }
                TextButton(onClick = { viewModel.clearLogs() }) {
                    Text("Clear", fontSize = 11.sp, color = NeonRed)
                }
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No activity logged yet.", color = TextTertiary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(logs, key = { it.id }) { entry ->
                    LogItemView(entry = entry, dateFormat = dateFormat)
                }
            }
        }
    }
}

@Composable
fun LogItemView(entry: LogEntry, dateFormat: SimpleDateFormat) {
    val pillColor = when (entry.type) {
        LogType.SYSTEM -> NeonCyan
        LogType.COMMAND -> NeonEmerald
        LogType.ACCESSIBILITY -> NeonAmber
        LogType.STABILITY -> Color(0xFF38BDF8)
        LogType.CLIPBOARD -> Color(0xFFA855F7)
        LogType.ERROR -> NeonRed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CyberSurface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(pillColor.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = entry.type.name,
                            color = pillColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = dateFormat.format(Date(entry.timestamp)),
                        color = TextTertiary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.message,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            if (!entry.details.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.details,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun GuideTab() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = "AI AGENT & TERMUX ARCHITECTURE",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                color = NeonCyan,
                letterSpacing = 1.sp
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Bridge Controller runs an unauthenticated Localhost HTTP & Socket API strictly on 127.0.0.1:8765. Your local AI agent (e.g. Qwen 0.5B running inside Termux or llama.cpp) communicates with this app using simple JSON HTTP POST requests.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Curl example 1
        CodeSnippetCard(
            title = "1. Launch ChatGPT",
            code = "curl -X POST http://127.0.0.1:8765/api/command \\\n" +
                    "  -H \"Content-Type: application/json\" \\\n" +
                    "  -d '{\"action\":\"OPEN_APP\",\"package\":\"com.openai.chatgpt\"}'",
            onCopy = {
                clipboardManager.setText(AnnotatedString(it))
                Toast.makeText(context, "Copied curl command", Toast.LENGTH_SHORT).show()
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Curl example 2
        CodeSnippetCard(
            title = "2. Type Prompt & Send",
            code = "# 1. Type prompt\n" +
                    "curl -X POST http://127.0.0.1:8765/api/command \\\n" +
                    "  -H \"Content-Type: application/json\" \\\n" +
                    "  -d '{\"action\":\"TYPE\",\"text\":\"Write a Python script for web scraping\"}'\n\n" +
                    "# 2. Click send / enter\n" +
                    "curl -X POST http://127.0.0.1:8765/api/command \\\n" +
                    "  -H \"Content-Type: application/json\" \\\n" +
                    "  -d '{\"action\":\"ENTER\"}'",
            onCopy = {
                clipboardManager.setText(AnnotatedString(it))
                Toast.makeText(context, "Copied curl command", Toast.LENGTH_SHORT).show()
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Curl example 3
        CodeSnippetCard(
            title = "3. Wait for Stable Response & Extract",
            code = "curl -X POST http://127.0.0.1:8765/api/command \\\n" +
                    "  -H \"Content-Type: application/json\" \\\n" +
                    "  -d '{\"action\":\"WAIT_FOR_STABLE_TEXT\",\"timeoutMs\":20000,\"stabilityMs\":1800}'",
            onCopy = {
                clipboardManager.setText(AnnotatedString(it))
                Toast.makeText(context, "Copied curl command", Toast.LENGTH_SHORT).show()
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Python Agent snippet
        CodeSnippetCard(
            title = "4. Python Integration for Termux AI Agent",
            code = "import requests, time\n\n" +
                    "BRIDGE_URL = 'http://127.0.0.1:8765/api/command'\n\n" +
                    "def send_bridge(action, **kwargs):\n" +
                    "    payload = {'action': action, **kwargs}\n" +
                    "    r = requests.post(BRIDGE_URL, json=payload, timeout=30)\n" +
                    "    return r.json()\n\n" +
                    "# Example workflow for local Qwen agent:\n" +
                    "send_bridge('OPEN_APP', package='com.openai.chatgpt')\n" +
                    "time.sleep(1.5)\n" +
                    "send_bridge('TYPE', text='Hello ChatGPT from Termux')\n" +
                    "send_bridge('ENTER')\n" +
                    "res = send_bridge('WAIT_FOR_STABLE_TEXT', timeoutMs=25000, stabilityMs=1800)\n" +
                    "latest_text = res.get('data', {}).get('text')\n" +
                    "print('Assistant response:', latest_text)",
            onCopy = {
                clipboardManager.setText(AnnotatedString(it))
                Toast.makeText(context, "Copied Python script", Toast.LENGTH_SHORT).show()
            }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun CodeSnippetCard(title: String, code: String, onCopy: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CyberSurface),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                )
                IconButton(
                    onClick = { onCopy(code) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF06090F), RoundedCornerShape(6.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = ConsoleGreen,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
fun AppPickerDialog(
    apps: List<InstalledAppInfo>,
    selectedPkg: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CyberSurface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Select Target App",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search by name or package...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CyberBorder
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val isSelected = app.packageName == selectedPkg
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) NeonCyan.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { onSelect(app.packageName) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.appName,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) NeonCyan else TextPrimary
                                    )
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberSurfaceVariant)
                ) {
                    Text("Close", color = TextPrimary)
                }
            }
        }
    }
}
