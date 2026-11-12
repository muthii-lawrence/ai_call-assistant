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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedNumbersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).callDao() }
    val numbers by dao.observeBlockedNumbers().collectAsState(initial = emptyList())
    Scaffold(
        topBar = { TopAppBar(title = { Text("Blocked numbers") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }
    ) { padding ->
        if (numbers.isEmpty()) {
            EmptyCallState(Modifier.padding(padding), "No blocked numbers", "Blocked callers will be rejected automatically.")
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(numbers) { blocked ->
                    ListItem(
                        headlineContent = { Text(blocked.phoneNumber) },
                        trailingContent = {
                            TextButton(onClick = { scope.launch { dao.unblockNumber(blocked.phoneNumber) } }) { Text("Remove") }
                        }
                    )
                }
            }
        }
    }
}