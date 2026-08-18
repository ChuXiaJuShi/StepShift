package com.example.stepshift.util

import android.content.Context
import android.content.pm.PackageManager

/**
 * Self-check endpoint for the StepShift LSPosed module.
 *
 * When the module is enabled and this app is inside its scope, the module hooks
 * [isModuleActive] to return true and [getHookedQueueCount] to report how many
 * sensor-dispatch methods it instrumented in this process. Without the module
 * (or with the app outside the scope) the defaults below are returned, so the
 * settings UI can distinguish "installed but not active" from "active".
 */
object LsposedStatus {

    const val MODULE_PACKAGE = "com.example.stepshift.xposed"

    /** Hooked by the module to return true. Default: module not in this process. */
    @JvmStatic
    fun isModuleActive(): Boolean = false

    /** Hooked by the module to return the number of hooked sensor dispatch methods. */
    @JvmStatic
    fun getHookedQueueCount(): Int = 0

    fun getModuleVersion(context: Context): String? = try {
        context.packageManager.getPackageInfo(MODULE_PACKAGE, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
