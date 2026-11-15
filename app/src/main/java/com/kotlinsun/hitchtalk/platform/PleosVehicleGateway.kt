package com.kotlinsun.hitchtalk.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import com.kotlinsun.hitchtalk.Gear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Read-only SDK adapter. Null values are unavailable, never an inferred safe state. */
class PleosVehicleGateway(context: Context) {
    private data class GearObservation(val gear: Gear, val observedAt: Long, val source: String)

    // A listener update takes priority over any read that was already in flight.
    private class SignalUpdates {
        private var issued = 0L
        private var applied = 0L

        fun beginRead(): Long = ++issued

        fun accept(readId: Long?): Boolean {
            val id = readId ?: ++issued
            if (id <= applied) return false
            applied = id
            return true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private val mutableSnapshots = MutableStateFlow(VehicleSnapshot())
    val snapshots: StateFlow<VehicleSnapshot> = mutableSnapshots.asStateFlow()
    private var vehicle: Vehicle? = null
    private var hvacArea: SeatArea? = null
    private var started = false
    private var closed = false
    private val carSignals = AndroidCarSignalReader(appContext, ::updateHazard, ::onAaosGearChanged)
    private var pleosGear: GearObservation? = null
    private var aaosGear: GearObservation? = null
    private var aaosGearEventVersion = 0L
    private val pleosGearUpdates = SignalUpdates()
    private val turnUpdates = SignalUpdates()

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(value: VehicleGear?) = updateGear(value)
        override fun onFailed(exception: Exception) = updateGear(null)
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = updateTurn(value)
        override fun onFailed(exception: Exception) = updateTurn(null)
    }
    private val batteryListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) = updateBattery(value)
        override fun onFailed(exception: Exception) = updateBattery(null)
    }
    private val hvacListener = object : HvacPowerStateListener {
        override fun onHvacPowerStateUpdated(value: Boolean?) = updateHvac(value)
        override fun onFailed(exception: Exception) = updateHvac(null)
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(value: Boolean?) {
            update { it.copy(brakePressed = value) }
        }
        override fun onFailed(exception: Exception) {
            update { it.copy(brakePressed = null) }
        }
    }

    fun start() {
        if (started || closed) return
        started = true
        vehicle = runCatching { Vehicle(appContext).also { it.initialize() } }.getOrNull()
        registerListeners()
        carSignals.start()
        scope.launch {
            while (isActive) {
                // Getters provide a fresh observation even when an on-change property stays static.
                poll()
                delay(1_000)
            }
        }
        scope.launch {
            while (isActive) {
                val eventVersion = aaosGearEventVersion
                val gear = carSignals.readGear()
                if (eventVersion == aaosGearEventVersion) update { it.withAaosGear(gear) }
                delay(1_000)
            }
        }
    }

    private fun registerListeners() {
        val sdk = vehicle ?: return
        runCatching { sdk.getGear().registerGearSelection(gearListener) }
        runCatching { sdk.getTurnSignal().registerTurnSignalState(turnListener) }
        runCatching { sdk.getEvBattery().registerEvBatteryStateOfCharge(batteryListener) }
        runCatching { sdk.getBrake().registerBrakePedalStatus(brakeListener) }
    }

    private fun poll() {
        val sdk = vehicle ?: return
        val gearRead = pleosGearUpdates.beginRead()
        runCatching {
            sdk.getGear().getCurrentGearSelectionState({ updateGear(it, gearRead) }) { updateGear(null, gearRead) }
        }.onFailure { updateGear(null, gearRead) }
        val turnRead = turnUpdates.beginRead()
        runCatching {
            sdk.getTurnSignal().getCurrentTurnSignalState({ updateTurn(it, turnRead) }) { updateTurn(null, turnRead) }
        }.onFailure { updateTurn(null, turnRead) }
        runCatching { sdk.getEvBattery().getEvBatteryStateOfCharge(::updateBattery) { updateBattery(null) } }
            .onFailure { updateBattery(null) }
        runCatching {
            sdk.getBrake().getCurrentBrakePedalStatus(
                onComplete = { value -> update { it.copy(brakePressed = value) } },
                onFailed = { update { it.copy(brakePressed = null) } },
            )
        }.onFailure { update { it.copy(brakePressed = null) } }
        if (hvacArea == null) {
            runCatching {
                sdk.getHVAC().checkHvacPowerCapabilities(
                    onComplete = { areas: List<SeatArea> ->
                        main.post {
                            if (!closed && hvacArea == null) {
                                hvacArea = areas.firstOrNull()
                                hvacArea?.let { area ->
                                    runCatching { sdk.getHVAC().registerHvacPowerState(area, hvacListener) }
                                }
                            }
                        }
                    },
                    onFailed = { updateHvac(null) },
                )
            }
        }
        hvacArea?.let { area ->
            runCatching { sdk.getHVAC().getHvacPowerState(area, ::updateHvac) { updateHvac(null) } }
                .onFailure { updateHvac(null) }
        }
        carSignals.refresh()
    }

    private fun updateGear(value: VehicleGear?, readId: Long? = null) = update {
        if (!pleosGearUpdates.accept(readId)) return@update it
        val gear = when (value) {
            VehicleGear.GEAR_PARK -> Gear.PARK
            VehicleGear.GEAR_DRIVE -> Gear.DRIVE
            VehicleGear.GEAR_REVERSE -> Gear.REVERSE
            VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
            else -> null
        }
        pleosGear = gear?.let { GearObservation(it, SystemClock.elapsedRealtime(), "pleos") }
        it.withSelectedGear()
    }

    private fun VehicleSnapshot.withSelectedGear(): VehicleSnapshot {
        val now = SystemClock.elapsedRealtime()
        val pleos = pleosGear?.takeIf { now - it.observedAt in 0..3_000 }
        val aaos = aaosGear?.takeIf { now - it.observedAt in 0..3_000 }
        val selected = when {
            pleos != null && aaos != null && pleos.gear != aaos.gear -> null
            else -> pleos ?: aaos
        }
        return copy(
            gear = selected?.gear ?: Gear.UNKNOWN,
            gearObservedAt = selected?.observedAt ?: 0L,
            gearSource = selected?.source,
        )
    }

    private fun onAaosGearChanged(gear: Gear?) = update {
        aaosGearEventVersion++
        it.withAaosGear(gear)
    }

    private fun VehicleSnapshot.withAaosGear(gear: Gear?): VehicleSnapshot {
        aaosGear = gear?.let { GearObservation(it, SystemClock.elapsedRealtime(), "aaos") }
        return withSelectedGear()
    }

    private fun updateTurn(value: TurnSignalValue?, readId: Long? = null) = update {
        if (!turnUpdates.accept(readId)) return@update it
        it.copy(
            turnSignalActive = value?.let { signal -> signal != TurnSignalValue.OFF },
            turnObservedAt = if (value == null) 0L else SystemClock.elapsedRealtime(),
        )
    }

    private fun updateBattery(value: Int?) = update {
        it.copy(
            batteryPercent = value?.takeIf { percent -> percent in 0..100 },
            batteryObservedAt = if (value == null) 0L else SystemClock.elapsedRealtime(),
        )
    }

    private fun updateHvac(value: Boolean?) = update {
        it.copy(
            hvacOn = value,
            hvacObservedAt = if (value == null) 0L else SystemClock.elapsedRealtime(),
        )
    }

    private fun updateHazard(value: Boolean?) = update {
        it.copy(
            hazardActive = value,
            hazardObservedAt = if (value == null) 0L else SystemClock.elapsedRealtime(),
        )
    }

    private fun update(transform: (VehicleSnapshot) -> VehicleSnapshot) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { update(transform) }
            return
        }
        if (!closed) {
            mutableSnapshots.value = transform(mutableSnapshots.value).copy(
                observedAt = SystemClock.elapsedRealtime(),
            )
        }
    }

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        carSignals.close()
        vehicle?.let { sdk ->
            runCatching { sdk.getGear().unregisterGearSelection(gearListener) }
            runCatching { sdk.getTurnSignal().unregisterTurnSignalState(turnListener) }
            runCatching { sdk.getEvBattery().unregisterEvBatteryStateOfCharge(batteryListener) }
            runCatching { sdk.getBrake().unregisterBrakePedalStatus(brakeListener) }
            hvacArea?.let { area ->
                runCatching { sdk.getHVAC().unregisterHvacPowerState(area, hvacListener) }
            }
            runCatching { sdk.release() }
        }
        vehicle = null
    }
}
