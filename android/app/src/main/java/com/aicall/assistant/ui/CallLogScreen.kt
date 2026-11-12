package com.aicall.assistant.ui

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.aicall.assistant.data.AppDatabase
import com.aicall.assistant.data.CallEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CallLogScreen(
    onOpenOutbound: () -> Unit,
    onOpenAiCalls: () -> Unit,
    onOpenCall: (Long) -> Unit
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("all") }
    val calls by remember {
        AppDatabase.get(context).callDao().observeCalls()
    }.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calls") },
                actions = {
                    TextButton(onClick = onOpenAiCalls) { Text("AI Calls") }
                    IconButton(onClick = onOpenOutbound) {
                        Icon(painterResource(com.aicall.assistant.R.drawable.ic_nav_call), "New call")
                    }
                }
            )
        }
    ) { padding ->
        val visibleCalls = if (filter == "missed") {
            calls.filter { it.status == com.aicall.assistant.data.CallStatus.MISSED || it.status == com.aicall.assistant.data.CallStatus.REJECTED }
        } else calls
        Column(modifier = Modifier.padding(padding)) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                FilterChip(selected = filter == "all", onClick = { filter = "all" }, label = { Text("All") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = filter == "missed", onClick = { filter = "missed" }, label = { Text("Missed") })
            }
        if (visibleCalls.isEmpty()) {
            EmptyCallState(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                title = if (filter == "missed") "No missed calls" else "No calls yet",
                message = "Your calls will appear here after they are logged."
            )
        } else {
            LazyColumn {
                items(visibleCalls) { call ->
                    CallRow(call, onClick = { onOpenCall(call.id) })
                }
            }
        }
        }
    }
}

@Composable
fun EmptyCallState(modifier: Modifier = Modifier, title: String, message: String) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallRow(call: CallEntity, onClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    Box {
        ListItem(
            headlineContent = { Text(call.contactName ?: call.phoneNumber) },
            supportingContent = {
                Column {
                    Text("${call.status.name} • ${fmt.format(Date(call.startTime))} • ${call.durationSeconds}s")
                    call.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Call back") },
                onClick = {
                    menuOpen = false
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(call.phoneNumber)}")))
                }
            )
            DropdownMenuItem(
                text = { Text("Send message") },
                onClick = {
                    menuOpen = false
                    context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(call.phoneNumber)}")))
                }
            )
            if (!call.isKnown) {
                DropdownMenuItem(
                    text = { Text("Add to contacts") },
                    onClick = {
                        menuOpen = false
                        context.startActivity(Intent(Intent.ACTION_INSERT).apply {
                            type = ContactsContract.Contacts.CONTENT_TYPE
                            putExtra(ContactsContract.Intents.Insert.PHONE, call.phoneNumber)
                        })
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Block number") },
                onClick = {
                    menuOpen = false
                    scope.launch { AppDatabase.get(context).callDao().blockNumber(com.aicall.assistant.data.BlockedNumberEntity(call.phoneNumber)) }
                }
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    menuOpen = false
                    scope.launch {
                        AppDatabase.get(context).callDao().deleteCall(call.id)
                    }
                }
            )
        }
    }
    HorizontalDivider()
}
