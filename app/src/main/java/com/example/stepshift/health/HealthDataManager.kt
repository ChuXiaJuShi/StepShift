package com.example.stepshift.health

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.stepshift.model.SimulationSnapshot

/**
 * Health & Motion Data Manager:
 * 1. Broadcasts step counter and motion updates to system and sports apps (WeChat Sports, Keep, etc.).
 * 2. Emits standardized Health and Fitness intents.
 */
class HealthDataManager private constructor() {

    fun dispatchMotionUpdate(context: Context, snapshot: SimulationSnapshot) {
        val totalSteps = snapshot.currentSteps
        val distanceMeters = snapshot.totalDistanceMeters
        val speedKmh = snapshot.speedKmH
        val cadenceSpm = snapshot.cadenceSpm
        val caloriesKcal = (distanceMeters / 1000.0) * 60.0 * 1.036 // MET walking calories approx

        try {
            // 1. Generic StepShift Sport Broadcast
            val stepIntent = Intent("com.example.stepshift.ACTION_STEP_UPDATED").apply {
                putExtra("extra_total_steps", totalSteps)
                putExtra("extra_distance_meters", distanceMeters)
                putExtra("extra_speed_kmh", speedKmh)
                putExtra("extra_cadence_spm", cadenceSpm)
                putExtra("extra_calories_kcal", caloriesKcal)
                setPackage(context.packageName)
            }
            context.sendBroadcast(stepIntent)

            // 2. WeChat Sport / Tencent Sport compatible step counter intent
            val wechatSportIntent = Intent("com.tencent.mm.plugin.sport.ACTION_STEP_COUNTER").apply {
                putExtra("step_count", totalSteps)
                putExtra("step_timestamp", System.currentTimeMillis())
            }
            context.sendBroadcast(wechatSportIntent)

            // 3. Android Standard Pedometer / Fitness Step Intent
            val standardPedometerIntent = Intent("android.intent.action.STEP_COUNTER_UPDATED").apply {
                putExtra("steps", totalSteps)
                putExtra("distance", distanceMeters)
                putExtra("calories", caloriesKcal)
            }
            context.sendBroadcast(standardPedometerIntent)

        } catch (e: Exception) {
            Log.e("StepShiftHealth", "Failed to dispatch health broadcast", e)
        }
    }

    /**
     * Standalone step override: push an arbitrary step count (decoupled from any
     * route simulation) through the same multi-protocol broadcast channels.
     */
    fun dispatchStepOverride(context: Context, totalSteps: Long) {
        try {
            // 1. Generic StepShift Sport Broadcast
            val stepIntent = Intent("com.example.stepshift.ACTION_STEP_UPDATED").apply {
                putExtra("extra_total_steps", totalSteps)
                putExtra("extra_distance_meters", 0.0)
                putExtra("extra_speed_kmh", 0.0)
                putExtra("extra_cadence_spm", 0)
                putExtra("extra_calories_kcal", 0.0)
                setPackage(context.packageName)
            }
            context.sendBroadcast(stepIntent)

            // 2. WeChat Sport / Tencent Sport compatible step counter intent
            val wechatSportIntent = Intent("com.tencent.mm.plugin.sport.ACTION_STEP_COUNTER").apply {
                putExtra("step_count", totalSteps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                putExtra("step_timestamp", System.currentTimeMillis())
            }
            context.sendBroadcast(wechatSportIntent)

            // 3. Android Standard Pedometer / Fitness Step Intent
            val standardPedometerIntent = Intent("android.intent.action.STEP_COUNTER_UPDATED").apply {
                putExtra("steps", totalSteps)
                putExtra("distance", 0.0)
                putExtra("calories", 0.0)
            }
            context.sendBroadcast(standardPedometerIntent)
        } catch (e: Exception) {
            Log.e("StepShiftHealth", "Failed to dispatch step override broadcast", e)
        }
    }

    companion object {
        val instance by lazy { HealthDataManager() }
    }
}
