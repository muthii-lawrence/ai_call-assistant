package com.aicall.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicall.assistant.aisession.TwilioActiveCall
import com.aicall.assistant.aisession.TwilioCallControlClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwilioCallsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var calls by remember { mutableStateOf<List<TwilioActiveCall>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var takingOver by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    suspend fun refreshCalls() {
        try {
            calls = TwilioCallControlClient.activeCalls()
            error = null
        } catch (exception: Exception) {
            error = exception.message ?: "Unable to connect to call service."
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshCalls()
            delay(3000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Calls") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { scope.launch { refreshCalls() } }) { Text("Refresh") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                calls.isEmpty() -> EmptyCallState(
                    modifier = Modifier.fillMaxSize(),
                    title = "No active AI calls",
                    message = "Incoming calls routed through your Twilio number will appear here."
                )
                else -> LazyColumn {
                    items(calls, key = { it.callSid }) { call ->
                        ListItem(
                            headlineContent = { Text(call.phoneNumber) },
                            supportingContent = { Text("AI is speaking with this caller") },
                            trailingContent = {
                                Button(
                                    enabled = takingOver == null && call.canTakeOver,
                                    onClick = {
                                        scope.launch {
                                            takingOver = call.callSid
                                            try {
                                                TwilioCallControlClient.requestTakeover(call.callSid)
                                                error = null
                                            } catch (exception: Exception) {
                                                error = exception.message ?: "Unable to take over the call."
                                            } finally {
                                                takingOver = null
                                            }
                                        }
                                    }
                                ) {
                                    Text(
                                        when {
                                            takingOver == call.callSid -> "Connecting"
                                            call.canTakeOver -> "Take over"
                                            else -> "Handover unavailable"
                                        }
                                    )
                                }
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
