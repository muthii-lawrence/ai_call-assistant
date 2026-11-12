package com.aicall.assistant.telecom

import android.content.Intent
import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import com.aicall.assistant.audio.CallForegroundService
import com.aicall.assistant.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Required because this app owns all call-related UI/notifications
 * (doc section 1, rule 6) instead of layering on top of the stock dialer.
 */
class MyInCallService : InCallService() {

    companion object {
        @Volatile
        private var activeService: MyInCallService? = null

        fun setCallMuted(muted: Boolean) {
            activeService?.setMuted(muted)
        }

        fun setSpeakerEnabled(enabled: Boolean) {
            val endpointType = if (enabled) CallEndpoint.TYPE_SPEAKER else CallEndpoint.TYPE_EARPIECE
            activeService?.requestEndpoint(endpointType)
        }
    }

    private var availableEndpoints: List<CallEndpoint> = emptyList()

    override fun onCreate() {
        super.onCreate()
        activeService = this
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        this.availableEndpoints = availableEndpoints
    }

    private fun requestEndpoint(endpointType: Int) {
        val endpoint = availableEndpoints.firstOrNull { it.endpointType == endpointType } ?: return
        requestCallEndpointChange(endpoint, mainExecutor, object : OutcomeReceiver<Void, CallEndpointException> {
            override fun onResult(result: Void?) = Unit
            override fun onError(error: CallEndpointException) = Unit
        })
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            when (state) {
                Call.STATE_DISCONNECTED -> CallStateMachine.onCallEnded(applicationContext)
            }
        }
    }

    override fun onCallAdded(call: Call) {
        activeService = this
        call.registerCallback(callback)
        val number = call.details?.handle?.schemeSpecificPart
        val isKnown = number?.let { ContactLookup.isKnownNumber(applicationContext, it) } ?: false
        if (call.state == Call.STATE_RINGING) {
            CoroutineScope(Dispatchers.IO).launch {
                val blocked = number?.let { AppDatabase.get(applicationContext).callDao().isBlocked(it) } == true
                if (blocked) {
                    call.reject(Call.REJECT_REASON_DECLINED)
                } else {
                    CallStateMachine.onIncomingCall(applicationContext, call, number, isKnown)
                }
            }
        } else if (call.details?.callDirection == Call.Details.DIRECTION_OUTGOING) {
            CallStateMachine.onOutgoingCall(applicationContext, call)
        }
        val serviceIntent = Intent(this, CallForegroundService::class.java).apply {
            action = if (call.state == Call.STATE_RINGING) {
                CallForegroundService.ACTION_START_INCOMING
            } else {
                null
            }
        }
        try {
            startForegroundService(serviceIntent)
        } catch (_: SecurityException) {
            // Telecom remains authoritative; the user can still use the system call UI.
        }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        CallStateMachine.onCallEnded(applicationContext)
        if (getCalls().isEmpty() && activeService === this) activeService = null
        stopService(Intent(this, CallForegroundService::class.java))
    }
}
