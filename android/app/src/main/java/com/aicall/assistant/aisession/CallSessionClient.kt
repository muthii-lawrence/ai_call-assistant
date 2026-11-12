package com.aicall.assistant.aisession

import android.content.Context
import com.aicall.assistant.BuildConfig
import com.aicall.assistant.audio.AudioRouter
import com.aicall.assistant.data.AppDatabase
import com.aicall.assistant.data.CallEntity
import com.aicall.assistant.data.CallStatus
import com.aicall.assistant.data.TranscriptSegmentEntity
import com.aicall.assistant.settings.SettingsRepository
import com.aicall.assistant.audio.CallNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * Module 3 of the doc: WebSocket client to the backend /call-session
 * endpoint. Streams caller audio frames out, receives TTS audio + live
 * transcript events + the end-of-call summary back.
 */
object CallSessionClient {

    private val _liveTranscript = MutableStateFlow<List<String>>(emptyList())
    val liveTranscript = _liveTranscript.asStateFlow()

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming connection
        .build()

    private var webSocket: WebSocket? = null
    private var pendingSummary: String? = null
    private data class TranscriptLine(
        val speaker: String,
        val text: String,
        val offsetSeconds: Float
    )

    private val transcriptBuffer = mutableListOf<TranscriptLine>()
    private var sessionStarted = false

    suspend fun startSession(context: Context, number: String, isKnown: Boolean) {
        val settings = SettingsRepository.get(context)
        val contactName = if (isKnown) {
            com.aicall.assistant.telecom.ContactLookup.lookupContactName(context, number)
        } else null

        sessionStarted = true
        transcriptBuffer.clear()
        _liveTranscript.value = emptyList()
        pendingSummary = null

        val requestBuilder = Request.Builder().url(BuildConfig.BACKEND_WS_URL)
        if (BuildConfig.BACKEND_AUTH_TOKEN.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${BuildConfig.BACKEND_AUTH_TOKEN}")
        }
        val request = requestBuilder.build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val init = JSONObject().apply {
                    put("type", "session_init")
                    put("protocol_version", 1)
                    put("session_id", UUID.randomUUID().toString())
                    put("assistant_name", settings.assistantName)
                    put("user_name", settings.userName)
                    put("language", settings.language)
                    put("accent", settings.accent)
                    put("translation_enabled", settings.callerLanguage != settings.language)
                    put("caller_language", settings.callerLanguage)
                    put("is_known", isKnown)
                    put("contact_name", contactName)
                    put("phone_number", number)
                    put("recording_disclosure_enabled", settings.recordingDisclosureEnabled)
                }
                webSocket.send(init.toString())
                AudioRouter.routeToAi(context) { frame ->
                    webSocket.send(okio.ByteString.of(*frame))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "transcript" -> {
                        val line = TranscriptLine(
                            speaker = json.getString("speaker"),
                            text = json.getString("text"),
                            offsetSeconds = json.optDouble("timestamp_offset_seconds", 0.0).toFloat()
                        )
                        transcriptBuffer.add(line)
                        _liveTranscript.value = _liveTranscript.value + "${line.speaker}: ${line.text}"
                    }
                    "summary" -> pendingSummary = json.getString("summary")
                    "error" -> pendingSummary = "AI session error: ${json.optString("message")}" 
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                AudioRouter.playAiAudio(bytes.toByteArray())
                // the TTS vendor's output format.
            }
        })
    }

    fun sendControl(action: String) {
        val json = JSONObject().apply {
            put("type", "control")
            put("action", action)
        }
        webSocket?.send(json.toString())
    }

    fun sendAudioFrame(frame: ByteArray) {
        webSocket?.send(okio.ByteString.of(*frame))
    }

    fun persistCallOnEnd(
        context: Context,
        number: String,
        isKnown: Boolean,
        status: CallStatus,
        callStartTime: Long,
        direction: String = "incoming"
    ) {
        val endTime = System.currentTimeMillis()
        val duration = ((endTime - callStartTime) / 1000).toInt().coerceAtLeast(0)
        val contactName = if (isKnown) {
            com.aicall.assistant.telecom.ContactLookup.lookupContactName(context, number)
        } else null

        // Only calls that actually reached AI mode have a transcript/summary
        // to attach; a plain reject or manual-answer-only call has neither.
        val summaryForThisCall = if (sessionStarted) pendingSummary else null
        val transcriptForThisCall = if (sessionStarted) transcriptBuffer.toList() else emptyList()
        val hadAiSession = sessionStarted

        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.get(context)
            val callId = db.callDao().insertCallWithTranscript(
                call = CallEntity(
                    phoneNumber = number,
                    contactName = contactName,
                    isKnown = isKnown,
                    direction = direction,
                    startTime = callStartTime,
                    endTime = endTime,
                    durationSeconds = duration,
                    status = status,
                    hadHandover = status == CallStatus.AI_THEN_YOU,
                    summary = summaryForThisCall
                ),
                transcript = transcriptForThisCall.map { line ->
                    TranscriptSegmentEntity(
                        callId = 0,
                        speaker = line.speaker,
                        text = line.text,
                        timestampOffsetSeconds = line.offsetSeconds
                    )
                }
            )
            if (hadAiSession || status == CallStatus.MISSED) {
                CallNotificationManager.notifyCallEnded(
                    context,
                    callId,
                    summaryForThisCall,
                    status == CallStatus.MISSED
                )
            }
        }
        webSocket?.close(1000, "call ended")
        webSocket = null
        AudioRouter.stop(context)
        sessionStarted = false
        _liveTranscript.value = emptyList()
    }
}
