package com.aicall.assistant.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle

/**
 * Module 6.1 of the architecture doc.
 *
 * This is what the OS talks to when a call arrives, once this app has been
 * granted RoleManager.ROLE_DIALER. Without that role, this service simply
 * won't be asked to handle calls.
 */
class MyConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val connection = CallConnection()
        connection.setRinging()

        val number = request?.address?.schemeSpecificPart
        val isKnown = number?.let { ContactLookup.isKnownNumber(applicationContext, it) } ?: false

        // Hands off to the state machine described in section 4:
        // classify known/unknown, then either wait for manual answer or
        // start the per-type auto-answer timer.
        CallStateMachine.onIncomingCall(applicationContext, connection, number, isKnown)

        return connection
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val connection = CallConnection()
        connection.setDialing()
        // Outbound calls per doc section 1, rule 7: placed via normal system
        // dialer intent from the UI, so this mostly just needs to exist to
        // satisfy the default-dialer contract, not run custom AI logic.
        return connection
    }
}

/**
 * Thin Connection subclass; real audio-state transitions (mute/unmute for
 * handover) are driven by AudioRouter, not duplicated here.
 */
class CallConnection : Connection() {
    override fun onAnswer() {
        setActive()
    }

    override fun onReject() {
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
    }

    override fun onDisconnect() {
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }
}
