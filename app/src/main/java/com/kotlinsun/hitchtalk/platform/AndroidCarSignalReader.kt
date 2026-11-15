package com.kotlinsun.hitchtalk.platform

import android.content.Context
import com.kotlinsun.hitchtalk.Gear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Proxy

/** Optional AAOS reads for hazard lights and selected gear. */
internal class AndroidCarSignalReader(
    private val context: Context,
    private val onValue: (Boolean?) -> Unit,
    private val onGearValue: (Gear?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var car: Any? = null
    private var manager: Any? = null
    private var hazardCallback: Any? = null
    private var gearCallback: Any? = null
    private var callbackType: Class<*>? = null
    private var propertyId = 0
    private var gearPropertyId = 0
    private var stateOn = 1
    private var stateOff = 0
    private var hazardEventVersion = 0L
    private var refreshing = false
    private var closed = false

    fun start() {
        runCatching {
            val carClass = Class.forName("android.car.Car")
            val instance = carClass.getMethod("createCar", Context::class.java).invoke(null, context)
                ?: return
            car = instance
            manager = carClass.getMethod("getCarManager", String::class.java).invoke(
                instance, carClass.getField("PROPERTY_SERVICE").get(null),
            ) ?: return
            callbackType = Class.forName(
                "android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback"
            )
        }
        runCatching {
            gearPropertyId = Class.forName("android.car.VehiclePropertyIds")
                .getField("GEAR_SELECTION").getInt(null)
            gearCallback = registerCallback(gearPropertyId) { onGearValue(decodeGear(it)) }
        }.onFailure { onGearValue(null) }
        runCatching {
            propertyId = Class.forName("android.car.VehiclePropertyIds")
                .getField("HAZARD_LIGHTS_STATE").getInt(null)
            val lightState = Class.forName("android.car.VehicleLightState")
            stateOn = lightState.getField("STATE_ON").getInt(null)
            stateOff = lightState.getField("STATE_OFF").getInt(null)
            hazardCallback = registerCallback(propertyId) { value ->
                scope.launch {
                    hazardEventVersion++
                    emit(value as? Int)
                }
            }
            refresh()
        }.onFailure { onValue(null) }
    }

    private fun registerCallback(id: Int, onChanged: (Any?) -> Unit): Any? {
        val propertyManager = manager ?: return null
        val listenerType = callbackType ?: return null
        val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
            when (method.name) {
                "onChangeEvent" -> {
                    val value = runCatching { availableValue(args?.firstOrNull()) }.getOrNull()
                    if (!closed) onChanged(value)
                    null
                }
                "onErrorEvent" -> {
                    if (!closed) onChanged(null)
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "HitchTalkCarPropertyListener($id)"
                else -> null
            }
        }
        val registered = propertyManager.javaClass.getMethod(
            "registerCallback", listenerType, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
        ).invoke(propertyManager, listener, id, 0f)
        return listener.takeIf { registered == true }
    }

    fun refresh() {
        val propertyManager = manager ?: return
        if (closed || refreshing || propertyId == 0) return
        refreshing = true
        scope.launch {
            val eventVersion = hazardEventVersion
            try {
                val value = withContext(Dispatchers.IO) {
                    runCatching { readIntProperty(propertyManager, propertyId) }.getOrNull()
                }
                if (!closed && eventVersion == hazardEventVersion) emit(value)
            } finally {
                refreshing = false
            }
        }
    }

    suspend fun readGear(): Gear? {
        val propertyManager = manager ?: return null
        val id = gearPropertyId.takeIf { it != 0 } ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { decodeGear(readIntProperty(propertyManager, id)) }.getOrNull()
        }
    }

    private fun readIntProperty(propertyManager: Any, id: Int): Int? {
        val value = propertyManager.javaClass.getMethod(
            "getProperty", Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(propertyManager, Int::class.javaObjectType, id, 0)
        return availableValue(value) as? Int
    }

    private fun availableValue(value: Any?): Any? {
        if (value == null || value.javaClass.getMethod("getStatus").invoke(value) != 0) return null
        return value.javaClass.getMethod("getValue").invoke(value)
    }

    // Android VehicleGear values are bit flags. Unsupported values remain unavailable.
    private fun decodeGear(value: Any?): Gear? = when (value) {
        1 -> Gear.NEUTRAL
        2 -> Gear.REVERSE
        4 -> Gear.PARK
        8 -> Gear.DRIVE
        else -> null
    }

    private fun emit(value: Int?) {
        if (!closed) onValue(when (value) {
            stateOff -> false
            stateOn -> true
            else -> null
        })
    }

    fun close() {
        closed = true
        scope.cancel()
        callbackType?.let { type ->
            listOfNotNull(gearCallback, hazardCallback).forEach { listener ->
                runCatching {
                    manager?.javaClass?.getMethod("unregisterCallback", type)?.invoke(manager, listener)
                }
            }
        }
        runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
        manager = null
        car = null
    }
}
