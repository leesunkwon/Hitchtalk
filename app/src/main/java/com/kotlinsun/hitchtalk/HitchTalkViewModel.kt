package com.kotlinsun.hitchtalk

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.kotlinsun.hitchtalk.ai.OpenAiConversationClient
import com.kotlinsun.hitchtalk.ai.OpenAiException
import com.kotlinsun.hitchtalk.ai.OpenAiFailure
import com.kotlinsun.hitchtalk.ai.OpenAiKeyStore
import com.kotlinsun.hitchtalk.data.ConfirmedMemory
import com.kotlinsun.hitchtalk.data.CorrectionRecord
import com.kotlinsun.hitchtalk.data.HitchTalkRepository
import com.kotlinsun.hitchtalk.data.SaveResult
import com.kotlinsun.hitchtalk.data.PublicProfileRulesException
import com.kotlinsun.hitchtalk.data.ScoreRecord
import com.kotlinsun.hitchtalk.data.SessionRecord
import com.kotlinsun.hitchtalk.data.TravelerProfile
import com.kotlinsun.hitchtalk.data.UserPreferences
import com.kotlinsun.hitchtalk.data.UserProfile
import com.kotlinsun.hitchtalk.domain.MemoryCandidate
import com.kotlinsun.hitchtalk.domain.DialogueRequest
import com.kotlinsun.hitchtalk.domain.DialogueTurn
import com.kotlinsun.hitchtalk.domain.PersonaRecommender
import com.kotlinsun.hitchtalk.domain.TurnRole
import com.kotlinsun.hitchtalk.platform.AndroidUxRestrictionsReader
import com.kotlinsun.hitchtalk.platform.DrivingUxRestrictions
import com.kotlinsun.hitchtalk.platform.PleosVehicleGateway
import com.kotlinsun.hitchtalk.platform.PleosVoiceGateway
import com.kotlinsun.hitchtalk.platform.VehicleSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

enum class Screen { ONBOARDING, SETUP, HOME, RIDE, REPORT, HISTORY, COLLECTION, SETTINGS }

enum class AccountLoad { READY, RESTORING, FAILED }

enum class OpenAiCheck { IDLE, CHECKING, READY, INVALID_KEY, RATE_LIMITED, FAILED }

enum class VoiceAvailability {
    READY, CONSENT_REQUIRED, MICROPHONE_REQUIRED, CONFIGURATION_REQUIRED,
    VEHICLE_SIGNALS_REQUIRED, SAFETY_PAUSED, CONNECTING, CONNECTION_REQUIRED,
}

enum class Traveler(val personaId: String, val displayName: String) {
    ALEX("alex", "Alex"), SARA("sara", "Sara");

    companion object {
        fun fromId(id: String): Traveler = entries.firstOrNull { it.personaId == id } ?: ALEX
    }
}

enum class Gear { UNKNOWN, PARK, DRIVE, REVERSE, NEUTRAL }

data class HitchTalkState(
    val screen: Screen = Screen.ONBOARDING,
    val traveler: Traveler = Traveler.ALEX,
    val gear: Gear = Gear.UNKNOWN,
    val gearFresh: Boolean = false,
    val safety: ConversationSafety = ConversationSafety(),
    val isPreview: Boolean = false,
    val isConsolePreview: Boolean = false,
    val consolePreviewAvailable: Boolean = false,
    val busy: Boolean = false,
    val accountLoad: AccountLoad = AccountLoad.READY,
    val authenticated: Boolean = false,
    val profileRequired: Boolean = false,
    val message: Int = 0,
    val voice: ConversationStatus = ConversationStatus(),
    val voiceReady: Boolean = false,
    val voiceAvailability: VoiceAvailability = VoiceAvailability.CONNECTION_REQUIRED,
    val microphoneGranted: Boolean = false,
    val openAiKeyConfigured: Boolean = false,
    val openAiKeyBusy: Boolean = false,
    val openAiCheck: OpenAiCheck = OpenAiCheck.IDLE,
    val profiles: Map<String, TravelerProfile> = emptyMap(),
    val preferences: UserPreferences = UserPreferences(),
    val userProfile: UserProfile? = null,
    val report: SessionRecord? = null,
    val reportFromHistory: Boolean = false,
    val reportPreviousPromise: String? = null,
    val reportPromiseReviewed: Boolean = false,
    val previousReports: List<SessionRecord> = emptyList(),
    val historyLoadFailed: Boolean = false,
    val memoryCandidates: List<MemoryCandidate> = emptyList(),
    val syncPending: Boolean = false,
    val pendingCount: Int = 0,
    val pendingReportIds: Set<String> = emptySet(),
    val savingReportId: String? = null,
    val unsavedReportIds: Set<String> = emptySet(),
    val simulatedHazard: Boolean = false,
    val simulatedTurn: Boolean = false,
    val simulatedBrake: Boolean = false,
    val simulatedHvac: Boolean = false,
) {
    val canInteract: Boolean get() = safety.canInteract
    val isPaused: Boolean get() = voice.phase == VoicePhase.PAUSED
}

class HitchTalkViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HitchTalkRepository(application)
    private val vehicle = PleosVehicleGateway(application)
    private val voice = PleosVoiceGateway(application)
    private val openAiKeys = OpenAiKeyStore(application)
    private class LocalCredential(val uid: String, val key: String)
    @Volatile private var openAiCredential: LocalCredential? = null
    private val ai = OpenAiConversationClient({ openAiCredential?.takeIf { it.uid == accountId() }?.key })
    private var openAiCheckJob: Job? = null
    private val automotive = application.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private val consolePreviewSupported = BuildConfig.DEBUG && Build.HARDWARE == "ranchu" && automotive
    private val drivingPreviewEnabled = BuildConfig.PLEOS_DRIVING_PREVIEW && consolePreviewSupported
    private fun canSimulateConsoleHazard() = consolePreviewSupported &&
        getApplication<Application>().checkSelfPermission("android.car.permission.CAR_EXTERIOR_LIGHTS") !=
        PackageManager.PERMISSION_GRANTED

    private val mutableState = MutableLiveData(HitchTalkState(
        accountLoad = if (repository.isSignedIn) AccountLoad.RESTORING else AccountLoad.READY,
        consolePreviewAvailable = canSimulateConsoleHazard(),
    ))
    val state: LiveData<HitchTalkState> = mutableState
    private val current: HitchTalkState get() = checkNotNull(mutableState.value)
    private var snapshot = VehicleSnapshot()
    private var platformRestrictions: DrivingUxRestrictions? = null
    private val uxRestrictionsReader = AndroidUxRestrictionsReader(application) {
        platformRestrictions = it
        if (drivingPreviewEnabled) Log.d("HitchTalkDriving", "Platform UX restrictions: $it")
        refreshSafety()
    }
    private var previewGear = Gear.PARK
    private var foreground = false
    private var audioAvailable = false
    private var voiceInitialized = false
    private var voiceConnecting = false
    private var voiceInitialization: Job? = null
    private var accountVersion = 0L
    private var stateOwnerId: String? = null
    private var autoSyncAttempted = false
    private var historyOrigin = Screen.HOME
    private var setupOrigin = Screen.HOME
    private var ridePreviousPromise: String? = null
    private var rideInputMode = "vehicle"
    private val unsavedReports = mutableMapOf<String, SessionRecord>()
    private val orchestrator = ConversationOrchestrator(
        viewModelScope, voice, ai,
        onStatus = { status ->
            if (drivingPreviewEnabled && status.phase != current.voice.phase) {
                Log.d("HitchTalkDriving", "Gear ${current.gear}, voice ${status.phase}")
            }
            update {
                it.copy(
                    voice = status,
                    screen = when {
                        status.phase == VoicePhase.REPORTING -> Screen.REPORT
                        status.phase == VoicePhase.IDLE && it.screen == Screen.RIDE -> Screen.HOME
                        else -> it.screen
                    },
                )
            }
        },
        onCompleted = ::completeSession,
        onAiAuthenticationFailure = { update { it.copy(openAiCheck = OpenAiCheck.INVALID_KEY) } },
    )

    init {
        voice.onAudioInterruption = {
            audioAvailable = false
            refreshSafety()
        }
        uxRestrictionsReader.start()
        vehicle.start()
        viewModelScope.launch {
            vehicle.snapshots.collect {
                snapshot = it
                refreshSafety()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                refreshSafety()
                if (foreground && canUseAccount() && current.syncPending &&
                    current.screen != Screen.RIDE && !autoSyncAttempted
                ) {
                    autoSyncAttempted = true
                    retrySync()
                }
                delay(500)
            }
        }
        if (repository.isSignedIn) launchCloud { version -> loadAccount(version) }
    }

    fun onForeground(microphoneGranted: Boolean) {
        foreground = true
        audioAvailable = true
        autoSyncAttempted = false
        update { it.copy(microphoneGranted = microphoneGranted) }
        refreshSafety()
        prepareVoice()
    }

    fun onBackground() {
        foreground = false
        audioAvailable = false
        refreshSafety()
        voice.cancel()
    }

    fun setMicrophoneGranted(granted: Boolean) {
        update { it.copy(microphoneGranted = granted) }
        refreshSafety()
        if (granted) prepareVoice()
    }

    fun configurePreview(enabled: Boolean) {
        if (!BuildConfig.DEBUG || current.safety.platformRestricted != false || current.isConsolePreview || current.busy ||
            current.screen == Screen.RIDE || current.voice.phase == VoicePhase.REPORTING) return
        if (current.isPreview == enabled) return
        previewGear = Gear.PARK
        update {
            it.copy(
                isPreview = enabled, simulatedHazard = false, simulatedTurn = false,
                simulatedBrake = false, simulatedHvac = false,
            )
        }
        refreshSafety()
    }

    fun configureConsolePreview(enabled: Boolean) {
        if (!canSimulateConsoleHazard() || !current.canInteract || current.isPreview || current.busy ||
            current.screen == Screen.RIDE || current.voice.phase == VoicePhase.REPORTING) return
        if (current.isConsolePreview == enabled) return
        if (enabled && (snapshot.gear != Gear.PARK ||
                !fresh(snapshot.gearObservedAt, SystemClock.elapsedRealtime()))) return
        update { it.copy(isConsolePreview = enabled, simulatedHazard = false) }
        refreshSafety()
    }

    fun setPreviewGear(gear: Gear) {
        if (!BuildConfig.DEBUG || !current.isPreview) return
        previewGear = gear
        refreshSafety()
    }

    fun setSimulation(
        hazard: Boolean = current.simulatedHazard,
        turn: Boolean = current.simulatedTurn,
        brake: Boolean = current.simulatedBrake,
        hvac: Boolean = current.simulatedHvac,
    ) {
        if (!BuildConfig.DEBUG) return
        if (current.isConsolePreview && canSimulateConsoleHazard()) {
            update { it.copy(simulatedHazard = hazard) }
            refreshSafety()
            return
        }
        if (!current.isPreview) return
        update { it.copy(simulatedHazard = hazard, simulatedTurn = turn, simulatedBrake = brake, simulatedHvac = hvac) }
        refreshSafety()
    }

    fun authenticate(email: String, password: String, profile: UserProfile? = null) {
        if (!current.canInteract || current.busy || current.screen != Screen.ONBOARDING ||
            current.accountLoad != AccountLoad.READY) return
        if (current.profileRequired && profile == null) {
            update { it.copy(message = R.string.cloud_complete_profile) }
            return
        }
        accountVersion++
        launchCloud { version ->
            try {
                when {
                    current.profileRequired && repository.isSignedIn -> repository.saveUserProfile(checkNotNull(profile))
                    profile != null -> repository.signUp(email, password, profile)
                    else -> repository.signIn(email, password)
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                val locallySavedProfile = if (profile != null && repository.isSignedIn) {
                    repository.loadUserProfile()
                } else null
                if (profile == null || locallySavedProfile != profile) throw error
                loadAccount(version)
                if (version == accountVersion) update { it.copy(message = errorMessage(error)) }
                return@launchCloud
            }
            loadAccount(version)
        }
    }

    fun resetPassword(email: String) {
        if (!current.canInteract || current.busy || current.screen != Screen.ONBOARDING ||
            current.accountLoad != AccountLoad.READY) return
        launchCloud { version ->
            repository.resetPassword(email)
            if (version == accountVersion) update { it.copy(message = R.string.cloud_reset_sent) }
        }
    }

    fun retryAccountLoad() {
        if (!current.canInteract || current.busy || current.accountLoad != AccountLoad.FAILED) return
        launchCloud { version -> loadAccount(version) }
    }

    fun useAnotherAccount() {
        if (!current.canInteract || current.busy || current.accountLoad != AccountLoad.FAILED) return
        signOutAccount()
    }

    fun board(traveler: Traveler) {
        if (!canUseAccount() || current.screen !in setOf(Screen.HOME, Screen.COLLECTION)) return
        if (!current.preferences.onboardingCompleted || !current.preferences.aiConsentGranted) return
        refreshSafety()
        if (!current.canInteract) return
        if (!ai.isConfigured) {
            update { it.copy(message = R.string.cloud_ai_key_missing) }
            return
        }
        if (!current.voiceReady) {
            prepareVoice()
            update { it.copy(message = R.string.cloud_connection_failed) }
            return
        }
        val oldPreferences = current.preferences
        val origin = current.screen
        val preferences = if (oldPreferences.personaSelectionMode == "recommended" && current.traveler == traveler) {
            oldPreferences
        } else oldPreferences.copy(
            preferredPersonaId = traveler.personaId, personaSelectionMode = "manual", speechRate = 1f,
        )
        val profile = current.profiles[traveler.personaId]
        update { it.copy(screen = Screen.RIDE, traveler = traveler, preferences = preferences, report = null,
            reportFromHistory = false, reportPreviousPromise = null, reportPromiseReviewed = false,
            memoryCandidates = emptyList(), message = 0) }
        refreshSafety()
        if (!current.voiceReady || !orchestrator.board(traveler, preferences, profile)) {
            update { it.copy(screen = origin, preferences = oldPreferences) }
            return
        }
        ridePreviousPromise = profile?.memory?.lastPromise?.takeIf(String::isNotBlank)
        rideInputMode = when {
            current.isPreview -> "appPreview"
            current.isConsolePreview -> "consolePreview"
            else -> "vehicle"
        }
        if (preferences == oldPreferences) return
        val version = accountVersion
        val uid = accountId()
        viewModelScope.launch {
            try {
                repository.savePreferences(preferences)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrentAccount(version, uid)) update { it.copy(message = R.string.cloud_connection_failed) }
            }
        }
    }

    fun togglePause() {
        if (current.canInteract && !current.busy && current.screen == Screen.RIDE) orchestrator.togglePause()
    }

    fun dropOff() {
        if (current.canInteract && !current.busy && current.screen == Screen.RIDE) orchestrator.dropOff()
    }

    fun retryVoice() {
        if (!current.canInteract || current.busy) return
        audioAvailable = true
        refreshSafety()
        prepareVoice(retryConversation = current.screen == Screen.RIDE)
    }

    fun cancelRide() {
        if (!current.canInteract || current.busy || current.screen != Screen.RIDE) return
        if (current.voice.phase !in setOf(VoicePhase.ERROR, VoicePhase.SAFETY_MUTED)) return
        orchestrator.abort()
    }

    fun saveMemory(
        traveler: Traveler,
        memory: ConfirmedMemory?,
        confirmReport: Boolean = false,
        continuedPromise: Boolean = false,
    ) {
        if (!canUseAccount() || current.screen !in setOf(Screen.REPORT, Screen.SETTINGS, Screen.COLLECTION)) return
        val report = current.report?.takeIf {
            confirmReport && current.screen == Screen.REPORT && !current.reportFromHistory && it.personaId == traveler.personaId
        }
        if (confirmReport && report == null) return
        val reportId = report?.id
        if (reportId != null && (
                reportId == current.savingReportId || reportId in current.pendingReportIds ||
                    reportId in current.unsavedReportIds
            )) return
        val previousPromise = current.reportPreviousPromise?.takeIf {
            continuedPromise && !current.reportPromiseReviewed && report != null && report.userTurnCount > 0
        }
        if (memory == null && previousPromise == null) return
        launchCloud { version ->
            val uid = accountId()
            repository.confirmMemory(traveler.personaId, memory, reportId, previousPromise)
            if (isCurrentAccount(version, uid)) update {
                val profile = it.profiles[traveler.personaId] ?: TravelerProfile(traveler.personaId)
                it.copy(
                    profiles = if (memory == null) it.profiles else it.profiles + (traveler.personaId to profile.copy(memory = memory)),
                    memoryCandidates = if (confirmReport && memory != null) emptyList() else it.memoryCandidates,
                    reportPromiseReviewed = it.reportPromiseReviewed || previousPromise != null,
                    message = if (memory == null) R.string.report_previous_promise_confirmed else R.string.cloud_memory_saved,
                )
            }
            val profiles = readCloud<Map<String, TravelerProfile>?>(null, mutableListOf()) { repository.loadProfiles() }
            if (profiles != null && isCurrentAccount(version, uid)) update {
                it.copy(profiles = profiles)
            }
        }
    }

    fun savePreferences(preferences: UserPreferences) {
        if (!canUseAccount() || current.screen !in setOf(Screen.HOME, Screen.SETTINGS)) return
        val normalized = preferences.copy(speechRate = 1f)
        launchCloud { version ->
            val uid = accountId()
            repository.savePreferences(normalized)
            if (isCurrentAccount(version, uid)) update {
                it.copy(preferences = normalized, traveler = recommendedTraveler(normalized, it.profiles), message = R.string.cloud_preferences_saved)
            }
        }
    }

    fun openSetup() {
        if (!canUseAccount() || current.screen !in setOf(Screen.HOME, Screen.SETTINGS, Screen.COLLECTION)) return
        setupOrigin = current.screen
        update { it.copy(screen = Screen.SETUP, message = 0) }
    }

    fun completeSetup(level: String, consent: Boolean) {
        if (!canUseAccount() || current.screen != Screen.SETUP) return
        if (level !in setOf("beginner", "intermediate", "advanced")) return
        val completedBefore = current.preferences.onboardingCompleted
        val preferences = current.preferences.copy(
            englishLevel = level, onboardingCompleted = true, aiConsentGranted = consent,
        )
        launchCloud { version ->
            val uid = accountId()
            repository.savePreferences(preferences)
            if (!isCurrentAccount(version, uid)) return@launchCloud
            update {
                it.copy(
                    screen = if (completedBefore) setupOrigin else Screen.HOME,
                    preferences = preferences,
                    traveler = recommendedTraveler(preferences, it.profiles),
                    message = R.string.cloud_preferences_saved,
                )
            }
            refreshSafety()
            prepareVoice()
        }
    }

    fun useRecommendation() = savePreferences(current.preferences.copy(personaSelectionMode = "recommended"))

    fun deleteMemory(traveler: Traveler) {
        if (!canUseAccount() || current.screen !in setOf(Screen.SETTINGS, Screen.COLLECTION, Screen.REPORT)) return
        launchCloud { version ->
            val uid = accountId()
            repository.confirmMemory(traveler.personaId, ConfirmedMemory())
            if (isCurrentAccount(version, uid)) update {
                val profile = it.profiles[traveler.personaId] ?: TravelerProfile(traveler.personaId)
                it.copy(
                    profiles = it.profiles + (traveler.personaId to profile.copy(memory = ConfirmedMemory())),
                    memoryCandidates = if (it.report?.personaId == traveler.personaId) emptyList() else it.memoryCandidates,
                    message = R.string.cloud_deleted,
                )
            }
        }
    }

    fun clearHistory() {
        if (!canUseAccount() || current.screen != Screen.SETTINGS) return
        launchCloud { version ->
            val uid = accountId()
            repository.deleteAllData(deleteAccount = false)
            if (isCurrentAccount(version, uid)) {
                unsavedReports.clear()
                update {
                    it.copy(
                        profiles = emptyMap(), previousReports = emptyList(), report = null,
                        memoryCandidates = emptyList(), reportFromHistory = false, historyLoadFailed = false,
                        syncPending = false, pendingCount = 0, pendingReportIds = emptySet(),
                        savingReportId = null, unsavedReportIds = emptySet(),
                        traveler = recommendedTraveler(it.preferences, emptyMap()),
                        message = R.string.settings_history_cleared,
                    )
                }
                updatePending(version, uid)
            }
        }
    }

    fun deleteTraveler(traveler: Traveler) {
        if (!canUseAccount() || current.screen !in setOf(Screen.SETTINGS, Screen.COLLECTION, Screen.REPORT)) return
        launchCloud { version ->
            val uid = accountId()
            repository.deleteTravelerData(traveler.personaId)
            if (isCurrentAccount(version, uid)) {
                unsavedReports.values.removeAll { it.personaId == traveler.personaId }
                update {
                    it.copy(
                        profiles = it.profiles + (traveler.personaId to TravelerProfile(traveler.personaId)),
                        previousReports = it.previousReports.filterNot { report -> report.personaId == traveler.personaId },
                        screen = if (it.screen == Screen.REPORT && it.report?.personaId == traveler.personaId) {
                            if (it.reportFromHistory) Screen.HISTORY else Screen.COLLECTION
                        } else it.screen,
                        report = it.report?.takeUnless { report -> report.personaId == traveler.personaId },
                        unsavedReportIds = unsavedReports.keys.toSet(),
                        memoryCandidates = emptyList(), message = R.string.cloud_deleted,
                    )
                }
                updatePending(version, uid)
            }
        }
    }

    fun deleteAccount(password: String) {
        if (!canUseAccount() || current.screen != Screen.SETTINGS) return
        launchCloud { version ->
            val uid = accountId() ?: return@launchCloud
            repository.reauthenticate(password)
            withContext(Dispatchers.IO) { openAiKeys.remove(uid) }
            openAiCredential = null
            update { it.copy(openAiKeyConfigured = false) }
            repository.deleteAllData(deleteAccount = true)
            if (version == accountVersion) {
                accountVersion++
                resetAccountState()
            }
        }
    }

    fun signOut() {
        if (!canUseAccount() || current.screen == Screen.RIDE) return
        signOutAccount()
    }

    private fun signOutAccount() {
        launchCloud { version ->
            repository.signOut()
            if (version == accountVersion) {
                accountVersion++
                resetAccountState()
            }
        }
    }

    fun retrySync() {
        if (!canUseAccount() || current.screen == Screen.RIDE) return
        launchCloud { version ->
            val uid = accountId()
            val failures = mutableListOf<Exception>()
            try {
                readCloud(0, failures) { repository.retryPending() }
                for (record in unsavedReports.values.toList()) {
                    if (!isCurrentAccount(version, uid)) return@launchCloud
                    update { it.copy(savingReportId = record.id) }
                    readCloud<SaveResult?>(null, failures) { saveReport(record, version, uid) }
                }
            } finally {
                if (isCurrentAccount(version, uid)) updatePending(version, uid)
            }
            failures.firstOrNull()?.let { throw it }
            if (isCurrentAccount(version, uid)) {
                loadAccount(version)
                if (isCurrentAccount(version, uid) && current.message == 0) {
                    update { it.copy(message = if (it.syncPending) R.string.cloud_pending else R.string.cloud_saved) }
                }
            }
        }
    }

    fun openCollection() = navigate(setOf(Screen.HOME, Screen.REPORT), Screen.COLLECTION)
    fun openSettings() = navigate(setOf(Screen.HOME, Screen.COLLECTION), Screen.SETTINGS)

    fun saveOpenAiKey(value: String) {
        if (!canUseAccount() || !foreground || current.screen != Screen.SETTINGS) return
        val key = value.trim()
        if (!OpenAiKeyStore.isValid(key)) {
            update { it.copy(message = R.string.openai_key_invalid) }
            return
        }
        changeOpenAiKey(key)
    }

    fun removeOpenAiKey() {
        if (!canUseAccount() || !foreground || current.screen != Screen.SETTINGS) return
        changeOpenAiKey(null)
    }

    fun checkOpenAiConnection() {
        refreshSafety()
        if (!canUseAccount() || !canCheckOpenAi(current) || current.openAiCheck == OpenAiCheck.CHECKING) return
        val uid = accountId() ?: return
        val version = accountVersion
        update { it.copy(openAiCheck = OpenAiCheck.CHECKING, message = 0) }
        openAiCheckJob = viewModelScope.launch {
            val result = try {
                ai.reply(DialogueRequest(
                    personaId = Traveler.SARA.personaId,
                    turns = listOf(DialogueTurn("connection-check", TurnRole.USER, "I like hiking on weekends.")),
                ))
                OpenAiCheck.READY
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: OpenAiException) {
                when (error.kind) {
                    OpenAiFailure.AUTHENTICATION, OpenAiFailure.CONFIGURATION -> OpenAiCheck.INVALID_KEY
                    OpenAiFailure.RATE_LIMIT -> OpenAiCheck.RATE_LIMITED
                    else -> OpenAiCheck.FAILED
                }
            } catch (_: Exception) {
                OpenAiCheck.FAILED
            }
            if (isCurrentAccount(version, uid) && canCheckOpenAi(current) && current.openAiCheck == OpenAiCheck.CHECKING) {
                update { it.copy(openAiCheck = result) }
                refreshSafety()
            }
        }
    }

    private fun canCheckOpenAi(state: HitchTalkState): Boolean =
        foreground && state.canInteract && state.screen == Screen.SETTINGS && state.authenticated &&
            state.accountLoad == AccountLoad.READY && state.openAiKeyConfigured && !state.openAiKeyBusy && !state.busy

    private fun changeOpenAiKey(key: String?) {
        val uid = accountId() ?: return
        val version = accountVersion
        update { it.copy(openAiKeyBusy = true, message = 0) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (key == null) openAiKeys.remove(uid) else openAiKeys.write(uid, key)
                }
                if (isCurrentAccount(version, uid)) {
                    openAiCredential = key?.let { LocalCredential(uid, it) }
                    update { it.copy(
                        openAiKeyConfigured = key != null,
                        openAiCheck = OpenAiCheck.IDLE,
                        message = if (key == null) R.string.openai_key_removed else R.string.openai_key_saved,
                    ) }
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (isCurrentAccount(version, uid)) update { it.copy(message = R.string.openai_key_storage_failed) }
            } finally {
                if (version == accountVersion) {
                    update { it.copy(openAiKeyBusy = false) }
                    refreshSafety()
                    prepareVoice()
                }
            }
        }
    }

    fun openHistory() {
        if (!canUseAccount() || current.screen !in setOf(Screen.HOME, Screen.SETTINGS, Screen.COLLECTION)) return
        historyOrigin = current.screen
        update { it.copy(screen = Screen.HISTORY, message = 0) }
    }

    fun refreshHistory() {
        if (!canUseAccount() || current.screen != Screen.HISTORY) return
        launchCloud { version ->
            val uid = accountId()
            val failures = mutableListOf<Exception>()
            val records = readCloud<List<SessionRecord>?>(null, failures) { repository.loadRecentSessions() }
            if (isCurrentAccount(version, uid)) update {
                it.copy(
                    previousReports = records?.let(::withUnsavedReports) ?: it.previousReports,
                    historyLoadFailed = records == null,
                    message = failures.firstOrNull()?.let(::errorMessage) ?: 0,
                )
            }
        }
    }

    fun openReport(sessionId: String) {
        if (!canUseAccount() || current.screen != Screen.HISTORY) return
        val record = current.previousReports.firstOrNull { it.id == sessionId } ?: return
        update {
            it.copy(screen = Screen.REPORT, report = record, reportFromHistory = true,
                reportPreviousPromise = null, reportPromiseReviewed = false,
                traveler = Traveler.fromId(record.personaId), memoryCandidates = emptyList(), message = 0)
        }
    }

    fun finishReport() {
        val destination = if (current.reportFromHistory) Screen.HISTORY else Screen.HOME
        navigate(setOf(Screen.REPORT), destination)
    }

    fun goBack(): Boolean {
        if (!current.canInteract || current.busy || current.screen == Screen.RIDE) return true
        return when (current.screen) {
            Screen.REPORT -> { finishReport(); true }
            Screen.HISTORY -> { navigate(setOf(Screen.HISTORY), historyOrigin); true }
            Screen.COLLECTION, Screen.SETTINGS -> { navigate(setOf(current.screen), Screen.HOME); true }
            Screen.SETUP -> {
                if (!current.preferences.onboardingCompleted) false else {
                    navigate(setOf(Screen.SETUP), setupOrigin)
                    true
                }
            }
            else -> false
        }
    }

    private fun navigate(from: Set<Screen>, destination: Screen) {
        if (canUseAccount() && current.screen in from) update {
            it.copy(screen = destination, message = 0,
                reportPreviousPromise = it.reportPreviousPromise.takeIf { destination == Screen.REPORT },
                reportPromiseReviewed = it.reportPromiseReviewed && destination == Screen.REPORT,
                traveler = if (destination == Screen.HOME) {
                recommendedTraveler(it.preferences, it.profiles)
            } else it.traveler)
        }
    }

    private fun canUseAccount(): Boolean =
        current.canInteract && !current.busy && !current.openAiKeyBusy && current.accountLoad == AccountLoad.READY &&
            current.authenticated && !current.profileRequired &&
            current.userProfile != null && current.voice.phase != VoicePhase.REPORTING

    private fun prepareVoice(retryConversation: Boolean = false) {
        if (!foreground || current.accountLoad != AccountLoad.READY || !current.authenticated ||
            current.profileRequired || !current.microphoneGranted || !current.preferences.aiConsentGranted || !ai.isConfigured ||
            voiceInitialization?.isActive == true) return
        if (voiceInitialized && !retryConversation) return
        voiceConnecting = true
        refreshSafety()
        voiceInitialization = viewModelScope.launch {
            try {
                voice.initialize()
                voiceInitialized = true
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                voiceInitialized = false
            } finally {
                voiceConnecting = false
                refreshSafety()
            }
            if (retryConversation && current.canInteract && current.voiceReady) orchestrator.retry()
        }
    }

    private fun refreshSafety() {
        val now = SystemClock.elapsedRealtime()
        val state = current
        val consolePreviewAvailable = canSimulateConsoleHazard()
        val consolePreview = state.isConsolePreview && consolePreviewAvailable
        val data = if (state.isPreview) {
            VehicleSnapshot(
                gear = previewGear, gearObservedAt = now, turnSignalActive = state.simulatedTurn,
                hazardActive = state.simulatedHazard, hvacOn = state.simulatedHvac, batteryPercent = 78,
                turnObservedAt = now, hazardObservedAt = now, hvacObservedAt = now, batteryObservedAt = now,
                observedAt = now, source = "appPreview",
            )
        } else if (consolePreview) {
            snapshot.copy(
                hazardActive = snapshot.hazardActive == true || state.simulatedHazard,
                hazardObservedAt = if (snapshot.hazardActive == null) now else snapshot.hazardObservedAt,
                source = "consolePreview",
            )
        } else snapshot
        val gearFresh = fresh(data.gearObservedAt, now)
        val signalsKnown = data.turnSignalActive != null && data.hazardActive != null &&
            fresh(data.turnObservedAt, now) && fresh(data.hazardObservedAt, now)
        val platformRestricted = if (automotive) {
            platformRestrictions?.let {
                it.requiresDistractionOptimization || it.activeRestrictions != 0 ||
                    (state.isPreview && fresh(snapshot.gearObservedAt, now) && snapshot.gear != Gear.PARK)
            }
        } else false
        val safety = ConversationSafety(
            gear = data.gear, gearFresh = gearFresh, signalsKnown = signalsKnown,
            hazardActive = data.hazardActive == true, turnSignalActive = data.turnSignalActive == true,
            hardBrake = state.isPreview && state.simulatedBrake,
            foreground = foreground, audioAvailable = audioAvailable,
            microphoneGranted = state.microphoneGranted && state.preferences.aiConsentGranted,
            platformRestricted = platformRestricted,
            simulatedGear = state.isPreview,
            drivingVoiceEnabled = if (state.isPreview) platformRestricted == false else drivingPreviewEnabled,
        )
        val availability = when {
            !state.preferences.aiConsentGranted -> VoiceAvailability.CONSENT_REQUIRED
            !state.microphoneGranted -> VoiceAvailability.MICROPHONE_REQUIRED
            !signalsKnown -> VoiceAvailability.VEHICLE_SIGNALS_REQUIRED
            safety.hazardActive || safety.turnSignalActive || safety.hardBrake -> VoiceAvailability.SAFETY_PAUSED
            !ai.isConfigured || (state.openAiCheck == OpenAiCheck.INVALID_KEY && state.screen != Screen.RIDE) ->
                VoiceAvailability.CONFIGURATION_REQUIRED
            voiceConnecting -> VoiceAvailability.CONNECTING
            !voiceInitialized || !audioAvailable -> VoiceAvailability.CONNECTION_REQUIRED
            safety.canTalk -> VoiceAvailability.READY
            else -> VoiceAvailability.SAFETY_PAUSED
        }
        update {
            it.copy(
                gear = data.gear, gearFresh = gearFresh, safety = safety,
                isConsolePreview = consolePreview, consolePreviewAvailable = consolePreviewAvailable,
                voiceAvailability = availability,
                voiceReady = availability == VoiceAvailability.READY && it.accountLoad == AccountLoad.READY &&
                    it.authenticated && !it.profileRequired && it.preferences.onboardingCompleted,
                traveler = if (it.screen == Screen.HOME && !it.busy) recommendedTraveler(it.preferences, it.profiles)
                    else it.traveler,
            )
        }
        val facts = buildMap {
            data.hvacOn?.takeIf { fresh(data.hvacObservedAt, now, 30_000) }?.let { put("hvacOn", it.toString()) }
            data.batteryPercent?.takeIf { fresh(data.batteryObservedAt, now, 30_000) }?.let { put("batteryPercent", it.toString()) }
        }
        orchestrator.updateSafety(safety, facts)
    }

    private fun completeSession(completed: CompletedConversation) {
        if (!current.authenticated) return
        val previousPromise = ridePreviousPromise
        ridePreviousPromise = null
        val scores = completed.scores
        val analysis = completed.analysis
        val record = SessionRecord(
            id = completed.id, personaId = completed.traveler.personaId,
            startedAtMillis = completed.startedAtMillis, endedAtMillis = completed.endedAtMillis,
            activeConversationSec = scores.activeConversationSec.toLong(),
            userTurnCount = scores.userTurnCount, englishWordCount = scores.englishWordCount,
            summaryKo = analysis?.summaryKo.orEmpty(),
            corrections = analysis?.corrections.orEmpty().take(3).map {
                CorrectionRecord(it.original, it.natural, it.explanationKo)
            },
            scores = ScoreRecord(
                scores.speechVolumeScore, scores.sentenceCompletenessScore, scores.expressionVarietyScore,
                scores.completeSentenceCount, scores.evaluableSentenceCount, scores.expressionCategories, scores.sampleSufficient,
                unevaluableSentenceCount = scores.unevaluableSentenceCount,
            ),
            analysisStatus = if (analysis == null) "unavailable" else "completed",
            analysisModel = analysis?.analysisModel.orEmpty(),
            contextSources = completed.contextSources, demoInputMode = rideInputMode,
        )
        update {
            it.copy(
                screen = Screen.REPORT, report = record, reportFromHistory = false,
                reportPreviousPromise = previousPromise, reportPromiseReviewed = false,
                memoryCandidates = analysis?.memoryCandidates.orEmpty(),
                voice = ConversationStatus(VoicePhase.IDLE),
                previousReports = (listOf(record) + it.previousReports.filterNot { previous -> previous.id == record.id }).take(20),
                savingReportId = record.id,
                message = R.string.cloud_saving,
            )
        }
        launchCloud { version ->
            val uid = accountId()
            val result = saveReport(record, version, uid)
            if (isCurrentAccount(version, uid)) {
                update {
                    it.copy(message = if (result == SaveResult.PENDING) R.string.cloud_pending else R.string.cloud_saved)
                }
                updatePending(version, uid)
                if (result == SaveResult.SYNCED) {
                    val profiles = repository.loadProfiles()
                    if (isCurrentAccount(version, uid)) update { it.copy(profiles = profiles) }
                }
            }
        }
    }

    private suspend fun saveReport(record: SessionRecord, version: Long, uid: String?): SaveResult {
        val result = try {
            repository.saveSession(record)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (isCurrentAccount(version, uid)) {
                unsavedReports[record.id] = record
                update {
                    it.copy(savingReportId = null, unsavedReportIds = unsavedReports.keys.toSet())
                }
            }
            throw error
        }
        if (isCurrentAccount(version, uid)) {
            unsavedReports.remove(record.id)
            update {
                val pending = result == SaveResult.PENDING
                it.copy(
                    savingReportId = null,
                    unsavedReportIds = unsavedReports.keys.toSet(),
                    syncPending = it.syncPending || pending,
                    pendingReportIds = if (pending) it.pendingReportIds + record.id else it.pendingReportIds - record.id,
                )
            }
        }
        return result
    }

    private suspend fun loadAccount(version: Long) {
        val uid = accountId()
        if (uid == null) {
            if (version == accountVersion) resetAccountState()
            return
        }
        if (current.screen == Screen.ONBOARDING || current.accountLoad != AccountLoad.READY) {
            update { it.copy(accountLoad = AccountLoad.RESTORING, profileRequired = false) }
        }
        val fallback = current.takeIf { stateOwnerId == uid }
        val accountFailures = mutableListOf<Exception>()
        val profile = readCloud(fallback?.userProfile, accountFailures) { repository.loadUserProfile() }
        if (!isCurrentAccount(version, uid)) return
        if (profile == null) {
            accountFailures.firstOrNull()?.let { throw it }
            update {
                it.copy(
                    screen = Screen.ONBOARDING, accountLoad = AccountLoad.READY, authenticated = true,
                    profileRequired = true, userProfile = null, message = R.string.cloud_complete_profile,
                )
            }
            updatePending(version, uid)
            refreshSafety()
            return
        }
        val failures = mutableListOf<Exception>()
        coroutineScope {
            val preferences = async {
                readCloud(fallback?.preferences, accountFailures) { repository.loadPreferences() }?.copy(speechRate = 1f)
            }
            val profiles = async { readCloud(fallback?.profiles.orEmpty(), failures) { repository.loadProfiles() } }
            val history = async {
                readCloud<List<SessionRecord>?>(null, failures) { repository.loadRecentSessions() }
            }
            val loadedPreferences = preferences.await() ?: throw accountFailures.last()
            val loadedProfiles = profiles.await()
            val loadedHistory = history.await()
            val records = loadedHistory ?: (
                readCloud(emptyList(), failures) { repository.loadPendingSessions() } + fallback?.previousReports.orEmpty()
            )
            val localKey = if (fallback == null) {
                withContext(Dispatchers.IO) { runCatching { openAiKeys.read(uid) }.getOrNull() }
            } else openAiCredential?.takeIf { it.uid == uid }?.key
            if (isCurrentAccount(version, uid)) {
                stateOwnerId = uid
                openAiCredential = localKey?.let { LocalCredential(uid, it) }
                update {
                    it.copy(
                        screen = when {
                            it.screen != Screen.ONBOARDING -> it.screen
                            loadedPreferences.onboardingCompleted -> Screen.HOME
                            else -> Screen.SETUP
                        },
                        accountLoad = AccountLoad.READY, authenticated = true, profileRequired = false, userProfile = profile,
                        openAiKeyConfigured = localKey != null,
                        preferences = loadedPreferences, profiles = loadedProfiles,
                        previousReports = withUnsavedReports(records), historyLoadFailed = loadedHistory == null,
                        traveler = if (it.screen == Screen.REPORT || it.screen == Screen.RIDE) it.traveler
                            else recommendedTraveler(loadedPreferences, loadedProfiles),
                        message = (accountFailures.firstOrNull() ?: failures.firstOrNull())?.let(::errorMessage) ?: it.message,
                    )
                }
            }
        }
        updatePending(version, uid)
        refreshSafety()
        prepareVoice()
    }

    private fun withUnsavedReports(records: List<SessionRecord>): List<SessionRecord> =
        (unsavedReports.values + records).distinctBy { it.id }.sortedByDescending { it.endedAtMillis }.take(20)

    private suspend fun <T> readCloud(fallback: T, failures: MutableList<Exception>, read: suspend () -> T): T =
        try {
            read()
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            failures += error
            fallback
        }

    private suspend fun updatePending(version: Long, uid: String?) {
        val count = repository.pendingCount()
        val ids = if (count == 0) emptySet() else repository.loadPendingSessions().mapTo(mutableSetOf()) { it.id }
        if (isCurrentAccount(version, uid)) update {
            it.copy(syncPending = count > 0, pendingCount = count, pendingReportIds = ids)
        }
    }

    private fun recommendedTraveler(preferences: UserPreferences, profiles: Map<String, TravelerProfile>): Traveler {
        if (preferences.personaSelectionMode == "manual") return Traveler.fromId(preferences.preferredPersonaId)
        return Traveler.fromId(PersonaRecommender.recommend(
            LocalDateTime.now(), profiles["alex"]?.lastSessionAtMillis, profiles["sara"]?.lastSessionAtMillis,
        ))
    }

    private fun launchCloud(action: suspend (Long) -> Unit) {
        if (current.busy) return
        val version = accountVersion
        update { it.copy(busy = true, message = 0) }
        viewModelScope.launch {
            try {
                action(version)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (version == accountVersion) update {
                    val signedIn = repository.isSignedIn
                    val accountLoadFailed = signedIn && (it.accountLoad == AccountLoad.RESTORING ||
                        it.userProfile == null && !it.profileRequired)
                    it.copy(
                        authenticated = signedIn,
                        accountLoad = when {
                            !signedIn -> AccountLoad.READY
                            accountLoadFailed -> AccountLoad.FAILED
                            else -> it.accountLoad
                        },
                        profileRequired = signedIn && !accountLoadFailed && it.profileRequired,
                        message = errorMessage(error),
                    )
                }
            } finally {
                if (version == accountVersion) update { it.copy(busy = false) }
            }
        }
    }

    private fun resetAccountState() {
        orchestrator.abort()
        openAiCheckJob?.cancel()
        stateOwnerId = null
        ridePreviousPromise = null
        openAiCredential = null
        unsavedReports.clear()
        val old = current
        mutableState.value = HitchTalkState(
            gear = old.gear, gearFresh = old.gearFresh, isPreview = old.isPreview,
            safety = old.safety,
            isConsolePreview = old.isConsolePreview, consolePreviewAvailable = old.consolePreviewAvailable,
            microphoneGranted = old.microphoneGranted,
            simulatedHazard = old.simulatedHazard, simulatedTurn = old.simulatedTurn,
            simulatedBrake = old.simulatedBrake, simulatedHvac = old.simulatedHvac,
        )
        refreshSafety()
    }

    private fun accountId(): String? = FirebaseAuth.getInstance().currentUser?.uid
    private fun isCurrentAccount(version: Long, uid: String?): Boolean =
        version == accountVersion && uid != null && accountId() == uid

    private fun fresh(observedAt: Long, now: Long, maxAgeMillis: Long = 5_000): Boolean =
        observedAt > 0 && now - observedAt in 0..maxAgeMillis

    private fun update(transform: (HitchTalkState) -> HitchTalkState) {
        var next = transform(current)
        if (next.openAiCheck == OpenAiCheck.CHECKING && !canCheckOpenAi(next)) {
            openAiCheckJob?.cancel()
            next = next.copy(openAiCheck = OpenAiCheck.IDLE)
        }
        if (next != current) mutableState.value = next
    }

    private fun errorMessage(error: Exception): Int = when (error) {
        is PublicProfileRulesException -> R.string.cloud_rules_public
        is FirebaseAuthException -> when (error.errorCode) {
            "ERROR_OPERATION_NOT_ALLOWED", "ERROR_ADMIN_RESTRICTED_OPERATION" -> R.string.cloud_provider_disabled
            "ERROR_EMAIL_ALREADY_IN_USE" -> R.string.cloud_email_exists
            "ERROR_WEAK_PASSWORD" -> R.string.cloud_weak_password
            else -> R.string.cloud_auth_failed
        }
        is FirebaseFirestoreException -> if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            R.string.cloud_rules_denied
        } else R.string.cloud_connection_failed
        else -> R.string.cloud_connection_failed
    }

    override fun onCleared() {
        orchestrator.abort()
        openAiCredential = null
        uxRestrictionsReader.close()
        vehicle.close()
        voice.release()
        super.onCleared()
    }
}
