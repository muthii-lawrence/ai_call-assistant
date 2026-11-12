package com.aicall.assistant.ui

import android.app.Activity
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.core.content.ContextCompat

data class DialContact(val name: String, val number: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutboundCallScreen(initialNumber: String = "", onBack: () -> Unit) {
    val context = LocalContext.current
    var number by remember { mutableStateOf(initialNumber) }
    var contacts by remember { mutableStateOf<List<DialContact>>(emptyList()) }
    var hasContactsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingNumber by remember { mutableStateOf("") }
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasContactsPermission = granted
        if (!granted) {
            Toast.makeText(context, "Contacts permission denied. You can still dial a number.", Toast.LENGTH_LONG).show()
        }
    }
    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            placeCallThroughTelecom(context, pendingNumber)
        } else {
            Toast.makeText(context, "Phone permission is required to place calls.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(hasContactsPermission) {
        if (!hasContactsPermission) {
            contactsPermissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
            return@LaunchedEffect
        }
        try {
            val result = mutableListOf<DialContact>()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && result.size < 200) {
                    result += DialContact(cursor.getString(nameIndex), cursor.getString(numberIndex))
                }
            }
            contacts = result
        } catch (_: SecurityException) {
            contacts = emptyList()
        }
    }

    LaunchedEffect(initialNumber) {
        number = initialNumber
    }

    fun dial(value: String) {
        if (value.isBlank()) return
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) != true) {
            Toast.makeText(
                context,
                "AI Call Assistant is not the default phone app yet. Please set it as default to place calls.",
                Toast.LENGTH_LONG
            ).show()
            (context as? Activity)?.let { activity ->
                com.aicall.assistant.permissions.PermissionsManager.openDefaultDialerSettings(activity)
            }
            return
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingNumber = value
            callPermissionLauncher.launch(android.Manifest.permission.CALL_PHONE)
            return
        }
        placeCallThroughTelecom(context, value)
    }

    val matchingContacts = remember(number, contacts) {
        val digits = number.filter(Char::isDigit)
        if (number.isBlank()) emptyList() else contacts.filter { contact ->
            contact.name.contains(number, ignoreCase = true) ||
                (digits.isNotEmpty() && contact.number.filter(Char::isDigit).contains(digits))
        }.take(8)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("New call") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text("Search contacts or enter number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (number.isNotBlank()) {
                Button(
                    onClick = { dial(number) },
                    enabled = number.any(Char::isDigit),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Call $number") }
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(matchingContacts) { contact ->
                    ListItem(
                        headlineContent = { Text(contact.name) },
                        supportingContent = { Text(contact.number) },
                        modifier = Modifier.clickable {
                            number = contact.number
                        }
                    )
                }
            }
            listOf("123", "456", "789", "*0#").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { digit ->
                        FilledTonalButton(
                            onClick = { number += digit },
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) { Text(digit.toString(), style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { number = number.dropLast(1) }, modifier = Modifier.weight(1f)) {
                    Text("Delete")
                }
                OutlinedButton(onClick = { number = "" }, modifier = Modifier.weight(1f)) {
                    Text("Clear")
                }
            }
        }
    }
}

private fun placeCallThroughTelecom(context: android.content.Context, number: String) {
    if (number.isBlank()) return
    try {
        val telecomManager = context.getSystemService(TelecomManager::class.java)
        telecomManager.placeCall(Uri.fromParts("tel", number, null), Bundle())
    } catch (_: SecurityException) {
        Toast.makeText(context, "This app is not authorized to place calls yet.", Toast.LENGTH_LONG).show()
    } catch (_: IllegalStateException) {
        Toast.makeText(context, "The phone service is unavailable right now.", Toast.LENGTH_LONG).show()
    }
}