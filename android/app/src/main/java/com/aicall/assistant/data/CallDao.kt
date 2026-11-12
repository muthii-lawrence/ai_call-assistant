package com.aicall.assistant.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CallDao {
    @Insert
    abstract suspend fun insertCall(call: CallEntity): Long

    @Insert
    protected abstract suspend fun insertTranscriptSegment(segment: TranscriptSegmentEntity)

    @androidx.room.Transaction
    open suspend fun insertCallWithTranscript(
        call: CallEntity,
        transcript: List<TranscriptSegmentEntity>
    ): Long {
        val callId = insertCall(call)
        transcript.forEach { insertTranscriptSegment(it.copy(callId = callId)) }
        return callId
    }

    @Query("SELECT * FROM calls ORDER BY startTime DESC")
    abstract fun observeCalls(): Flow<List<CallEntity>>

    @Query("SELECT * FROM calls WHERE id = :callId")
    abstract suspend fun getCall(callId: Long): CallEntity?

    @Query("SELECT * FROM calls WHERE id = :callId")
    abstract fun observeCall(callId: Long): Flow<CallEntity?>

    @Query("SELECT * FROM transcript_segments WHERE callId = :callId ORDER BY timestampOffsetSeconds ASC")
    abstract suspend fun getTranscriptForCall(callId: Long): List<TranscriptSegmentEntity>

    @Query("SELECT * FROM transcript_segments WHERE callId = :callId ORDER BY timestampOffsetSeconds ASC")
    abstract fun observeTranscriptForCall(callId: Long): Flow<List<TranscriptSegmentEntity>>

    @Query("DELETE FROM calls WHERE id = :callId")
    abstract suspend fun deleteCall(callId: Long)

    @Query("DELETE FROM calls")
    abstract suspend fun deleteAllCalls()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun blockNumber(number: BlockedNumberEntity)

    @Query("DELETE FROM blocked_numbers WHERE phoneNumber = :number")
    abstract suspend fun unblockNumber(number: String)

    @Query("SELECT * FROM blocked_numbers ORDER BY addedAt DESC")
    abstract fun observeBlockedNumbers(): Flow<List<BlockedNumberEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_numbers WHERE phoneNumber = :number)")
    abstract suspend fun isBlocked(number: String): Boolean
}
