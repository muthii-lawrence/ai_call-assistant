package com.aicall.assistant.telecom

import android.content.ComponentName
import android.content.Context
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/**
 * Real gap this fixes: holding RoleManager.ROLE_DIALER alone is not
 * enough. The Telecom framework will never route a call to
 * MyConnectionService unless a matching PhoneAccount has been registered
 * via TelecomManager.registerPhoneAccount(). Call
 * PhoneAccountRegistrar.register(context) once at app startup (and again
 * any time after the app is granted the default-dialer role, since some
 * OEMs clear registration on role changes).
 */
object PhoneAccountRegistrar {

    const val ACCOUNT_ID = "ai_call_assistant_account"

    fun handle(context: Context): PhoneAccountHandle {
        val componentName = ComponentName(context, MyConnectionService::class.java)
        return PhoneAccountHandle(componentName, ACCOUNT_ID)
    }

    fun register(context: Context): Boolean {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val phoneAccount = PhoneAccount.builder(handle(context), "AI Call Assistant")
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .build()
            telecomManager.registerPhoneAccount(phoneAccount)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    /** Doc's default-dialer role only becomes fully effective once this is true. */
    fun isEnabled(context: Context): Boolean {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        return try {
            telecomManager.getPhoneAccount(handle(context))?.isEnabled == true
        } catch (e: SecurityException) {
            false
        }
    }
}
