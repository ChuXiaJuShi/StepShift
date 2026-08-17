package com.example.stepshift.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Writes the standalone virtual step count into Android Health Connect as
 * [StepsRecord] entries, so the system health dashboard and every app reading
 * Health Connect actually see the overridden steps (broadcasts alone reach
 * almost nothing on modern Android).
 *
 * Strategy: StepShift owns a set of HC record ids (persisted in prefs). Every
 * apply deletes our previous records first and then inserts fresh ones covering
 * today 00:00 -> now, so re-applying a value never double-counts. HC validates
 * a single StepsRecord to count <= 1,000,000, so larger targets are chunked
 * into multiple records.
 */
class HealthConnectWriter private constructor() {

    private fun clientOrNull(context: Context): HealthConnectClient? {
        return try {
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                HealthConnectClient.getOrCreate(context)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect unavailable", e)
            null
        }
    }

    /** Whether we currently hold the Health Connect WRITE_STEPS permission. */
    suspend fun hasWritePermission(context: Context): Boolean {
        val client = clientOrNull(context) ?: return false
        return try {
            client.permissionController.getGrantedPermissions()
                .contains(HealthPermission.getWritePermission(StepsRecord::class))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query HC permissions", e)
            false
        }
    }

    /**
     * Replace StepShift's step records in Health Connect with [totalSteps] for today.
     * @return true on success (or when steps <= 0 after cleanup), false on failure.
     */
    suspend fun applySteps(context: Context, totalSteps: Long): Boolean {
        val client = clientOrNull(context) ?: return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        return try {
            deleteOwnRecords(client, prefs)

            if (totalSteps <= 0L) {
                true
            } else {
                val zone = ZoneId.systemDefault()
                val now = Instant.now()
                val startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant()

                // HC hard limit: 1,000,000 steps per StepsRecord -> chunk if needed.
                val chunks = mutableListOf<Long>()
                var remaining = totalSteps
                while (remaining > MAX_STEPS_PER_RECORD) {
                    chunks.add(MAX_STEPS_PER_RECORD)
                    remaining -= MAX_STEPS_PER_RECORD
                }
                if (remaining > 0) chunks.add(remaining)

                val spanMillis = (now.toEpochMilli() - startOfToday.toEpochMilli()).coerceAtLeast(1L)
                val records = chunks.mapIndexed { index, count ->
                    val chunkStart = startOfToday.toEpochMilli() + spanMillis * index / chunks.size
                    val chunkEnd = startOfToday.toEpochMilli() + spanMillis * (index + 1) / chunks.size
                    StepsRecord(
                        startTime = Instant.ofEpochMilli(chunkStart),
                        startZoneOffset = null,
                        endTime = Instant.ofEpochMilli(chunkEnd),
                        endZoneOffset = null,
                        count = count,
                        metadata = Metadata.manualEntry()
                    )
                }

                val response = client.insertRecords(records)
                prefs.edit()
                    .putString(KEY_RECORD_IDS, response.recordIdsList.joinToString(","))
                    .apply()
                Log.i(TAG, "Wrote $totalSteps steps to Health Connect (${records.size} record(s))")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write steps to Health Connect", e)
            false
        }
    }

    /** Remove every StepsRecord StepShift ever wrote (virtual steps cleared). */
    suspend fun clearSteps(context: Context): Boolean {
        val client = clientOrNull(context) ?: return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            deleteOwnRecords(client, prefs)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear Health Connect steps", e)
            false
        }
    }

    private suspend fun deleteOwnRecords(client: HealthConnectClient, prefs: android.content.SharedPreferences) {
        val ids = prefs.getString(KEY_RECORD_IDS, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        if (ids.isNotEmpty()) {
            client.deleteRecords(StepsRecord::class, recordIdsList = ids, clientRecordIdsList = emptyList())
        }
        prefs.edit().remove(KEY_RECORD_IDS).apply()
    }

    companion object {
        private const val TAG = "StepShiftHealthConnect"
        private const val PREFS_NAME = "stepshift_override_prefs"
        private const val KEY_RECORD_IDS = "hc_step_record_ids"
        private const val MAX_STEPS_PER_RECORD = 1_000_000L

        val instance by lazy { HealthConnectWriter() }
    }
}
