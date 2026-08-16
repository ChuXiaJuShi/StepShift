package com.example.stepshift

import android.app.Application
import org.osmdroid.config.Configuration
import java.io.File

class StepShiftApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize osmdroid configuration with valid browser User-Agent
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = "Mozilla/5.0 (Linux; Android 15; StepShift) AppleWebKit/537.36"
        
        // Cache and tile storage paths
        val basePath = File(cacheDir, "osmdroid")
        if (!basePath.exists()) {
            basePath.mkdirs()
        }
        osmConfig.osmdroidBasePath = basePath
        osmConfig.osmdroidTileCache = File(basePath, "tiles")
    }
}
