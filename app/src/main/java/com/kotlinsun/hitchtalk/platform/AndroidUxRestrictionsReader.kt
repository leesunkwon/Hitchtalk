package com.kotlinsun.hitchtalk.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.lang.reflect.Proxy

internal data class DrivingUxRestrictions(
    val requiresDistractionOptimization: Boolean,
    val activeRestrictions: Int,
)

/** Observes platform driving restrictions. Null means the state could not be verified. */
internal class AndroidUxRestrictionsReader(
    context: Context,
    private val onValue: (DrivingUxRestrictions?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var car: Any? = null
    private var manager: Any? = null
    private var started = false
    private var closed = false

    fun start() {
        if (started || closed) return
        started = true
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            onValue(null)
            return
        }

        // The android.car shared library is optional on non-automotive devices.
        val connection = runCatching {
            val carType = Class.forName("android.car.Car")
            val listenerType = Class.forName("android.car.Car\$CarServiceLifecycleListener")
            val listener = callback(listenerType, "onLifecycleChanged") { args ->
                if (!closed) {
                    val connected = args.firstOrNull()
                    if (args.getOrNull(1) == true && connected != null) {
                        connect(carType, connected)
                    } else {
                        clearManager()
                        onValue(null)
                    }
                }
            }
            carType.getMethod(
                "createCar", Context::class.java, Handler::class.java, Long::class.javaPrimitiveType, listenerType,
            ).invoke(null, appContext, main, carType.getField("CAR_WAIT_TIMEOUT_DO_NOT_WAIT").getLong(null), listener)
        }.getOrNull()

        if (closed) disconnect(connection) else car = connection
        if (!closed && connection == null) onValue(null)
    }

    private fun connect(carType: Class<*>, connected: Any) {
        clearManager()
        val initial = runCatching {
            val service = carType.getField("CAR_UX_RESTRICTION_SERVICE").get(null)
            val next = carType.getMethod("getCarManager", String::class.java).invoke(connected, service)
                ?: return@runCatching null
            val listenerType = Class.forName(
                "android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener",
            )
            val listener = callback(listenerType, "onUxRestrictionsChanged") { args ->
                if (!closed && manager === next) onValue(read(args.firstOrNull()))
            }
            manager = next
            next.javaClass.getMethod("registerListener", listenerType).invoke(next, listener)
            read(next.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(next))
        }.getOrNull()
        if (!closed) onValue(initial)
    }

    private fun read(value: Any?): DrivingUxRestrictions? {
        if (value == null) return null
        return runCatching {
            DrivingUxRestrictions(
                value.javaClass.getMethod("isRequiresDistractionOptimization").invoke(value) as Boolean,
                value.javaClass.getMethod("getActiveRestrictions").invoke(value) as Int,
            )
        }.getOrNull()
    }

    private fun callback(type: Class<*>, methodName: String, onEvent: (Array<out Any?>) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                methodName -> {
                    onEvent(args.orEmpty())
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "HitchTalk${type.simpleName}"
                else -> null
            }
        }

    private fun clearManager() {
        val previous = manager ?: return
        manager = null
        runCatching { previous.javaClass.getMethod("unregisterListener").invoke(previous) }
    }

    private fun disconnect(connection: Any?) {
        runCatching { connection?.javaClass?.getMethod("disconnect")?.invoke(connection) }
    }

    fun close() {
        closed = true
        clearManager()
        disconnect(car)
        car = null
    }
}
