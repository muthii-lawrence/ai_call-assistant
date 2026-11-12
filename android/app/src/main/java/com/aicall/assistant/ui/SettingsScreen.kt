package com.aicall.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aicall.assistant.settings.AppSettings
import com.aicall.assistant.settings.SettingsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onRequestPermissions: () -> Unit,
    onRequestDialerRole: () -> Unit,
    onRequestBattery: () -> Unit,
    onOpenOemSettings: () -> Unit,
    onOpenBlockedNumbers: () -> Unit,
    onFeedback: () -> Unit,
    onDeleteHistory: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsState by remember { SettingsRepository.observe(context) }
        .collectAsState(initial = AppSettings())
    var showDeleteDialog by remember { mutableStateOf(false) }

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { SettingsRepository.update(context, transform) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = settingsState.assistantName,
                onValueChange = { name -> update { it.copy(assistantName = name) } },
                label = { Text("Assistant name") }
            )
            OutlinedTextField(
                value = settingsState.userName,
                onValueChange = { name -> update { it.copy(userName = name) } },
                label = { Text("Your name") }
            )

            ToggleRow("Auto-answer known contacts", settingsState.autoAnswerKnown) {
                update { s -> s.copy(autoAnswerKnown = it) }
            }
            ToggleRow("Auto-answer unknown numbers", settingsState.autoAnswerUnknown) {
                update { s -> s.copy(autoAnswerUnknown = it) }
            }

            Text("Wait time before AI answers — known: ${settingsState.knownWaitSeconds}s")
            Slider(
                value = settingsState.knownWaitSeconds.toFloat(),
                valueRange = 0f..60f,
                enabled = settingsState.autoAnswerKnown,
                onValueChange = { v -> update { it.copy(knownWaitSeconds = v.toInt()) } }
            )

            Text("Wait time before AI answers — unknown: ${settingsState.unknownWaitSeconds}s")
            Slider(
                value = settingsState.unknownWaitSeconds.toFloat(),
                valueRange = 0f..60f,
                enabled = settingsState.autoAnswerUnknown,
                onValueChange = { v -> update { it.copy(unknownWaitSeconds = v.toInt()) } }
            )

            SegmentedChoice(
                label = "Language",
                options = listOf("en" to "English", "sw" to "Swahili"),
                selected = settingsState.language,
                onSelect = { v -> update { it.copy(language = v) } }
            )
            SegmentedChoice(
                label = "Caller language",
                options = listOf("en" to "English", "sw" to "Swahili"),
                selected = settingsState.callerLanguage,
                onSelect = { v -> update { it.copy(callerLanguage = v) } }
            )
            SegmentedChoice(
                label = "Accent",
                options = listOf("kenyan" to "Kenyan", "american" to "American"),
                selected = settingsState.accent,
                onSelect = { v -> update { it.copy(accent = v) } }
            )

            ToggleRow("Recording disclosure", settingsState.recordingDisclosureEnabled) {
                update { s -> s.copy(recordingDisclosureEnabled = it) }
            }

            HorizontalDivider()
            Text("Call setup", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onRequestPermissions) { Text("Grant required permissions") }
            Button(onClick = onRequestDialerRole) { Text("Set as default phone app") }

            HorizontalDivider()
            Text("Background reliability", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onRequestBattery) { Text("Exempt from battery optimization") }
            Button(onClick = onOpenOemSettings) { Text("Open device autostart / background settings") }
            TextButton(onClick = onOpenBlockedNumbers) { Text("Manage blocked numbers") }
            TextButton(onClick = onFeedback) { Text("Send feedback") }
            OutlinedButton(onClick = { showDeleteDialog = true }) { Text("Delete all call history") }
        }
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete all call history?") },
            text = { Text("This permanently removes calls, transcripts, and summaries.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDeleteHistory() }) { Text("Delete everything") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun SegmentedChoice(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column {
        Text(label)
        Row {
            options.forEach { (value, display) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(display) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }
}
