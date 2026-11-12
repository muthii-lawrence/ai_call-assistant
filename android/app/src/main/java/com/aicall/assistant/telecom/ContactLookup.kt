package com.aicall.assistant.telecom

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

object ContactLookup {

    /** Known = exists in device Contacts (doc section 1 definition). */
    fun isKnownNumber(context: Context, number: String): Boolean {
        return lookupContactName(context, number) != null
    }

    fun lookupContactName(context: Context, number: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        } catch (_: SecurityException) {
            null
        }
    }
}
