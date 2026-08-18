package com.example.stepshift.xposed

import android.hardware.Sensor
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * LSPosed entry point: replaces the hardware step-counter value observed by the
 * hooked app (WeChat / QQ / Alipay) with StepShift's virtual step count.
 *
 * Hook target (verified against API 36 framework.jar on Pixel 10):
 *   android.hardware.SystemSensorManager$SensorEventQueue
 *     .dispatchSensorEvent(int handle, float[] values, int accuracy, long timestamp)
 * The values array is the raw event payload — rewriting it in place is the
 * standard injection point and covers every listener in the process.
 * (The legacy SystemSensorManager$ListenerDelegate no longer exists on API 23+.)
 *
 * The spoof value is read from /data/local/tmp/stepshift_steps.txt, written by
 * the StepShift app via its root shell. When the file is absent the real sensor
 * value passes through untouched.
 */
class StepSpoofHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        var hookedCount = 0
        try {
            val queueClass = XposedHelpers.findClass(
                "android.hardware.SystemSensorManager\$SensorEventQueue",
                lpparam.classLoader
            )

            XposedBridge.hookAllMethods(queueClass, "dispatchSensorEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // dispatchSensorEvent(int handle, float[] values, int accuracy, long timestamp)
                    val args = param.args
                    if (args.size < 2) return
                    val handle = args[0] as? Int ?: return
                    val values = args[1] as? FloatArray ?: return
                    if (values.isEmpty()) return

                    if (!isStepCounter(param.thisObject, handle)) return

                    val spoof = SpoofStepStore.read() ?: return
                    values[0] = spoof.toFloat()
                }
            })
            hookedCount++
        } catch (t: Throwable) {
            XposedBridge.log("StepShift-Xposed: hook failed in ${lpparam.packageName}: ${t.message}")
        }

        // Self-status hook: when running inside the StepShift app itself, let it
        // detect that the module is alive and report the sensor-hook count.
        if (lpparam.packageName == HOST_APP_PACKAGE) {
            try {
                val statusClass = XposedHelpers.findClass(
                    "com.example.stepshift.util.LsposedStatus",
                    lpparam.classLoader
                )
                XposedBridge.hookAllMethods(statusClass, "isModuleActive", XC_MethodReplacement.returnConstant(true))
                XposedBridge.hookAllMethods(statusClass, "getHookedQueueCount", XC_MethodReplacement.returnConstant(hookedCount))
            } catch (t: Throwable) {
                XposedBridge.log("StepShift-Xposed: self-status hook failed: ${t.message}")
            }
        }

        XposedBridge.log("StepShift-Xposed: initialized in ${lpparam.packageName}, queues hooked=$hookedCount")
    }

    /** Resolve handle -> Sensor via SystemSensorManager.mHandleToSensor and check the type. */
    private fun isStepCounter(queue: Any, handle: Int): Boolean {
        return try {
            val manager = XposedHelpers.getObjectField(queue, "mManager")
            val map = XposedHelpers.getObjectField(manager, "mHandleToSensor") as? Map<*, *> ?: return false
            val sensor = map[handle] as? Sensor ?: return false
            sensor.type == Sensor.TYPE_STEP_COUNTER
        } catch (t: Throwable) {
            false
        }
    }

    private companion object {
        const val HOST_APP_PACKAGE = "com.example.stepshift"
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
