package com.aicall.assistant.permissions

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionsManager {

    val REQUIRED_RUNTIME_PERMISSIONS = arrayOf(
        android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.READ_CALL_LOG,
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.POST_NOTIFICATIONS
    )

    fun missingPermissions(activity: Activity): List<String> =
        REQUIRED_RUNTIME_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(activity, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** Doc section 2.2: without ROLE_DIALER, none of the call-control features work at all. */
    fun requestDefaultDialerRole(activity: Activity, launcher: ActivityResultLauncher<Intent>) {
        try {
            val roleManager = activity.getSystemService(RoleManager::class.java) ?: return
            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                launcher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
            } else if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                openDefaultDialerSettings(activity)
            }
        } catch (_: SecurityException) {
            openDefaultDialerSettings(activity)
        } catch (_: ActivityNotFoundException) {
            openDefaultDialerSettings(activity)
        }
    }

    fun openDefaultDialerSettings(activity: Activity) {
        try {
            activity.startActivity(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, activity.packageName)
            })
        } catch (_: Exception) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    fun requestBatteryOptimizationExemption(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    /**
     * Doc section 2.3 + module 8: there is no universal API for OEM
     * autostart/background permission screens, so detect manufacturer and
     * deep-link to the known settings activity, falling back to the
     * general app-info screen if the deep link isn't present on this ROM.
     */
    fun openOemBackgroundSettings(activity: Activity) {
        val intent = when (Build.MANUFACTURER.lowercase()) {
            "samsung" -> Intent().setClassName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity"
            )
            "vivo" -> Intent().setClassName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            else -> null
        }
        try {
            if (intent != null) activity.startActivity(intent) else openAppInfoFallback(activity)
        } catch (e: Exception) {
            openAppInfoFallback(activity)
        }
    }

    private fun openAppInfoFallback(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }
}
