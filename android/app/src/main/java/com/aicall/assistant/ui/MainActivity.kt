package com.aicall.assistant.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aicall.assistant.R
import com.aicall.assistant.permissions.PermissionsManager
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val roleManager = getSystemService(android.app.role.RoleManager::class.java)
            val roleHeld = roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER) == true
            val registered = roleHeld && com.aicall.assistant.telecom.PhoneAccountRegistrar.register(this)
            if (!registered) {
                Toast.makeText(this, "Choose AI Call Assistant in the default phone app settings.", Toast.LENGTH_LONG).show()
                PermissionsManager.openDefaultDialerSettings(this)
            }
        }
        val permsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
        var openOutboundAfterContactsPermission = false
        var outboundNavigation: (() -> Unit)? = null
        val contactsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (openOutboundAfterContactsPermission) {
                openOutboundAfterContactsPermission = false
                if (!granted) {
                    Toast.makeText(this, "Contacts permission was denied. You can still enter a number manually.", Toast.LENGTH_LONG).show()
                }
                outboundNavigation?.invoke()
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFFF9800),
                secondary = Color(0xFFFFB74D),
                background = Color(0xFF101010),
                surface = Color(0xFF181818)
            )) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val notificationCallId = intent.getLongExtra("call_id", 0L)
                    val dialRequest = intent.action == Intent.ACTION_DIAL && intent.data?.scheme == "tel"
                    val dialNumber = if (dialRequest) intent.data?.schemeSpecificPart.orEmpty() else ""
                    val tabs = listOf(
                        AppTab("calls", "Calls", R.drawable.ic_nav_call),
                        AppTab("history", "History", R.drawable.ic_nav_history),
                        AppTab("settings", "Settings", R.drawable.ic_nav_settings)
                    )

                    Scaffold(
                        bottomBar = {
                            val destination = navController.currentBackStackEntryAsState().value?.destination
                            if (destination?.route != "call/{callId}") {
                                NavigationBar {
                                    tabs.forEach { tab ->
                                        NavigationBarItem(
                                            selected = destination?.hierarchy?.any { it.route == tab.route } == true,
                                            onClick = {
                                                navController.navigate(tab.route) {
                                                    popUpTo("calls") { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            },
                                            icon = { Icon(painterResource(tab.icon), contentDescription = tab.label) },
                                            label = { Text(tab.label) },
                                            alwaysShowLabel = false
                                        )
                                    }
                                }
                            }
                        }
                    ) { padding ->
                        NavHost(
                            navController = navController,
                            startDestination = when {
                                notificationCallId > 0L -> "call/$notificationCallId"
                                dialRequest -> "outbound"
                                else -> "calls"
                            },
                            modifier = Modifier.padding(padding)
                        ) {
                            composable("calls") {
                            CallLogScreen(
                                onOpenOutbound = {
                                    outboundNavigation = { navController.navigate("outbound") }
                                    if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        outboundNavigation?.invoke()
                                        outboundNavigation = null
                                    } else {
                                        openOutboundAfterContactsPermission = true
                                        contactsLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                                    }
                                },
                                onOpenAiCalls = { navController.navigate("ai-calls") },
                                onOpenCall = { callId -> navController.navigate("call/$callId") }
                            )
                            }
                            composable("ai-calls") {
                                TwilioCallsScreen(onBack = { navController.popBackStack() })
                            }
                            composable("history") {
                                HistoryScreen(onOpenCall = { callId -> navController.navigate("call/$callId") })
                            }
                            composable("outbound") {
                                OutboundCallScreen(
                                    initialNumber = dialNumber,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable("blocked") {
                                BlockedNumbersScreen(onBack = { navController.popBackStack() })
                            }
                            composable("settings") {
                                SettingsScreen(
                                    onRequestPermissions = {
                                        val missing = PermissionsManager.missingPermissions(this@MainActivity)
                                        if (missing.isNotEmpty()) permsLauncher.launch(missing.toTypedArray())
                                    },
                                    onRequestDialerRole = {
                                        PermissionsManager.requestDefaultDialerRole(this@MainActivity, roleLauncher)
                                    },
                                    onRequestBattery = {
                                        PermissionsManager.requestBatteryOptimizationExemption(this@MainActivity)
                                    },
                                    onOpenOemSettings = {
                                        PermissionsManager.openOemBackgroundSettings(this@MainActivity)
                                    },
                                    onOpenBlockedNumbers = { navController.navigate("blocked") },
                                    onFeedback = {
                                        startActivity(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")))
                                    },
                                    onDeleteHistory = {
                                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                            com.aicall.assistant.data.AppDatabase.get(this@MainActivity).callDao().deleteAllCalls()
                                        }
                                    }
                                )
                            }
                            composable(
                                "call/{callId}",
                                arguments = listOf(androidx.navigation.navArgument("callId") {
                                    type = androidx.navigation.NavType.LongType
                                })
                            ) { backStackEntry ->
                                val callId = backStackEntry.arguments?.getLong("callId") ?: 0L
                                CallDetailScreen(callId = callId, onBack = { navController.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class AppTab(
    val route: String,
    val label: String,
    val icon: Int
)
