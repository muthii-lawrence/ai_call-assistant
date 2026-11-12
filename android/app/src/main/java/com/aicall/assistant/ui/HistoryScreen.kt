package com.aicall.assistant.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aicall.assistant.data.AppDatabase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpenCall: (Long) -> Unit) {
    val context = LocalContext.current
    val calls by remember { AppDatabase.get(context).callDao().observeCalls() }
        .collectAsState(initial = emptyList())

    Scaffold(topBar = { TopAppBar(title = { Text("History") }) }) { padding ->
        if (calls.isEmpty()) {
            EmptyCallState(
                modifier = Modifier.padding(padding),
                title = "No call history yet",
                message = "Completed calls, transcripts, and summaries will appear here."
            )
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(calls) { call -> CallRow(call, onClick = { onOpenCall(call.id) }) }
            }
        }
    }
}