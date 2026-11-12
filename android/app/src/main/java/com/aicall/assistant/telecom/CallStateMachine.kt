package com.aicall.assistant.telecom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.Call
import android.telecom.Connection
import com.aicall.assistant.audio.AudioRouter
import com.aicall.assistant.aisession.CallSessionClient
import com.aicall.assistant.data.CallStatus
import com.aicall.assistant.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Implements the state diagram in architecture doc section 4:
 * INCOMING -> (manual answer -> HUMAN_MODE) | (timer expiry -> AI_MODE)
 * AI_MODE <-> HANDOVER_TO_HUMAN via take-over / return-to-AI
 * -> CALL_ENDING -> summary + persistence
 */
object CallStateMachine {

    private var activeCall: ActiveCall? = null
    private var autoAnswerJob: Job? = null
    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    enum class Mode { IDLE, RINGING, HUMAN, AI }

    data class CallUiState(
        val mode: Mode = Mode.IDLE,
        val number: String? = null,
        val isKnown: Boolean = false,
        val countdownSeconds: Int? = null,
        val aiAutoAnswerUnavailable: Boolean = false,
        val hadHandover: Boolean = false,
        val muted: Boolean = false,
        val speakerEnabled: Boolean = false,
        val keypadOpen: Boolean = false
    )

    data class ActiveCall(
        val connection: Connection? = null,
        val telecomCall: Call? = null,
        val number: String,
        val isKnown: Boolean,
        val direction: String = "incoming",
        var status: CallStatus,
        val startTime: Long = System.currentTimeMillis(),
        var handoverOccurred: Boolean = false
    )

    fun onIncomingCall(context: Context, connection: Connection, number: String?, isKnown: Boolean) {
        onIncomingCall(context, ActiveCall(connection = connection, number = number ?: "unknown", isKnown = isKnown, status = CallStatus.MISSED))
    }

    fun onIncomingCall(context: Context, call: Call, number: String?, isKnown: Boolean) {
        onIncomingCall(context, ActiveCall(telecomCall = call, number = number ?: "unknown", isKnown = isKnown, status = CallStatus.MISSED))
    }

    fun onOutgoingCall(context: Context, call: Call) {
        val number = call.details?.handle?.schemeSpecificPart ?: "unknown"
        activeCall = ActiveCall(
            telecomCall = call,
            number = number,
            isKnown = ContactLookup.isKnownNumber(context, number),
            direction = "outgoing",
            status = CallStatus.ANSWERED_BY_YOU
        )
        _uiState.value = CallUiState(mode = Mode.HUMAN, number = number)
    }

    private fun onIncomingCall(context: Context, call: ActiveCall) {
        autoAnswerJob?.cancel()
        activeCall = call
        _uiState.value = CallUiState(
            mode = Mode.RINGING,
            number = call.number,
            isKnown = call.isKnown
        )

        CoroutineScope(Dispatchers.IO).launch {
            val settings = SettingsRepository.get(context)
            val autoAnswerEnabled =
                if (call.isKnown) settings.autoAnswerKnown else settings.autoAnswerUnknown
            val waitSeconds =
                if (call.isKnown) settings.knownWaitSeconds else settings.unknownWaitSeconds

            if (!autoAnswerEnabled) {
                // Ring normally; wait for manual UI action (answer/reject).
                return@launch
            }
            if (!AudioRouter.CELLULAR_CALL_MEDIA_BRIDGE_AVAILABLE) {
                _uiState.value = _uiState.value.copy(aiAutoAnswerUnavailable = true)
                return@launch
            }

            autoAnswerJob = CoroutineScope(Dispatchers.Main).launch {
                var remaining = waitSeconds.coerceIn(0, 60)
                _uiState.value = _uiState.value.copy(countdownSeconds = remaining)
                while (remaining > 0 && activeCall === call && call.isRinging()) {
                    kotlinx.coroutines.delay(1000L)
                    remaining -= 1
                    _uiState.value = _uiState.value.copy(countdownSeconds = remaining)
                }
                if (activeCall === call && call.isRinging()) {
                    enterAiMode(context, call)
                }
            }
        }
    }

    /** Call this from the UI when the user taps "Answer" before the timer fires. */
    fun onManualAnswer(context: Context) {
        val call = activeCall ?: return
        autoAnswerJob?.cancel()
        call.answer()
        call.status = CallStatus.ANSWERED_BY_YOU
        _uiState.value = _uiState.value.copy(mode = Mode.HUMAN, countdownSeconds = null, muted = false)
    }

    fun onManualReject(context: Context) {
        val call = activeCall ?: return
        autoAnswerJob?.cancel()
        call.status = CallStatus.REJECTED
        _uiState.value = CallUiState()
        call.reject()
        onCallEnded(context)
    }

    fun onMessage(context: Context) {
        val call = activeCall ?: return
        onManualReject(context)
        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(call.number)}")).apply {
            putExtra("sms_body", "I can't talk right now. I'll get back to you soon.")
        })
    }

    private fun enterAiMode(context: Context, call: ActiveCall) {
        call.answer()
        call.status = CallStatus.ANSWERED_BY_AI
        _uiState.value = _uiState.value.copy(mode = Mode.AI, countdownSeconds = null)
        AudioRouter.routeToAi(context) { frame -> CallSessionClient.sendAudioFrame(frame) }
        CoroutineScope(Dispatchers.IO).launch {
            CallSessionClient.startSession(context, call.number, call.isKnown)
        }
    }

    /** Rule 2: user takes over live; AI fully muted (no listen, no speak). */
    fun onTakeOver(context: Context) {
        val call = activeCall ?: return
        call.handoverOccurred = true
        _uiState.value = _uiState.value.copy(mode = Mode.HUMAN, hadHandover = true)
        CallSessionClient.sendControl("handover_to_human")
        AudioRouter.routeToHuman(context)
    }

    fun onReturnToAi(context: Context) {
        _uiState.value = _uiState.value.copy(mode = Mode.AI)
        CallSessionClient.sendControl("return_to_ai")
        AudioRouter.routeToAi(context) { frame -> CallSessionClient.sendAudioFrame(frame) }
    }

    fun toggleMute(context: Context) {
        val next = !_uiState.value.muted
        MyInCallService.setCallMuted(next)
        _uiState.value = _uiState.value.copy(muted = next)
    }

    fun toggleSpeaker(context: Context) {
        val next = !_uiState.value.speakerEnabled
        MyInCallService.setSpeakerEnabled(next)
        _uiState.value = _uiState.value.copy(speakerEnabled = next)
    }

    fun onHangUp(context: Context) {
        val call = activeCall ?: return
        autoAnswerJob?.cancel()
        call.telecomCall?.disconnect()
        call.connection?.let {
            it.setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.LOCAL))
            it.destroy()
        }
        onCallEnded(context)
    }

    fun toggleKeypad() {
        _uiState.value = _uiState.value.copy(keypadOpen = !_uiState.value.keypadOpen)
    }

    fun sendDtmf(digit: Char) {
        activeCall?.telecomCall?.playDtmfTone(digit)
        activeCall?.telecomCall?.stopDtmfTone()
    }

    fun onCallEnded(context: Context) {
        val call = activeCall ?: return
        autoAnswerJob?.cancel()
        autoAnswerJob = null
        CallSessionClient.sendControl("call_ended")
        val finalStatus = if (call.handoverOccurred) CallStatus.AI_THEN_YOU else call.status
        CallSessionClient.persistCallOnEnd(
            context,
            call.number,
            call.isKnown,
            finalStatus,
            call.startTime,
            call.direction
        )
        activeCall = null
        _uiState.value = CallUiState()
    }

    private fun ActiveCall.isRinging(): Boolean =
        connection?.state == Connection.STATE_RINGING || telecomCall?.state == Call.STATE_RINGING

    private fun ActiveCall.answer() {
        telecomCall?.answer(0) ?: connection?.setActive()
    }

    private fun ActiveCall.reject() {
        telecomCall?.reject(Call.REJECT_REASON_DECLINED) ?: connection?.apply {
            setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.REJECTED))
            destroy()
        }
    }
}
