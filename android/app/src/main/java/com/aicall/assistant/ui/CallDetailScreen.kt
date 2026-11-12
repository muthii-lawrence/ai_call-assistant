package com.aicall.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aicall.assistant.data.AppDatabase
import com.aicall.assistant.data.CallEntity
import com.aicall.assistant.data.TranscriptSegmentEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailScreen(callId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).callDao() }
    val call by remember(callId) { dao.observeCall(callId) }.collectAsState(initial = null)
    val segments by remember(callId) { dao.observeTranscriptForCall(callId) }
        .collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(call?.contactName ?: call?.phoneNumber ?: "Call") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            call?.let { c ->
                Text("Status: ${c.status.name}", style = MaterialTheme.typography.bodyMedium)
                Text("Duration: ${c.durationSeconds}s", style = MaterialTheme.typography.bodyMedium)
                if (c.hadHandover) {
                    Text("Handed over to you mid-call", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                c.summary?.let {
                    Text("Summary", style = MaterialTheme.typography.titleMedium)
                    Text(it)
                    Spacer(Modifier.height(16.dp))
                }
            }

            if (segments.isEmpty()) {
                Text(
                    "No AI-mode transcript for this call " +
                        "(nothing is recorded while you're speaking live).",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text("Transcript (AI-mode only)", style = MaterialTheme.typography.titleMedium)
                LazyColumn {
                    items(segments) { seg ->
                        val label = if (seg.speaker == "ai") "Assistant" else "Caller"
                        Text("$label: ${seg.text}", modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}
