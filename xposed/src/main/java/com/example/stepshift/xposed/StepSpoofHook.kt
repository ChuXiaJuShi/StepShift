package com.example.stepshift.xposed

import android.hardware.Sensor
import android.hardware.SensorEvent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * LSPosed entry point: replaces the hardware step-counter value observed by the
 * hooked app (WeChat / QQ / Alipay) with StepShift's virtual step count.
 *
 * The spoof value is read from /data/local/tmp/stepshift_steps.txt, which the
 * StepShift app writes via its root shell whenever the override changes. When the
 * file is absent the real sensor value passes through untouched.
 */
class StepSpoofHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // android.hardware.SystemSensorManager$ListenerDelegate is the inner
            // dispatcher that forwards sensor events into app-registered listeners;
            // hooking its onSensorChanged covers every listener in the process.
            val delegateClass = XposedHelpers.findClass(
                "android.hardware.SystemSensorManager\$ListenerDelegate",
                lpparam.classLoader
            )

            XposedBridge.hookAllMethods(delegateClass, "onSensorChanged", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val event = param.args.getOrNull(0) as? SensorEvent ?: return
                    if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return

                    val spoof = SpoofStepStore.read() ?: return
                    if (event.values.isNotEmpty()) {
                        event.values[0] = spoof.toFloat()
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("StepShift-Xposed: hook failed: ${t.message}")
        }
    }
}

/**
 * Cached reader for the shared spoof file (2s TTL to avoid disk I/O on every
 * sensor event — the step counter fires at ~1Hz while walking).
 */
private object SpoofStepStore {
    private const val SPOOF_FILE = "/data/local/tmp/stepshift_steps.txt"
    private const val CACHE_TTL_MS = 2000L

    @Volatile
    private var cachedAtMs = 0L

    @Volatile
    private var cachedValue: Long? = null

    fun read(): Long? {
        val now = System.currentTimeMillis()
        if (now - cachedAtMs < CACHE_TTL_MS) return cachedValue
        cachedAtMs = now
        cachedValue = try {
            File(SPOOF_FILE).takeIf { it.exists() && it.canRead() }
                ?.readText()?.trim()?.toLongOrNull()
        } catch (t: Throwable) {
            null
        }
        return cachedValue
    }
}
