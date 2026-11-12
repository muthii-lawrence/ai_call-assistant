package com.aicall.assistant.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val assistantName: String = "Assistant",
    val userName: String = "User",
    val autoAnswerKnown: Boolean = false,
    val autoAnswerUnknown: Boolean = true,
    val knownWaitSeconds: Int = 20,
    val unknownWaitSeconds: Int = 10,
    val language: String = "en",       // "en" | "sw"
    val callerLanguage: String = "en", // "en" | "sw"
    val accent: String = "kenyan",     // "kenyan" | "american"
    val recordingDisclosureEnabled: Boolean = true
)

object SettingsRepository {
    private object Keys {
        val ASSISTANT_NAME = stringPreferencesKey("assistant_name")
        val USER_NAME = stringPreferencesKey("user_name")
        val AUTO_ANSWER_KNOWN = booleanPreferencesKey("auto_answer_known")
        val AUTO_ANSWER_UNKNOWN = booleanPreferencesKey("auto_answer_unknown")
        val KNOWN_WAIT_SECONDS = intPreferencesKey("known_wait_seconds")
        val UNKNOWN_WAIT_SECONDS = intPreferencesKey("unknown_wait_seconds")
        val LANGUAGE = stringPreferencesKey("language")
        val CALLER_LANGUAGE = stringPreferencesKey("caller_language")
        val ACCENT = stringPreferencesKey("accent")
        val RECORDING_DISCLOSURE = booleanPreferencesKey("recording_disclosure")
    }

    fun observe(context: Context): Flow<AppSettings> =
        context.dataStore.data.map { prefs ->
            AppSettings(
                assistantName = (prefs[Keys.ASSISTANT_NAME] ?: "Assistant").trim().ifEmpty { "Assistant" },
                userName = (prefs[Keys.USER_NAME] ?: "User").trim().ifEmpty { "User" },
                autoAnswerKnown = prefs[Keys.AUTO_ANSWER_KNOWN] ?: false,
                autoAnswerUnknown = prefs[Keys.AUTO_ANSWER_UNKNOWN] ?: true,
                knownWaitSeconds = (prefs[Keys.KNOWN_WAIT_SECONDS] ?: 20).coerceIn(0, 60),
                unknownWaitSeconds = (prefs[Keys.UNKNOWN_WAIT_SECONDS] ?: 10).coerceIn(0, 60),
                language = (prefs[Keys.LANGUAGE] ?: "en").takeIf { it == "en" || it == "sw" } ?: "en",
                callerLanguage = (prefs[Keys.CALLER_LANGUAGE] ?: "en").takeIf { it == "en" || it == "sw" } ?: "en",
                accent = (prefs[Keys.ACCENT] ?: "kenyan").takeIf { it == "kenyan" || it == "american" } ?: "kenyan",
                recordingDisclosureEnabled = prefs[Keys.RECORDING_DISCLOSURE] ?: true
            )
        }

    suspend fun get(context: Context): AppSettings = observe(context).first()

    suspend fun update(context: Context, transform: (AppSettings) -> AppSettings) {
        val current = get(context)
        val updated = transform(current)
        context.dataStore.edit { prefs ->
            prefs[Keys.ASSISTANT_NAME] = updated.assistantName.trim().ifEmpty { "Assistant" }
            prefs[Keys.USER_NAME] = updated.userName.trim().ifEmpty { "User" }
            prefs[Keys.AUTO_ANSWER_KNOWN] = updated.autoAnswerKnown
            prefs[Keys.AUTO_ANSWER_UNKNOWN] = updated.autoAnswerUnknown
            prefs[Keys.KNOWN_WAIT_SECONDS] = updated.knownWaitSeconds.coerceIn(0, 60)
            prefs[Keys.UNKNOWN_WAIT_SECONDS] = updated.unknownWaitSeconds.coerceIn(0, 60)
            prefs[Keys.LANGUAGE] = updated.language.takeIf { it == "en" || it == "sw" } ?: "en"
            prefs[Keys.CALLER_LANGUAGE] = updated.callerLanguage.takeIf { it == "en" || it == "sw" } ?: "en"
            prefs[Keys.ACCENT] = updated.accent.takeIf { it == "kenyan" || it == "american" } ?: "kenyan"
            prefs[Keys.RECORDING_DISCLOSURE] = updated.recordingDisclosureEnabled
        }
    }
}
