package com.aicall.assistant.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicall.assistant.aisession.CallSessionClient
import com.aicall.assistant.telecom.CallStateMachine

/**
 * The in-call screen from module 6.6: shows Answer/Reject while ringing,
 * then Take-over / Return-to-AI once the AI is engaged (doc section 4).
 */
class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by CallStateMachine.uiState.collectAsState()
                    val transcript by CallSessionClient.liveTranscript.collectAsState()
                    InCallScreen(
                        state = state,
                        transcript = transcript,
                        onMute = { CallStateMachine.toggleMute(this) },
                        onSpeaker = { CallStateMachine.toggleSpeaker(this) },
                        onKeypad = { CallStateMachine.toggleKeypad() },
                        onDtmf = { CallStateMachine.sendDtmf(it) },
                        onAnswer = { CallStateMachine.onManualAnswer(this) },
                        onReject = { CallStateMachine.onManualReject(this); finish() },
                        onMessage = { CallStateMachine.onMessage(this); finish() },
                        onTakeOver = { CallStateMachine.onTakeOver(this) },
                        onReturnToAi = { CallStateMachine.onReturnToAi(this) },
                        onHangUp = { CallStateMachine.onHangUp(this); finish() }
                    )
                }
            }
        }
    }
}

@Composable
fun InCallScreen(
    state: CallStateMachine.CallUiState,
    transcript: List<String>,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onKeypad: () -> Unit,
    onDtmf: (Char) -> Unit,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onMessage: () -> Unit,
    onTakeOver: () -> Unit,
    onReturnToAi: () -> Unit,
    onHangUp: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(state.number ?: "Incoming call", style = MaterialTheme.typography.titleLarge)
        Text(
            when (state.mode) {
                CallStateMachine.Mode.RINGING -> "Ringing"
                CallStateMachine.Mode.AI -> "AI is handling this call"
                CallStateMachine.Mode.HUMAN -> "You're live"
                CallStateMachine.Mode.IDLE -> "Call"
            },
            style = MaterialTheme.typography.headlineSmall
        )
        state.countdownSeconds?.let {
            Text("AI will answer in ${it}s", color = MaterialTheme.colorScheme.primary)
        }
        if (state.aiAutoAnswerUnavailable) {
            Text(
                "AI answering is unavailable for cellular calls on this device. Answer manually.",
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.mode == CallStateMachine.Mode.RINGING) {
                Button(onClick = onAnswer) { Text("Answer") }
                OutlinedButton(onClick = onReject) { Text("Decline") }
                OutlinedButton(onClick = onMessage) { Text("Message") }
            }
            if (state.mode == CallStateMachine.Mode.AI) {
                Button(onClick = onTakeOver) { Text("Take over") }
            }
            if (state.mode == CallStateMachine.Mode.HUMAN && state.hadHandover) {
                Button(onClick = onReturnToAi) { Text("Return to AI") }
            }
        }
        if (state.mode == CallStateMachine.Mode.HUMAN) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMute) { Text(if (state.muted) "Unmute" else "Mute") }
                OutlinedButton(onClick = onSpeaker) { Text(if (state.speakerEnabled) "Earpiece" else "Speaker") }
                OutlinedButton(onClick = onKeypad) { Text("Keypad") }
            }
            if (state.keypadOpen) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    listOf("1","2","3","4","5","6","7","8","9","*","0","#")
                        .chunked(3).forEach { row ->
                            Row {
                                row.forEach { digit ->
                                    TextButton(onClick = { onDtmf(digit.single()) }) { Text(digit) }
                                }
                            }
                        }
                }
            }
        }
        if (state.mode == CallStateMachine.Mode.AI && transcript.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp)) {
                transcript.takeLast(8).forEach { line ->
                    Text(line, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onHangUp, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
            Text("Hang up")
        }
    }
}
