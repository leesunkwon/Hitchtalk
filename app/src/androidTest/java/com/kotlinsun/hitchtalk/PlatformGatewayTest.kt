package com.kotlinsun.hitchtalk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kotlinsun.hitchtalk.platform.PleosVehicleGateway
import com.kotlinsun.hitchtalk.platform.PleosVoiceGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the real Pleos emulator services. It never logs or stores recognized speech. */
@RunWith(AndroidJUnit4::class)
class PlatformGatewayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun realVehicleReadsStayFreshAndExposeSafetyAvailability() = runBlocking {
        assumePleosVehicle()
        val gateway = withContext(Dispatchers.Main.immediate) {
            PleosVehicleGateway(context).also { it.start() }
        }
        try {
            val initial = withTimeout(12_000) {
                gateway.snapshots.first {
                    it.gear != Gear.UNKNOWN && it.turnSignalActive != null
                }
            }
            assertTrue(SystemClock.elapsedRealtime() - initial.gearObservedAt < 3_000)
            assertTrue(SystemClock.elapsedRealtime() - initial.turnObservedAt < 3_000)
            withTimeout(4_000) {
                gateway.snapshots.first {
                    it.gearObservedAt > initial.gearObservedAt &&
                        it.turnObservedAt > initial.turnObservedAt
                }
            }
            val refreshed = withTimeoutOrNull(4_000) {
                gateway.snapshots.first {
                    it.hvacOn != null && it.batteryPercent != null && it.brakePressed != null
                }
            } ?: gateway.snapshots.value
            val exteriorLightsGranted = context.checkSelfPermission(
                "android.car.permission.CAR_EXTERIOR_LIGHTS",
            ) == PackageManager.PERMISSION_GRANTED
            assertEquals("pleos", refreshed.source)
            assertEquals(refreshed.hazardActive == null, refreshed.hazardObservedAt == 0L)
            report(
                "vehicleSource" to refreshed.source,
                "gear" to refreshed.gear.name,
                "gearSource" to (refreshed.gearSource ?: "unknown"),
                "turnAvailable" to (refreshed.turnSignalActive != null).toString(),
                "turnActive" to (refreshed.turnSignalActive?.toString() ?: "unknown"),
                "hazardAvailable" to (refreshed.hazardActive != null).toString(),
                "hazardActive" to (refreshed.hazardActive?.toString() ?: "unknown"),
                "exteriorLightsGranted" to exteriorLightsGranted.toString(),
                "hvacAvailable" to (refreshed.hvacOn != null).toString(),
                "batteryAvailable" to (refreshed.batteryPercent != null).toString(),
                "brakePedalAvailable" to (refreshed.brakePressed != null).toString(),
            )
        } finally {
            withContext(Dispatchers.Main.immediate) { gateway.close() }
        }
    }

    @Test
    fun englishVoiceInitializesAndCancelsReadyListeningWithoutSpeech() = runBlocking {
        assumePleosVehicle()
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.RECORD_AUDIO,
        )
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        instrumentation.waitForIdleSync()
        // Let the foreground Activity complete its own initialization before this isolated client.
        delay(2_000)
        val gateway = withContext(Dispatchers.Main.immediate) { PleosVoiceGateway(context) }
        val events = mutableMapOf("ready" to 0, "started" to 0, "ended" to 0)
        val ready = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main.immediate) {
            gateway.onRecognitionEvent = { event ->
                events[event] = (events[event] ?: 0) + 1
                if (event == "ready") ready.complete(Unit)
            }
        }
        try {
            withTimeout(12_000) { gateway.initialize() }
            withTimeout(10_000) { gateway.speak("Hello! This is a HitchTalk voice connection test.") }
            withTimeout(3_000) { gateway.playDoor(open = false) }
            withContext(Dispatchers.Main.immediate) {
                val listening = async { gateway.listen() }
                // The SDK maps started/ended to speech EPD_ON/OFF. Silence needs no EPD_ON.
                withTimeout(6_000) { ready.await() }
                delay(200)
                gateway.cancel()
                withTimeout(2_000) { listening.cancelAndJoin() }
                assertTrue(listening.isCompleted)
            }
            // A new utterance proves the cancelled operation released its mutex and audio focus.
            withTimeout(10_000) { gateway.speak("The listening test is complete.") }
            assertEquals(false, gateway.rateSupported)
            report("englishVoice" to "ready", "silentListeningCancellation" to "completed")
        } finally {
            withContext(Dispatchers.Main.immediate) {
                report(*events.map { (event, count) -> "stt_$event" to count.toString() }.toTypedArray())
                gateway.release()
                activity.finish()
            }
        }
    }

    private fun assumePleosVehicle() {
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
    }

    private fun report(vararg values: Pair<String, String>) {
        val status = Bundle()
        values.forEach { (name, value) -> status.putString(name, value) }
        instrumentation.sendStatus(0, status)
    }
}
