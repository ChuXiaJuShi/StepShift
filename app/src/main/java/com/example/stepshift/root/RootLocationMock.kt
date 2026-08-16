package com.example.stepshift.root

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import com.example.stepshift.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root-enabled location mock injector and background battery optimization bypass.
 * Supports GPS_PROVIDER, NETWORK_PROVIDER, and FUSED_PROVIDER on Android 12+.
 */
class RootLocationMock(
    private val rootExecutor: RootShellExecutor = RootShellExecutor.instance
) {
    private var isProviderRegistered = false

    private val providers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
    } else {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }

    /**
     * Use Root permissions to forcefully enable system location, mock location,
     * background battery whitelist, activity recognition, and health permissions.
     */
    suspend fun grantMockPermissions(context: Context): Boolean = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val commands = listOf(
            // 1. System location & mock settings
            "cmd location set-location-enabled true",
            "settings put secure location_mode 3",
            "settings put secure mock_location 1",
            "appops set $pkg android:mock_location allow",

            // 2. Location permissions
            "pm grant $pkg android.permission.ACCESS_FINE_LOCATION",
            "pm grant $pkg android.permission.ACCESS_COARSE_LOCATION",
            "pm grant $pkg android.permission.ACCESS_BACKGROUND_LOCATION",

            // 3. Background keep-alive & Doze whitelist
            "dumpsys deviceidle whitelist +$pkg",
            "appops set $pkg RUN_IN_BACKGROUND allow",
            "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "appops set $pkg WAKE_LOCK allow",

            // 4. Sensors & Health
            "appops set $pkg ACTIVITY_RECOGNITION allow",
            "pm grant $pkg android.permission.ACTIVITY_RECOGNITION",
            "pm grant $pkg android.permission.BODY_SENSORS",
            "pm grant $pkg android.permission.HIGH_SAMPLING_RATE_SENSORS",
            "pm grant $pkg android.permission.health.WRITE_STEPS",
            "pm grant $pkg android.permission.health.READ_STEPS",
            "pm grant $pkg android.permission.health.WRITE_DISTANCE",
            "pm grant $pkg android.permission.health.READ_DISTANCE",
            "pm grant $pkg android.permission.health.WRITE_TOTAL_CALORIES_BURNED",
            "pm grant $pkg android.permission.health.READ_TOTAL_CALORIES_BURNED"
        )

        var allOk = true
        for (cmd in commands) {
            val res = rootExecutor.execute(cmd)
            if (!res.isSuccess) {
                allOk = false
            }
        }
        allOk
    }

    /**
     * Ensure Android system location master switch is turned on via Root if disabled.
     */
    suspend fun ensureSystemLocationEnabled(): Boolean = withContext(Dispatchers.IO) {
        val res = rootExecutor.execute("cmd location set-location-enabled true; settings put secure location_mode 3")
        res.isSuccess
    }

    /**
     * Setup Android framework Test Providers for GPS, Network, and Fused.
     */
    fun setupTestProviders(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            for (provider in providers) {
                initProvider(lm, provider)
            }
            isProviderRegistered = true
            true
        } catch (e: Exception) {
            isProviderRegistered = false
            false
        }
    }

    private fun initProvider(lm: LocationManager, providerName: String) {
        try {
            lm.removeTestProvider(providerName)
        } catch (ignored: Exception) {
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val properties = ProviderProperties.Builder()
                    .setHasNetworkRequirement(false)
                    .setHasSatelliteRequirement(providerName == LocationManager.GPS_PROVIDER)
                    .setHasCellRequirement(false)
                    .setHasMonetaryCost(false)
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build()
                lm.addTestProvider(
                    providerName,
                    properties,
                    emptySet()
                )
            } else {
                @Suppress("DEPRECATION")
                lm.addTestProvider(
                    providerName,
                    false, // requiresNetwork
                    false, // requiresSatellite
                    false, // requiresCell
                    false, // hasMonetaryCost
                    true,  // supportsAltitude
                    true,  // supportsSpeed
                    true,  // supportsBearing
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
            }
            lm.setTestProviderEnabled(providerName, true)
        } catch (ignored: Exception) {
        }
    }

    /**
     * Push a mock location into Android Framework (GPS, Network, Fused).
     */
    fun pushLocation(
        context: Context,
        point: GeoPoint,
        speedMs: Double,
        bearing: Float,
        accuracyMeters: Float = 1.2f
    ): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false

        if (!isProviderRegistered) {
            setupTestProviders(context)
        }

        return try {
            val nowMs = System.currentTimeMillis()
            val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()

            for (provider in providers) {
                val loc = Location(provider).apply {
                    latitude = point.latitude
                    longitude = point.longitude
                    altitude = point.altitude
                    speed = speedMs.toFloat()
                    this.bearing = bearing
                    accuracy = if (provider == LocationManager.GPS_PROVIDER) accuracyMeters else accuracyMeters + 2.0f
                    time = nowMs
                    elapsedRealtimeNanos = nowElapsedNanos
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        bearingAccuracyDegrees = 2.0f
                        speedAccuracyMetersPerSecond = 0.2f
                        verticalAccuracyMeters = 1.5f
                    }
                }
                lm.setTestProviderLocation(provider, loc)
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Remove test providers on stop.
     */
    fun removeTestProviders(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        for (provider in providers) {
            try {
                lm.removeTestProvider(provider)
            } catch (ignored: Exception) {
            }
        }
        isProviderRegistered = false
    }

    companion object {
        val instance by lazy { RootLocationMock() }
    }
}
