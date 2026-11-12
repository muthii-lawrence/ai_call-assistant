package com.aicall.assistant.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

enum class CallStatus {
    ANSWERED_BY_AI, ANSWERED_BY_YOU, MISSED, REJECTED, AI_THEN_YOU, VOICEMAIL_LEFT
}

@Entity(tableName = "calls")
data class CallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneNumber: String,
    val contactName: String?,
    val isKnown: Boolean,
    val direction: String, // "incoming" | "outgoing"
    val startTime: Long,
    val endTime: Long,
    val durationSeconds: Int,
    val status: CallStatus,
    val hadHandover: Boolean,
    val summary: String?
)

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [ForeignKey(
        entity = CallEntity::class,
        parentColumns = ["id"],
        childColumns = ["callId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [androidx.room.Index("callId")]
)
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callId: Long,
    val speaker: String, // "ai" | "caller" -- human-mode segments are never inserted
    val text: String,
    val timestampOffsetSeconds: Float
)

@Entity(tableName = "blocked_numbers")
data class BlockedNumberEntity(
    @PrimaryKey val phoneNumber: String,
    val addedAt: Long = System.currentTimeMillis()
)
