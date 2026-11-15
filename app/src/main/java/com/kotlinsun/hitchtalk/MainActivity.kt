package com.kotlinsun.hitchtalk

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.viewbinding.ViewBinding
import com.kotlinsun.hitchtalk.data.ConfirmedMemory
import com.kotlinsun.hitchtalk.data.SessionRecord
import com.kotlinsun.hitchtalk.data.TravelerProfile
import com.kotlinsun.hitchtalk.data.UserProfile
import com.kotlinsun.hitchtalk.avatar.AvatarKind
import com.kotlinsun.hitchtalk.avatar.AvatarMood
import com.kotlinsun.hitchtalk.avatar.AvatarView
import com.kotlinsun.hitchtalk.databinding.ActivityMainBinding
import com.kotlinsun.hitchtalk.databinding.RowSessionBinding
import com.kotlinsun.hitchtalk.databinding.RowReportCorrectionBinding
import com.kotlinsun.hitchtalk.databinding.ScreenAccountStatusBinding
import com.kotlinsun.hitchtalk.databinding.ScreenAuthBinding
import com.kotlinsun.hitchtalk.databinding.ScreenOnboardingBinding
import com.kotlinsun.hitchtalk.databinding.ScreenCollectionBinding
import com.kotlinsun.hitchtalk.databinding.ScreenHomeBinding
import com.kotlinsun.hitchtalk.databinding.ScreenHistoryBinding
import com.kotlinsun.hitchtalk.databinding.ScreenLockedBinding
import com.kotlinsun.hitchtalk.databinding.ScreenReportBinding
import com.kotlinsun.hitchtalk.databinding.ScreenRestrictedDrivingBinding
import com.kotlinsun.hitchtalk.databinding.ScreenRideBinding
import com.kotlinsun.hitchtalk.databinding.ScreenSettingsBinding
import com.kotlinsun.hitchtalk.databinding.ScreenSetupBinding
import com.kotlinsun.hitchtalk.databinding.ViewReadinessBinding
import com.kotlinsun.hitchtalk.domain.ReportComparison
import com.kotlinsun.hitchtalk.ui.SeedTone
import com.kotlinsun.hitchtalk.ui.MicrophoneTestDialog
import com.kotlinsun.hitchtalk.ui.seedBadge
import com.kotlinsun.hitchtalk.ui.seedCallout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: HitchTalkViewModel
    private var currentScreen: Screen? = null
    private var content: ViewBinding? = null
    private var activeDialog: Dialog? = null
    private var signupMode = false
    private var authFormVisible = false
    private var debugExpanded = false
    private var rideQuoteExpanded = false
    private var historySelection = 0
    private var historyKey: HistoryKey? = null
    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setMicrophoneGranted(granted)
        render(state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        signupMode = savedInstanceState?.getBoolean("signupMode") ?: false
        authFormVisible = savedInstanceState?.getBoolean("authFormVisible") ?: false
        historySelection = savedInstanceState?.getInt("historySelection") ?: 0
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        viewModel = ViewModelProvider(this)[HitchTalkViewModel::class.java]
        onBackPressedDispatcher.addCallback(this) {
            if (state.screen == Screen.ONBOARDING && authFormVisible && !state.profileRequired) {
                authFormVisible = false
                currentScreen = null
                render(state)
            } else if (!viewModel.goBack()) finish()
        }
        binding.retrySync.setOnClickListener { viewModel.retrySync() }
        if (BuildConfig.DEBUG) {
            binding.debugToggle.setOnClickListener {
                debugExpanded = !debugExpanded
                renderFooter(state)
            }
            binding.simulation.setOnClickListener {
                viewModel.configurePreview(binding.simulation.isChecked)
                binding.simulation.isChecked = state.isPreview
            }
            binding.consolePreview.setOnClickListener {
                viewModel.configureConsolePreview(binding.consolePreview.isChecked)
                binding.consolePreview.isChecked = state.isConsolePreview
            }
            previewGearButtons().forEach { (gear, button) ->
                button.setOnClickListener { viewModel.setPreviewGear(gear) }
            }
            binding.hazard.setOnClickListener { viewModel.setSimulation(hazard = binding.hazard.isChecked) }
            binding.turn.setOnClickListener { viewModel.setSimulation(turn = binding.turn.isChecked) }
            binding.brake.setOnClickListener { viewModel.setSimulation(brake = binding.brake.isChecked) }
            binding.hvac.setOnClickListener { viewModel.setSimulation(hvac = binding.hvac.isChecked) }
        }
        viewModel.state.observe(this, ::render)
    }

    private val state: HitchTalkState get() = checkNotNull(viewModel.state.value)

    override fun onStart() {
        super.onStart()
        forEachAvatar(content?.root) { it.setActive(true) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onForeground(
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    override fun onPause() {
        (activeDialog as? MicrophoneTestDialog)?.dismiss()
        viewModel.onBackground()
        super.onPause()
    }

    override fun onStop() {
        forEachAvatar(content?.root) { it.setActive(false) }
        activeDialog?.dismiss()
        super.onStop()
    }

    override fun onDestroy() {
        forEachAvatar(content?.root) { it.release() }
        content = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("signupMode", signupMode)
        outState.putBoolean("authFormVisible", authFormVisible)
        outState.putInt("historySelection", historySelection)
        super.onSaveInstanceState(outState)
    }

    private fun renderFooter(state: HitchTalkState) {
        val debugAvailable = BuildConfig.DEBUG && (state.canInteract ||
            (state.gear == Gear.UNKNOWN && state.safety.platformRestricted == false && state.screen != Screen.RIDE))
        if (state.safety.requiresDrivingUi && !debugAvailable) {
            binding.debugToggle.isVisible = false
            binding.previewControls.isVisible = false
            binding.retrySync.isVisible = false
            binding.busyProgress.isVisible = false
            binding.statusMessage.isVisible = false
            return
        }
        val previewActive = state.isPreview || state.isConsolePreview
        binding.debugToggle.isVisible = debugAvailable && !previewActive
        binding.debugToggle.setText(if (debugExpanded) R.string.design_debug_hide else R.string.design_debug_show)
        binding.previewControls.isVisible = BuildConfig.DEBUG && (previewActive || (debugExpanded && debugAvailable))
        val canChangePreview = !state.busy && state.screen != Screen.RIDE && state.voice.phase != VoicePhase.REPORTING
        binding.simulation.isVisible = !state.isConsolePreview
        binding.simulation.isChecked = state.isPreview
        binding.simulation.isEnabled = canChangePreview
        binding.consolePreview.isVisible = state.consolePreviewAvailable && !state.isPreview
        binding.consolePreview.isChecked = state.isConsolePreview
        binding.consolePreview.isEnabled = canChangePreview
        previewGearButtons().forEach { (gear, button) ->
            button.isVisible = state.isPreview
            button.isSelected = gear == state.gear
        }
        binding.hazard.isVisible = previewActive
        binding.hazard.setText(if (state.isConsolePreview) R.string.debug_console_hazard else R.string.debug_hazard)
        binding.hazard.isChecked = state.simulatedHazard
        listOf(binding.turn, binding.brake, binding.hvac).forEach {
            it.isVisible = state.isPreview
        }
        binding.turn.isChecked = state.simulatedTurn
        binding.brake.isChecked = state.simulatedBrake
        binding.hvac.isChecked = state.simulatedHvac
        val parked = state.canInteract && state.screen != Screen.ONBOARDING && state.accountLoad == AccountLoad.READY
        binding.retrySync.isVisible = parked && state.screen != Screen.RIDE &&
            (state.syncPending || state.unsavedReportIds.isNotEmpty())
        binding.retrySync.isEnabled = !state.busy && state.voice.phase != VoicePhase.REPORTING
        binding.busyProgress.isVisible = parked && (state.busy || state.voice.phase == VoicePhase.REPORTING)
        val message = when {
            state.busy -> R.string.general_working
            state.unsavedReportIds.isNotEmpty() && state.screen != Screen.RIDE -> R.string.refine_unsaved_message
            state.message != 0 -> state.message
            state.syncPending && state.screen != Screen.RIDE -> R.string.cloud_pending
            else -> 0
        }
        binding.statusMessage.isVisible = parked && message != 0
        if (message != 0) binding.statusMessage.seedCallout(messageTone(message))
        if (message == R.string.cloud_pending && state.pendingCount > 0) {
            binding.statusMessage.text = getString(R.string.history_pending_count, state.pendingCount)
        } else if (message != 0) binding.statusMessage.setText(message)
    }

    private fun render(state: HitchTalkState) {
        if (state.screen != Screen.ONBOARDING) authFormVisible = false
        val pickup = state.screen == Screen.RIDE && !state.safety.requiresDrivingUi &&
            state.voice.phase in setOf(VoicePhase.BOARDING, VoicePhase.GREETING)
        binding.screenContainer.setBackgroundResource(
            if (pickup) R.drawable.hitch_card_lavender else R.drawable.hitch_card_white,
        )
        if (activeDialog is MicrophoneTestDialog && (!canTestMicrophone(state) || !state.microphoneGranted)) {
            activeDialog?.dismiss()
        }
        renderFooter(state)
        if (state.safety.requiresDrivingUi) {
            renderRestrictedDriving(state)
            return
        }
        if (!state.canInteract && state.screen != Screen.RIDE) {
            dismissParkedInput()
            if (content !is ScreenLockedBinding) {
                currentScreen = null
                show(ScreenLockedBinding.inflate(layoutInflater))
            }
            (content as ScreenLockedBinding).title.setText(
                if (state.gearFresh) gearStatusLabel(state.gear) else R.string.refine_vehicle_check,
            )
            return
        }

        if (!state.canInteract) activeDialog?.dismiss()
        if (state.accountLoad != AccountLoad.READY && state.screen != Screen.RIDE) {
            if (currentScreen == Screen.SETTINGS) activeDialog?.dismiss()
            currentScreen = null
            if (content !is ScreenAccountStatusBinding) {
                show(ScreenAccountStatusBinding.inflate(layoutInflater).apply {
                    retryAccount.setOnClickListener { viewModel.retryAccountLoad() }
                    switchAccount.setOnClickListener { viewModel.useAnotherAccount() }
                })
            }
            renderAccountStatus(content as ScreenAccountStatusBinding, state)
            return
        }
        if (currentScreen != state.screen) {
            if (currentScreen == Screen.SETTINGS) activeDialog?.dismiss()
            currentScreen = state.screen
            show(createScreen(state))
        }
        when (val screen = content) {
            is ScreenAuthBinding -> renderAuth(screen, state)
            is ScreenOnboardingBinding -> renderWelcome(screen, state)
            is ScreenSetupBinding -> renderSetup(screen, state)
            is ScreenHomeBinding -> renderHome(screen, state)
            is ScreenRideBinding -> renderRide(screen, state)
            is ScreenReportBinding -> renderReport(screen, state)
            is ScreenHistoryBinding -> renderHistory(screen, state)
            is ScreenCollectionBinding -> renderCollection(screen, state)
            is ScreenSettingsBinding -> renderSettings(screen, state)
        }
    }

    private fun dismissParkedInput() {
        activeDialog?.dismiss()
        currentFocus?.let {
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
    }

    private fun renderRestrictedDriving(state: HitchTalkState) {
        val screen = content as? ScreenRestrictedDrivingBinding ?: ScreenRestrictedDrivingBinding
            .inflate(layoutInflater).also {
                dismissParkedInput()
                currentScreen = null
                rideQuoteExpanded = false
                sizeRestrictedAvatar(it)
                show(it)
            }
        val riding = state.screen == Screen.RIDE
        val title = getString(if (riding) R.string.hitch_brand else R.string.restricted_driving_title)
        val showTraveler = riding && !state.safety.simulatedGear && state.safety.platformRestricted != null &&
            state.safety.gearFresh && state.safety.gear == Gear.DRIVE
        val travelerIcon = if (showTraveler) when (state.traveler) {
            Traveler.ALEX -> R.drawable.hitch_driving_avatar_alex
            Traveler.SARA -> R.drawable.hitch_driving_avatar_sara
        } else 0
        if (screen.travelerIcon.tag != travelerIcon) {
            screen.travelerIcon.setImageResource(travelerIcon)
            screen.travelerIcon.tag = travelerIcon
        }
        screen.travelerIcon.isVisible = showTraveler
        screen.travelerIcon.setBackgroundResource(
            if (state.safety.canTalk && state.voice.phase == VoicePhase.LISTENING) {
                R.drawable.hitch_zip_driving_halo
            } else R.drawable.hitch_zip_avatar_halo,
        )
        screen.voiceWave.isVisible = showTraveler && state.safety.canTalk
        screen.drivingMessage.isVisible = riding
        screen.drivingMessage.setText(when {
            !showTraveler -> R.string.hitch_wait_for_park
            state.safety.canTalk -> R.string.zip_drive_voice
            else -> R.string.zip_drive_paused
        })
        val status = getString(when {
            !riding -> R.string.restricted_driving_detail
            !showTraveler -> R.string.refine_vehicle_check
            state.safety.canTalk -> voiceLabel(state.voice.phase)
            else -> restrictedVoiceLabel(state.voice.phase)
        })
        val statusColor = ContextCompat.getColor(this, when {
            !showTraveler || !state.safety.canTalk -> R.color.hitch_muted
            state.voice.phase == VoicePhase.LISTENING -> R.color.hitch_green
            else -> R.color.hitch_accent
        })
        screen.status.setTextColor(statusColor)
        screen.voiceWave.imageTintList = ColorStateList.valueOf(statusColor)
        screen.status.contentDescription = if (riding) {
            "${state.traveler.displayName} · $status"
        } else status
        if (screen.title.text != title) screen.title.text = title
        if (screen.status.text != status) screen.status.text = status
    }

    private fun sizeRestrictedAvatar(screen: ScreenRestrictedDrivingBinding) {
        screen.root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val maximum = (360 * resources.displayMetrics.density).toInt()
            val side = minOf(((right - left) * .65f).toInt(), ((bottom - top) * .46f).toInt(), maximum)
                .coerceAtLeast(1)
            val params = screen.travelerIcon.layoutParams
            if (params.width != side || params.height != side) {
                params.width = side
                params.height = side
                screen.travelerIcon.layoutParams = params
            }
        }
    }

    private fun renderAccountStatus(screen: ScreenAccountStatusBinding, state: HitchTalkState) = with(screen) {
        val failed = state.accountLoad == AccountLoad.FAILED
        title.setText(if (failed) R.string.refine_account_failed else R.string.refine_account_loading)
        detail.setText(if (failed) R.string.refine_account_failed_detail else R.string.refine_account_loading_detail)
        progress.isVisible = !failed || state.busy
        retryAccount.isVisible = failed
        switchAccount.isVisible = failed
        retryAccount.isEnabled = !state.busy
        switchAccount.isEnabled = !state.busy
    }

    private fun renderHome(screen: ScreenHomeBinding, state: HitchTalkState) = with(screen) {
        val automatic = state.preferences.personaSelectionMode == "recommended"
        val alexSelected = state.traveler == Traveler.ALEX
        val selectedLabel = if (automatic) R.string.home_suggested_now else R.string.home_selected_badge
        greeting.setText(when (java.time.LocalTime.now().hour) {
            in 5..11 -> R.string.zip_home_morning
            in 12..17 -> R.string.zip_home_afternoon
            else -> R.string.zip_home_evening
        })
        fun familiarity(personaId: String): String {
            val profile = state.profiles[personaId]
            if (profile == null || profile.meetingCount == 0) return getString(R.string.zip_home_new_friend)
            val level = when {
                profile.xp >= 60 -> R.string.zip_home_best_friend
                profile.xp >= 20 -> R.string.zip_home_comfortable
                else -> R.string.zip_home_acquaintance
            }
            return getString(R.string.zip_home_familiarity, getString(level), profile.meetingCount)
        }
        alexFamiliarity.text = familiarity("alex")
        saraFamiliarity.text = familiarity("sara")
        recommendation.text = getString(
            if (automatic) R.string.home_recommended_traveler else R.string.home_manual_traveler,
            state.traveler.displayName,
        )
        alexRecommendation.setText(if (alexSelected) selectedLabel else R.string.home_alex_window)
        saraRecommendation.setText(if (!alexSelected) selectedLabel else R.string.home_sara_window)
        alexRecommendation.seedBadge(if (alexSelected) SeedTone.BRAND else SeedTone.NEUTRAL)
        saraRecommendation.seedBadge(if (alexSelected) SeedTone.NEUTRAL else SeedTone.BRAND)
        alexCard.setBackgroundResource(if (alexSelected) R.drawable.hitch_showcase_card_selected else R.drawable.hitch_card_gray)
        saraCard.setBackgroundResource(if (!alexSelected) R.drawable.hitch_showcase_card_selected else R.drawable.hitch_card_gray)
        renderReadiness(readiness, state)
        actionPriority(boardAlex, alexSelected)
        actionPriority(boardSara, !alexSelected)
        boardAlex.isEnabled = state.voiceReady && !state.busy
        boardSara.isEnabled = state.voiceReady && !state.busy
        collection.isEnabled = !state.busy
        settings.isEnabled = !state.busy
        history.isEnabled = !state.busy
        useRecommendation.isVisible = !automatic
        useRecommendation.isEnabled = !state.busy
    }

    private data class ReadinessCopy(
        @get:StringRes val title: Int,
        @get:StringRes val detail: Int = 0,
        @get:StringRes val action: Int = 0,
        val tone: SeedTone = SeedTone.WARNING,
    )

    private fun renderReadiness(panel: ViewReadinessBinding, state: HitchTalkState) = with(panel) {
        val copy = when (state.voiceAvailability) {
            VoiceAvailability.READY -> null
            VoiceAvailability.CONSENT_REQUIRED -> ReadinessCopy(
                R.string.refine_consent_title, R.string.refine_consent_detail, R.string.setup_open,
            )
            VoiceAvailability.MICROPHONE_REQUIRED -> {
                val settings = microphoneNeedsSettings()
                ReadinessCopy(
                    R.string.refine_mic_title,
                    if (settings) R.string.refine_mic_settings_detail else R.string.refine_mic_detail,
                    if (settings) R.string.refine_mic_settings else R.string.voice_request_microphone,
                )
            }
            VoiceAvailability.CONFIGURATION_REQUIRED -> if (state.openAiKeyConfigured) {
                ReadinessCopy(
                    R.string.openai_check_invalid_title, R.string.openai_check_invalid_key, R.string.openai_key_open_settings,
                )
            } else {
                ReadinessCopy(
                    R.string.openai_key_required, R.string.openai_key_required_detail, R.string.openai_key_open_settings,
                )
            }
            VoiceAvailability.VEHICLE_SIGNALS_REQUIRED -> ReadinessCopy(
                R.string.refine_signals_title, R.string.refine_signals_detail,
            )
            VoiceAvailability.SAFETY_PAUSED -> ReadinessCopy(
                R.string.refine_voice_safety_title, R.string.refine_voice_safety_detail,
            )
            VoiceAvailability.CONNECTING -> ReadinessCopy(R.string.voice_connecting, tone = SeedTone.INFORMATIVE)
            VoiceAvailability.CONNECTION_REQUIRED -> ReadinessCopy(
                R.string.refine_voice_retry_title, R.string.refine_voice_retry_detail, R.string.voice_reconnect,
            )
        }
        root.isVisible = state.canInteract && copy != null
        if (copy == null) return@with
        root.setBackgroundResource(copy.tone.callout)
        val heading = getString(copy.title)
        if (title.text != heading) title.text = heading
        val foreground = ContextCompat.getColor(this@MainActivity, copy.tone.foreground)
        title.setTextColor(foreground)
        readinessIcon.imageTintList = ColorStateList.valueOf(foreground)
        detail.isVisible = copy.detail != 0
        if (copy.detail != 0) detail.setText(copy.detail)
        action.isVisible = copy.action != 0
        action.isEnabled = !state.busy
        if (copy.action != 0) action.setText(copy.action)
    }

    private fun bindReadiness(panel: ViewReadinessBinding) {
        panel.action.setOnClickListener {
            if (!state.canInteract || state.busy) return@setOnClickListener
            when (state.voiceAvailability) {
                VoiceAvailability.CONSENT_REQUIRED -> viewModel.openSetup()
                VoiceAvailability.MICROPHONE_REQUIRED -> requestMicrophone()
                VoiceAvailability.CONFIGURATION_REQUIRED -> viewModel.openSettings()
                VoiceAvailability.CONNECTION_REQUIRED -> viewModel.retryVoice()
                else -> Unit
            }
        }
    }

    private fun actionPriority(button: AppCompatButton, primary: Boolean) {
        if (button.tag == primary) return
        button.tag = primary
        button.setBackgroundResource(if (primary) R.drawable.hitch_button else R.drawable.hitch_button_secondary)
        button.setTextColor(ContextCompat.getColorStateList(this,
            if (primary) R.color.hitch_button_label else R.color.hitch_button_secondary_label,
        ))
    }

    private fun renderSettings(screen: ScreenSettingsBinding, state: HitchTalkState) = with(screen) {
        microphoneTest.isEnabled = canTestMicrophone(state)
        microphoneTest.setText(if (state.microphoneGranted) R.string.microphone_test_open else R.string.microphone_test_permission)
        val profile = state.userProfile
        profileSummary.text = if (profile?.ageYears == null) profile?.displayName.orEmpty()
        else getString(R.string.settings_profile, profile.displayName, profile.ageYears, profile.gender, profile.birthDate)
        settingsEmail.text = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email.orEmpty()
        settingsEmail.isVisible = settingsEmail.text.isNotBlank()
        settingsAlexMemorySummary.text = memoryText(state.profiles[Traveler.ALEX.personaId]?.memory)
        settingsSaraMemorySummary.text = memoryText(state.profiles[Traveler.SARA.personaId]?.memory)
        listOf(back, save, sync, logout, deleteAccount, openSetup, clearHistory, history, alexMemory, saraMemory,
            level, travelerChoice, greeting, settingsAlexMemoryDelete, settingsSaraMemoryDelete,
            settingsTabDifficulty, settingsTabVoice, settingsTabMemory, settingsTabData,
            settingsLevelEasy, settingsLevelNormal, settingsLevelChallenge).forEach {
            it.isEnabled = state.canInteract && !state.busy
        }
        sync.isVisible = state.syncPending || state.unsavedReportIds.isNotEmpty()
        updateSettingsDifficulty(screen)

        val showKeySettings = state.canInteract && state.authenticated && !state.profileRequired &&
            state.userProfile != null
        openAiKeySettings.isVisible = showKeySettings
        if (!showKeySettings) activeDialog?.dismiss()
        val canEditKey = canEditOpenAiKey(state)
        openAiKeyStatus.setText(when {
            state.openAiKeyBusy -> R.string.openai_key_working
            state.openAiKeyConfigured -> R.string.openai_key_configured
            else -> R.string.openai_key_missing
        })
        openAiKeyUsage.setText(R.string.openai_key_usage)
        openAiKeyEdit.isVisible = true
        openAiKeyEdit.setText(if (state.openAiKeyConfigured) R.string.openai_key_replace else R.string.openai_key_add)
        openAiKeyEdit.isEnabled = canEditKey
        openAiKeyRemove.isVisible = state.openAiKeyConfigured
        openAiKeyRemove.isEnabled = canEditKey
        openAiConnectionNote.isVisible = state.openAiKeyConfigured
        openAiConnectionCheck.isVisible = state.openAiKeyConfigured
        openAiConnectionCheck.isEnabled = canEditKey && state.openAiKeyConfigured
        val result = when (state.openAiCheck) {
            OpenAiCheck.IDLE -> null
            OpenAiCheck.CHECKING -> R.string.openai_check_checking to SeedTone.INFORMATIVE
            OpenAiCheck.READY -> R.string.openai_check_ready to SeedTone.POSITIVE
            OpenAiCheck.INVALID_KEY -> R.string.openai_check_invalid_key to SeedTone.CRITICAL
            OpenAiCheck.RATE_LIMITED -> R.string.openai_check_rate_limited to SeedTone.WARNING
            OpenAiCheck.FAILED -> R.string.openai_check_failed to SeedTone.CRITICAL
        }
        openAiConnectionStatus.isVisible = state.openAiKeyConfigured && result != null
        if (result != null) {
            val (message, tone) = result
            val text = getString(message)
            if (openAiConnectionStatus.text != text) openAiConnectionStatus.text = text
            openAiConnectionStatus.seedCallout(tone)
        }
    }

    private fun createScreen(state: HitchTalkState): ViewBinding = when (state.screen) {
        Screen.ONBOARDING -> if (!authFormVisible && !state.profileRequired) {
            ScreenOnboardingBinding.inflate(layoutInflater).apply {
                start.setOnClickListener { openAuthForm(registering = true) }
                login.setOnClickListener { openAuthForm(registering = false) }
                microphone.setOnClickListener { requestMicrophone() }
            }
        } else ScreenAuthBinding.inflate(layoutInflater).apply {
            gender.adapter = choices(R.array.auth_gender_choices)
            consent.setText(R.string.auth_account_consent)
            submit.setOnClickListener { submitAuth(this) }
            toggleMode.setOnClickListener {
                signupMode = !signupMode
                passwordConfirm.text?.clear()
                listOf(email, password, passwordConfirm, name, birthDate).forEach { it.error = null }
                status.text = ""
                renderAuth(this, this@MainActivity.state.copy(message = 0))
            }
            resetPassword.setOnClickListener {
                if (validEmail(this)) viewModel.resetPassword(email.text.toString().trim())
            }
            birthDate.setOnClickListener { chooseBirthDate(this) }
            birthDate.keyListener = null
            birthDate.isFocusableInTouchMode = true
            birthDate.isClickable = true
            birthDate.showSoftInputOnFocus = false
            birthDate.setOnKeyListener { _, key, event ->
                if (key !in setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)) {
                    return@setOnKeyListener false
                }
                if (event.action == KeyEvent.ACTION_UP) chooseBirthDate(this)
                true
            }
            password.setOnEditorActionListener { _, action, _ ->
                if (action != EditorInfo.IME_ACTION_DONE) false
                else { submitAuth(this); true }
            }
        }
        Screen.SETUP -> ScreenSetupBinding.inflate(layoutInflater).apply {
            level.adapter = choices(R.array.settings_levels)
            level.setSelection(englishLevels.indexOf(state.preferences.englishLevel).coerceAtLeast(0))
            aiConsent.isChecked = state.preferences.aiConsentGranted
            microphone.setOnClickListener { requestMicrophone() }
            back.setOnClickListener { viewModel.goBack() }
            completeSetup.setOnClickListener {
                val chosen = englishLevels.getOrElse(level.selectedItemPosition) { "beginner" }
                viewModel.completeSetup(chosen, aiConsent.isChecked)
            }
        }
        Screen.HOME -> ScreenHomeBinding.inflate(layoutInflater).apply {
            bindReadiness(readiness)
            boardAlex.setOnClickListener { viewModel.board(Traveler.ALEX) }
            boardSara.setOnClickListener { viewModel.board(Traveler.SARA) }
            collection.setOnClickListener { viewModel.openCollection() }
            settings.setOnClickListener { viewModel.openSettings() }
            history.setOnClickListener { viewModel.openHistory() }
            useRecommendation.setOnClickListener { viewModel.useRecommendation() }
        }
        Screen.RIDE -> ScreenRideBinding.inflate(layoutInflater).apply {
            rideQuoteExpanded = false
            pause.setOnClickListener { viewModel.togglePause() }
            dropOff.setOnClickListener { viewModel.dropOff() }
            retry.setOnClickListener { viewModel.retryVoice() }
            toggleQuote.setOnClickListener {
                if (!this@MainActivity.state.canInteract) return@setOnClickListener
                rideQuoteExpanded = !rideQuoteExpanded
                renderRide(this, this@MainActivity.state)
            }
            cancelRide.setOnClickListener {
                confirmAction(R.string.voice_ride_cancel_confirm, R.string.voice_ride_cancel) { viewModel.cancelRide() }
            }
        }
        Screen.REPORT -> ScreenReportBinding.inflate(layoutInflater).apply {
            back.setOnClickListener { viewModel.goBack() }
            collection.setOnClickListener { viewModel.openCollection() }
            done.setOnClickListener { viewModel.finishReport() }
            confirmMemory.setOnClickListener {
                this@MainActivity.state.report?.let { editMemory(Traveler.fromId(it.personaId), reviewReport = true) }
            }
        }
        Screen.HISTORY -> ScreenHistoryBinding.inflate(layoutInflater).apply {
            val historyBinding = this
            historyFilter.adapter = choices(R.array.history_filter_choices)
            historyFilter.setSelection(historySelection)
            historyFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    historySelection = position
                    if (content === historyBinding) renderHistory(historyBinding, this@MainActivity.state)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            back.setOnClickListener { viewModel.goBack() }
            historyRetry.setOnClickListener { viewModel.refreshHistory() }
        }
        Screen.COLLECTION -> ScreenCollectionBinding.inflate(layoutInflater).apply {
            bindReadiness(readiness)
            back.setOnClickListener { viewModel.goBack() }
            reboardAlex.setOnClickListener { viewModel.board(Traveler.ALEX) }
            reboardSara.setOnClickListener { viewModel.board(Traveler.SARA) }
            manageAlex.setOnClickListener { editMemory(Traveler.ALEX) }
            manageSara.setOnClickListener { editMemory(Traveler.SARA) }
        }
        Screen.SETTINGS -> ScreenSettingsBinding.inflate(layoutInflater).apply {
            listOf(settingsTabDifficulty, settingsTabVoice, settingsTabMemory, settingsTabData)
                .forEachIndexed { index, tab ->
                    tab.setOnClickListener {
                        val current = this@MainActivity.state
                        if (current.canInteract && !current.busy) selectSettingsCategory(this, index)
                    }
                }
            selectSettingsCategory(
                this, if (state.voiceAvailability == VoiceAvailability.CONFIGURATION_REQUIRED) 1 else 0,
            )
            microphoneTest.setOnClickListener { openMicrophoneTest() }
            level.adapter = choices(R.array.settings_levels)
            listOf(settingsLevelEasy, settingsLevelNormal, settingsLevelChallenge)
                .forEachIndexed { index, button ->
                    button.setOnClickListener {
                        val current = this@MainActivity.state
                        if (current.canInteract && !current.busy) {
                            level.setSelection(index)
                            updateSettingsDifficulty(this)
                        }
                    }
                }
            travelerChoice.adapter = choices(R.array.preferred_traveler_choices)
            back.setOnClickListener { viewModel.goBack() }
            openAiKeyEdit.setOnClickListener { editOpenAiKey() }
            openAiConnectionCheck.setOnClickListener {
                val current = this@MainActivity.state
                if (canEditOpenAiKey(current) && current.openAiKeyConfigured) viewModel.checkOpenAiConnection()
            }
            openAiKeyRemove.setOnClickListener {
                if (canEditOpenAiKey(this@MainActivity.state)) {
                    confirmDeletion(R.string.openai_key_remove_confirm) {
                        if (canEditOpenAiKey(this@MainActivity.state)) viewModel.removeOpenAiKey()
                    }
                }
            }
            val options = state.preferences
            level.setSelection(englishLevels.indexOf(options.englishLevel).coerceAtLeast(0))
            travelerChoice.setSelection(
                if (options.personaSelectionMode == "recommended") 0
                else if (options.preferredPersonaId == "sara") 2 else 1,
            )
            greeting.isChecked = options.greetingEnabled
            save.setOnClickListener {
                val traveler = Traveler.entries.getOrNull(travelerChoice.selectedItemPosition - 1)
                val current = this@MainActivity.state.preferences
                viewModel.savePreferences(
                    current.copy(
                        englishLevel = englishLevels.getOrElse(level.selectedItemPosition) { current.englishLevel },
                        preferredPersonaId = traveler?.personaId ?: current.preferredPersonaId,
                        personaSelectionMode = if (traveler == null) "recommended" else "manual",
                        greetingEnabled = greeting.isChecked,
                        speechRate = 1f,
                    )
                )
            }
            alexMemory.setOnClickListener { editMemory(Traveler.ALEX) }
            saraMemory.setOnClickListener { editMemory(Traveler.SARA) }
            settingsAlexMemoryDelete.setOnClickListener { chooseMemoryDeletion(Traveler.ALEX) }
            settingsSaraMemoryDelete.setOnClickListener { chooseMemoryDeletion(Traveler.SARA) }
            history.setOnClickListener { viewModel.openHistory() }
            openSetup.setOnClickListener { viewModel.openSetup() }
            clearHistory.setOnClickListener {
                confirmDeletion(R.string.settings_history_clear_confirm) { viewModel.clearHistory() }
            }
            sync.setOnClickListener { viewModel.retrySync() }
            logout.setOnClickListener { viewModel.signOut() }
            deleteAccount.setOnClickListener {
                deleteAccountWithPassword()
            }
        }
    }

    private fun selectSettingsCategory(screen: ScreenSettingsBinding, selected: Int) = with(screen) {
        listOf(settingsTabDifficulty, settingsTabVoice, settingsTabMemory, settingsTabData)
            .forEachIndexed { index, tab -> tab.isSelected = index == selected }
        settingsDifficultySection.isVisible = selected == 0
        settingsVoiceSection.isVisible = selected == 1
        settingsMemorySection.isVisible = selected == 0 || selected == 2
        settingsTravelerRow.isVisible = selected == 2
        history.isVisible = selected == 2
        settingsDataSection.isVisible = selected == 3
        settingsHistoryDeleteRow.isVisible = selected == 0 || selected == 3
        save.isVisible = selected == 0 || selected == 2
        settingsScroll.scrollTo(0, 0)
    }

    private fun updateSettingsDifficulty(screen: ScreenSettingsBinding) = with(screen) {
        listOf(settingsLevelEasy, settingsLevelNormal, settingsLevelChallenge)
            .forEachIndexed { index, button -> button.isSelected = index == level.selectedItemPosition }
    }

    private fun renderSetup(screen: ScreenSetupBinding, state: HitchTalkState) = with(screen) {
        back.isVisible = state.preferences.onboardingCompleted
        back.isEnabled = !state.busy
        level.isEnabled = !state.busy
        aiConsent.isEnabled = !state.busy
        completeSetup.isEnabled = !state.busy
        completeSetup.setText(if (state.preferences.onboardingCompleted) R.string.settings_save else R.string.setup_continue)
        microphone.isVisible = !state.microphoneGranted
        microphone.isEnabled = !state.busy
        microphone.setText(if (microphoneNeedsSettings()) R.string.refine_mic_settings else R.string.voice_request_microphone)
        microphoneStatus.setText(
            if (state.microphoneGranted) R.string.setup_microphone_granted else R.string.setup_microphone_optional,
        )
    }

    private data class HistoryKey(
        val reports: List<SessionRecord>,
        val pending: Set<String>,
        val unsaved: Set<String>,
        val loadFailed: Boolean,
        val selection: Int,
        val busy: Boolean,
    )

    private fun renderHistory(screen: ScreenHistoryBinding, state: HitchTalkState) = with(screen) {
        val key = HistoryKey(
            state.previousReports, state.pendingReportIds, state.unsavedReportIds,
            state.historyLoadFailed, historySelection, state.busy,
        )
        if (historyKey == key) return
        historyKey = key
        back.isEnabled = !state.busy
        historyFilter.isEnabled = !state.busy
        val traveler = Traveler.entries.getOrNull(historySelection - 1)
        val records = state.previousReports.filter { traveler == null || traveler.personaId == it.personaId }
        historyEmpty.isVisible = records.isEmpty() || state.historyLoadFailed
        historyRetry.isVisible = state.historyLoadFailed
        historyRetry.isEnabled = !state.busy
        historyEmpty.seedCallout(if (state.historyLoadFailed) SeedTone.WARNING else SeedTone.NEUTRAL)
        historyEmpty.text = when {
            state.historyLoadFailed && records.isEmpty() -> getString(R.string.refine_history_unavailable)
            state.historyLoadFailed -> getString(R.string.refine_history_cached)
            traveler != null -> getString(R.string.refine_history_filtered_empty, traveler.displayName)
            else -> getText(R.string.refine_history_empty)
        }
        historyRows.removeAllViews()
        for (record in records) {
            val name = Traveler.fromId(record.personaId).displayName
            val date = sessionDate(record.endedAtMillis)
            val row = RowSessionBinding.inflate(layoutInflater, historyRows, false)
            row.sessionTitle.text = getString(R.string.history_row_title, name)
            row.sessionDetails.text = getString(R.string.history_row_details, date, record.userTurnCount)
            row.sessionStatus.setText(
                when {
                    record.id in state.unsavedReportIds -> R.string.refine_report_not_saved
                    record.id in state.pendingReportIds -> R.string.history_pending
                    record.scores.sampleSufficient -> R.string.history_scored
                    else -> R.string.report_practice
                },
            )
            row.sessionStatus.seedBadge(when {
                record.id in state.unsavedReportIds -> SeedTone.CRITICAL
                record.id in state.pendingReportIds -> SeedTone.WARNING
                record.scores.sampleSufficient -> SeedTone.POSITIVE
                else -> SeedTone.NEUTRAL
            })
            row.openReport.contentDescription = getString(R.string.history_open_report_for, name, date)
            row.openReport.isEnabled = !state.busy
            row.openReport.setOnClickListener { viewModel.openReport(record.id) }
            historyRows.addView(row.root)
        }
    }

    private fun sessionDate(time: Long): String = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(resources.configuration.locales[0])
        .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

    private fun openAuthForm(registering: Boolean) {
        if (!state.canInteract || state.busy) return
        signupMode = registering
        authFormVisible = true
        currentScreen = null
        render(state)
    }

    private fun renderWelcome(screen: ScreenOnboardingBinding, state: HitchTalkState) = with(screen) {
        alexAvatar.update(AvatarKind.ALEX, AvatarMood.IDLE, still = false)
        saraAvatar.update(AvatarKind.SARA, AvatarMood.IDLE, still = false)
        start.isEnabled = !state.busy
        login.isEnabled = !state.busy
        microphone.isEnabled = !state.busy && !state.microphoneGranted
        microphone.setText(if (state.microphoneGranted) R.string.zip_welcome_permission_granted else R.string.hitch_allow)
    }

    private fun renderAuth(screen: ScreenAuthBinding, state: HitchTalkState) = with(screen) {
        val registering = signupMode || state.profileRequired
        consent.isVisible = registering
        consent.isEnabled = !state.busy
        listOf(email, password, passwordConfirm, name, birthDate, gender).forEach { it.isEnabled = !state.busy }
        mainAvatar.update(
            AvatarKind.MAIN,
            if (state.busy) AvatarMood.THINKING else AvatarMood.IDLE,
            still = false,
        )
        modeTitle.setText(
            when {
                state.profileRequired -> R.string.cloud_complete_profile
                registering -> R.string.auth_signup
                else -> R.string.auth_login
            }
        )
        registrationFields.isVisible = registering
        confirmContainer.isVisible = registering && !state.profileRequired
        email.isVisible = !state.profileRequired
        emailLabel.isVisible = !state.profileRequired
        password.isVisible = !state.profileRequired
        passwordLabel.isVisible = !state.profileRequired
        password.imeOptions = if (registering) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE
        toggleMode.isVisible = !state.profileRequired
        resetPassword.isVisible = !registering
        submit.setText(
            when {
                state.profileRequired -> R.string.auth_profile_finish
                registering -> R.string.auth_signup
                else -> R.string.auth_login
            }
        )
        toggleMode.setText(if (registering) R.string.auth_login else R.string.auth_signup)
        submit.isEnabled = !state.busy
        toggleMode.isEnabled = !state.busy
        resetPassword.isEnabled = !state.busy
        progress.isVisible = state.busy
        if (state.message != 0) showAuthFeedback(this, state.message)
        else if (state.busy) status.text = ""
        status.isVisible = status.text.isNotBlank()
        if (state.authenticated) {
            password.text?.clear()
            passwordConfirm.text?.clear()
        }
    }

    private fun submitAuth(screen: ScreenAuthBinding) = with(screen) {
        if (!state.canInteract || state.busy) return
        val profileOnly = state.profileRequired
        val registering = signupMode || profileOnly
        if (!profileOnly && !validEmail(this)) return
        if (!profileOnly && password.text.isNullOrEmpty()) {
            showAuthFeedback(this, R.string.auth_required_fields, password)
            return
        }
        if (registering && !consent.isChecked) {
            showAuthFeedback(this, R.string.auth_consent_required)
            return
        }
        if (registering && !profileOnly && password.text.toString() != passwordConfirm.text.toString()) {
            showAuthFeedback(this, R.string.auth_password_mismatch, passwordConfirm)
            return
        }
        val profile = if (registering) {
            val birthday = runCatching { LocalDate.parse(birthDate.text.toString()) }.getOrNull()
            if (name.text.isNullOrBlank() || gender.selectedItemPosition == 0) {
                showAuthFeedback(this, R.string.auth_required_fields, name.takeIf { it.text.isNullOrBlank() })
                return
            }
            if (birthday == null || birthday > LocalDate.now()) {
                showAuthFeedback(this, R.string.auth_invalid_birth_date, birthDate)
                return
            }
            UserProfile(name.text.toString().trim(), birthday.toString(), gender.selectedItem.toString())
        } else null
        viewModel.authenticate(email.text.toString().trim(), password.text.toString(), profile)
    }

    private fun validEmail(screen: ScreenAuthBinding): Boolean {
        val valid = Patterns.EMAIL_ADDRESS.matcher(screen.email.text.toString().trim()).matches()
        if (!valid) showAuthFeedback(screen, R.string.auth_invalid_email, screen.email)
        return valid
    }

    private fun showAuthFeedback(screen: ScreenAuthBinding, @StringRes message: Int, field: EditText? = null) {
        screen.status.seedCallout(messageTone(message))
        screen.status.setText(message)
        screen.status.isVisible = true
        field?.error = getString(message)
    }

    private fun chooseBirthDate(screen: ScreenAuthBinding) {
        if (!state.canInteract) return
        val initial = runCatching { LocalDate.parse(screen.birthDate.text.toString()) }
            .getOrDefault(LocalDate.now().minusYears(25))
        activeDialog = DatePickerDialog(
            this,
            { _, year, month, day -> screen.birthDate.setText(LocalDate.of(year, month + 1, day).toString()) },
            initial.year, initial.monthValue - 1, initial.dayOfMonth,
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
            show()
        }
    }

    private fun microphoneNeedsSettings(): Boolean =
        getPreferences(MODE_PRIVATE).getBoolean(MICROPHONE_REQUESTED, false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

    private fun requestMicrophone() {
        if (!state.canInteract || state.busy || state.microphoneGranted) return
        when {
            microphoneNeedsSettings() -> confirmAction(R.string.refine_mic_settings_detail, R.string.refine_mic_settings) {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.refine_mic_settings_detail, Toast.LENGTH_LONG).show()
                }
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                confirmAction(R.string.refine_mic_detail, R.string.voice_request_microphone, ::launchMicrophonePermission)
            else -> launchMicrophonePermission()
        }
    }

    private fun launchMicrophonePermission() {
        if (!state.canInteract || state.busy) return
        getPreferences(MODE_PRIVATE).edit().putBoolean(MICROPHONE_REQUESTED, true).apply()
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun renderRide(screen: ScreenRideBinding, state: HitchTalkState) = with(screen) {
        val pickingUp = state.voice.phase in setOf(VoicePhase.BOARDING, VoicePhase.GREETING)
        normalHeader.isVisible = !pickingUp
        root.setBackgroundColor(ContextCompat.getColor(this@MainActivity,
            if (pickingUp) R.color.hitch_brand_weak else R.color.hitch_surface,
        ))
        title.text = getString(R.string.hitch_ride_title, state.traveler.displayName)
        clock.text = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        pickupCue.isVisible = pickingUp
        pickupCue.setText(if (state.voice.phase == VoicePhase.BOARDING) {
            R.string.zip_pickup_door
        } else R.string.voice_greeting)
        traveler.text = if (pickingUp) getString(
            if (state.voice.phase == VoicePhase.BOARDING) R.string.zip_pickup_boarding else R.string.zip_pickup_ready,
            state.traveler.displayName,
        ) else state.traveler.displayName
        travelerAvatar.setBackgroundResource(
            if (pickingUp) R.drawable.hitch_zip_pickup_halo else R.drawable.hitch_zip_avatar_halo,
        )
        travelerAvatar.update(
            if (state.traveler == Traveler.ALEX) AvatarKind.ALEX else AvatarKind.SARA,
            when (state.voice.phase) {
                VoicePhase.THINKING -> AvatarMood.THINKING
                VoicePhase.BOARDING, VoicePhase.GREETING, VoicePhase.SPEAKING -> AvatarMood.HAPPY
                else -> AvatarMood.IDLE
            },
            still = !state.canInteract || state.voice.phase in setOf(
                VoicePhase.PAUSED, VoicePhase.SAFETY_MUTED, VoicePhase.ERROR,
            ),
        )
        gearStatus.setText(if (state.gearFresh) gearStatusLabel(state.gear) else R.string.refine_vehicle_check)
        gearStatus.seedBadge(when {
            !state.gearFresh -> SeedTone.WARNING
            state.gear == Gear.PARK -> SeedTone.NEUTRAL
            state.gear == Gear.DRIVE -> SeedTone.NEUTRAL
            else -> SeedTone.WARNING
        })
        val keyRejected = state.openAiCheck == OpenAiCheck.INVALID_KEY
        voiceStatus.text = when {
            keyRejected && state.canInteract && state.voice.phase == VoicePhase.ERROR ->
                getString(R.string.openai_check_invalid_title)
            state.voice.phase == VoicePhase.BOARDING -> getString(R.string.zip_pickup_wait, state.traveler.displayName)
            state.voice.phase in setOf(VoicePhase.SPEAKING, VoicePhase.GREETING) ->
                getString(R.string.zip_ride_speaking, state.traveler.displayName)
            else -> getString(voiceLabel(state.voice.phase))
        }
        val voiceColor = ContextCompat.getColor(this@MainActivity, when (state.voice.phase) {
            VoicePhase.ERROR -> R.color.hitch_danger
            VoicePhase.SAFETY_MUTED -> R.color.hitch_warning
            VoicePhase.PAUSED, VoicePhase.IDLE -> R.color.hitch_muted
            else -> R.color.hitch_accent
        })
        voiceStatus.setTextColor(voiceColor)
        voiceWave.imageTintList = ColorStateList.valueOf(voiceColor)
        voiceWave.isVisible = state.voice.phase in setOf(
            VoicePhase.BOARDING, VoicePhase.GREETING, VoicePhase.SPEAKING, VoicePhase.LISTENING, VoicePhase.THINKING,
        )
        voiceIndicator.background = if (pickingUp) null else ContextCompat.getDrawable(
            this@MainActivity, R.drawable.hitch_zip_voice_pill,
        )
        pickupQuote.isVisible = pickingUp && state.voice.lastUtterance.isNotBlank()
        pickupQuote.text = if (pickupQuote.isVisible) state.voice.lastUtterance else ""
        parkedControls.isVisible = state.canInteract && !pickingUp
        lockMessage.isVisible = !state.canInteract
        lockMessage.setText(
            if (state.gearFresh && state.gear == Gear.DRIVE) R.string.hitch_drive_ride else R.string.hitch_wait_for_park,
        )
        pause.setText(if (state.isPaused) R.string.hitch_ride_resume else R.string.hitch_ride_paused)
        pause.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (state.isPaused) R.drawable.hitch_zip_play else R.drawable.hitch_zip_pause, 0, 0, 0,
        )
        pause.isEnabled = state.voice.phase in setOf(
            VoicePhase.LISTENING, VoicePhase.THINKING, VoicePhase.SPEAKING, VoicePhase.PAUSED
        )
        dropOff.isEnabled = state.voiceReady && state.voice.phase !in setOf(
            VoicePhase.BOARDING, VoicePhase.GREETING, VoicePhase.DROPPING_OFF, VoicePhase.REPORTING
        ) && (state.voice.phase != VoicePhase.SAFETY_MUTED || state.voice.safetySettled)
        dropOff.setText(if (state.voice.dropoffPending) R.string.voice_finish_pending else R.string.hitch_ride_drop_off)
        retry.isVisible = state.voice.phase == VoicePhase.ERROR ||
            (state.voice.phase == VoicePhase.SAFETY_MUTED && state.voiceAvailability == VoiceAvailability.CONNECTION_REQUIRED)
        retry.isEnabled = !keyRejected &&
            state.voiceAvailability in setOf(VoiceAvailability.READY, VoiceAvailability.CONNECTION_REQUIRED)
        cancelRide.isVisible = state.canInteract && state.voice.phase in setOf(VoicePhase.ERROR, VoicePhase.SAFETY_MUTED)
        recoveryActions.isVisible = retry.isVisible || cancelRide.isVisible
        val hasQuote = state.canInteract && !pickingUp && state.voice.lastUtterance.isNotBlank()
        rideQuoteExpanded = false
        toggleQuote.isVisible = false
        quotePanel.isVisible = hasQuote
        quote.text = if (quotePanel.isVisible) state.voice.lastUtterance else ""
        quoteHint.isVisible = state.voice.phase in setOf(VoicePhase.LISTENING, VoicePhase.SPEAKING)
        quoteHint.text = getString(R.string.zip_ride_reply, state.traveler.displayName)
    }

    private fun renderReport(screen: ScreenReportBinding, state: HitchTalkState) = with(screen) {
        val report = state.report
        val traveler = report?.let { Traveler.fromId(it.personaId) } ?: state.traveler
        val analyzing = state.voice.phase == VoicePhase.REPORTING
        val saving = report != null && report.id == state.savingReportId
        val unsaved = report != null && report.id in state.unsavedReportIds
        title.text = if (report == null) getString(R.string.report_title_with_traveler, traveler.displayName)
        else {
            val duration = if (report.activeConversationSec >= 60) {
                getString(R.string.journey_minutes, report.activeConversationSec / 60)
            } else getString(R.string.journey_seconds, report.activeConversationSec)
            getString(R.string.journey_report_title, traveler.displayName, duration)
        }
        reportStatus.setText(when {
            analyzing || report == null -> R.string.report_analysis_pending
            saving -> R.string.refine_report_saving
            unsaved -> R.string.refine_report_not_saved
            report.id in state.pendingReportIds -> R.string.history_pending
            else -> R.string.history_saved
        })
        reportStatus.seedBadge(when {
            analyzing || report == null || saving -> SeedTone.INFORMATIVE
            unsaved -> SeedTone.CRITICAL
            report.id in state.pendingReportIds -> SeedTone.WARNING
            else -> SeedTone.POSITIVE
        })
        travelerAvatar.update(
            if (traveler == Traveler.ALEX) AvatarKind.ALEX else AvatarKind.SARA,
            AvatarMood.HAPPY,
            still = false,
        )
        renderReportMetric(speechValue, speechChange, speechProgress, report, state.previousReports) {
            it.scores.speechVolumeScore
        }
        renderReportMetric(completionValue, completionChange, completionProgress, report, state.previousReports) {
            it.scores.sentenceCompletenessScore
        }
        renderReportMetric(varietyValue, varietyChange, varietyProgress, report, state.previousReports) {
            it.scores.expressionVarietyScore
        }
        speechCount.isVisible = report != null
        completionCount.isVisible = report?.analysisStatus == "completed"
        varietyCount.isVisible = report?.analysisStatus == "completed"
        speechCount.text = getString(R.string.journey_metric_speech_count, report?.englishWordCount ?: 0)
        completionCount.text = getString(R.string.journey_metric_completion_count,
            report?.scores?.completeSentenceCount ?: 0, report?.scores?.evaluableSentenceCount ?: 0)
        varietyCount.text = getString(R.string.journey_metric_variety_count, report?.scores?.expressionCategories?.size ?: 0)
        reportDetails.text = if (report == null) getString(R.string.report_analysis_pending) else buildList {
            add(sessionDate(report.endedAtMillis))
            add(getString(R.string.report_raw_counts, report.englishWordCount, report.userTurnCount,
                report.activeConversationSec.toInt()))
            if (report.analysisStatus == "completed") add(getString(
                R.string.report_analysis_counts, report.scores.evaluableSentenceCount,
                report.scores.completeSentenceCount, report.scores.expressionCategories.size,
            ))
            report.scores.unevaluableSentenceCount?.let { add(getString(R.string.report_unevaluable_count, it)) }
            add(getString(if (report.scores.sampleSufficient) R.string.report_comparison_note else R.string.report_estimate_note))
        }.joinToString("\n")
        val profile = state.profiles[traveler.personaId]
        relationshipValue.text = if (profile == null) getString(R.string.report_relationship_empty)
        else getString(R.string.journey_relationship_detail, travelerLevelLabel(profile.xp), profile.xp)
        relationshipMeetings.isVisible = profile != null
        relationshipMeetings.text = getString(R.string.journey_meetings, profile?.meetingCount ?: 0)
        val corrections = report?.corrections.orEmpty()
        if (correctionCards.tag != corrections) {
            correctionCards.removeAllViews()
            corrections.forEach { correction ->
                val row = RowReportCorrectionBinding.inflate(layoutInflater, correctionCards, false)
                row.original.text = correction.original
                row.natural.text = correction.natural
                row.explanation.text = correction.explanationKo
                row.explanation.isVisible = correction.explanationKo.isNotBlank()
                correctionCards.addView(row.root)
            }
            correctionCards.tag = corrections
        }
        correctionCards.isVisible = corrections.isNotEmpty()
        learningValue.isVisible = corrections.isEmpty()
        learningValue.text = getString(when {
                report == null -> R.string.report_analysis_pending
                report.analysisStatus == "completed" -> R.string.report_no_corrections
                report.userTurnCount == 0 -> R.string.report_not_enough_speech
                else -> R.string.report_analysis_unavailable_final
            })
        val summary = report?.summaryKo.orEmpty()
        summarySection.isVisible = summary.isNotBlank()
        summaryValue.text = summary

        val candidates = pendingReportMemories(state)
        val hasCandidates = candidates.isNotEmpty()
        memorySection.isVisible = hasCandidates
        memoryLabel.isVisible = hasCandidates
        memoryValue.isVisible = hasCandidates
        memoryValue.text = candidates.joinToString("\n") { it.text }
        val reviewAvailable = canReviewReportMemory(state)
        confirmMemory.isVisible = hasCandidates || (reviewAvailable && previousReportPromise(state) != null)
        confirmMemory.isEnabled = reviewAvailable
        confirmMemory.setText(if (hasCandidates) R.string.report_memory_review else R.string.report_previous_promise_review)
        back.isEnabled = !state.busy && !analyzing
        collection.isEnabled = !state.busy && !analyzing
        collection.isVisible = !state.reportFromHistory
        done.isEnabled = !state.busy && !analyzing
        done.setText(if (state.reportFromHistory) R.string.history_finish_report else R.string.journey_report_confirm)
    }

    private fun renderReportMetric(
        valueView: TextView,
        changeView: TextView,
        progressView: ProgressBar,
        report: SessionRecord?,
        history: List<SessionRecord>,
        metric: (SessionRecord) -> Int?,
    ) {
        val score = report?.let(metric)
        valueView.text = when {
            report == null -> getString(R.string.report_metric_unavailable)
            score != null -> getString(R.string.journey_metric_score, score)
            else -> getString(if (report.scores.sampleSufficient) R.string.report_unrated else R.string.report_practice)
        }
        valueView.contentDescription = scoreText(report, history, metric)
        val change = report?.let { ReportComparison.change(it, history, metric) }
        changeView.isVisible = change != null
        changeView.text = change?.let { getString(R.string.journey_metric_change, it) }.orEmpty()
        changeView.setTextColor(ContextCompat.getColor(this, when {
            change == null || change == 0 -> R.color.hitch_muted
            change > 0 -> R.color.journey_positive
            else -> R.color.hitch_danger
        }))
        progressView.isVisible = score != null
        progressView.progress = score ?: 0
    }

    private fun scoreText(
        report: SessionRecord?,
        history: List<SessionRecord>,
        metric: (SessionRecord) -> Int?,
    ): String {
        if (report == null) return getString(R.string.report_metric_unavailable)
        val value = metric(report) ?: return getString(
            if (report.scores.sampleSufficient) R.string.report_unrated else R.string.report_practice,
        )
        val change = ReportComparison.change(report, history, metric)
        return if (change == null) getString(R.string.report_score, value)
        else getString(R.string.report_score_change, value, change)
    }

    private fun pendingReportMemories(state: HitchTalkState) =
        if (state.reportFromHistory || state.report == null) emptyList() else state.memoryCandidates.filter { candidate ->
            val limit = when (candidate.kind) {
                "userJob" -> 120
                "userLike" -> 100
                "lastPromise" -> 240
                else -> 0
            }
            candidate.text.isNotBlank() && candidate.text.length <= limit
        }

    private fun previousReportPromise(state: HitchTalkState): String? =
        state.reportPreviousPromise?.takeIf {
            !state.reportFromHistory && !state.reportPromiseReviewed &&
                (state.report?.userTurnCount ?: 0) > 0 && it.isNotBlank()
        }

    private fun canReviewReportMemory(state: HitchTalkState): Boolean {
        val report = state.report ?: return false
        return state.screen == Screen.REPORT && state.canInteract && state.authenticated &&
            state.accountLoad == AccountLoad.READY && !state.reportFromHistory && !state.busy &&
            state.voice.phase != VoicePhase.REPORTING && report.id != state.savingReportId &&
            report.id !in state.unsavedReportIds && report.id !in state.pendingReportIds
    }

    private fun renderCollection(screen: ScreenCollectionBinding, state: HitchTalkState) = with(screen) {
        renderReadiness(readiness, state)
        val alex = state.profiles["alex"]
        val sara = state.profiles["sara"]
        travelerCount.text = getString(R.string.journey_dex_count, listOf(alex, sara).count { (it?.meetingCount ?: 0) > 0 })
        alexAvatar.update(AvatarKind.ALEX, AvatarMood.IDLE, still = false)
        saraAvatar.update(AvatarKind.SARA, AvatarMood.IDLE, still = false)
        renderTravelerProfile(alex, alexHistory, alexLastMeeting, alexLevel,
            listOf(alexStage1, alexStage2, alexStage3), listOf(alexStageLabel1, alexStageLabel2, alexStageLabel3))
        renderTravelerProfile(sara, saraHistory, saraLastMeeting, saraLevel,
            listOf(saraStage1, saraStage2, saraStage3), listOf(saraStageLabel1, saraStageLabel2, saraStageLabel3))
        alexMemory.text = memoryText(alex?.memory)
        saraMemory.text = memoryText(sara?.memory)
        actionPriority(reboardAlex, state.traveler == Traveler.ALEX)
        actionPriority(reboardSara, state.traveler == Traveler.SARA)
        reboardAlex.isEnabled = state.voiceReady && !state.busy
        reboardSara.isEnabled = state.voiceReady && !state.busy
        manageAlex.isEnabled = !state.busy
        manageSara.isEnabled = !state.busy
    }

    private fun renderTravelerProfile(
        profile: TravelerProfile?,
        meetings: TextView,
        lastMeeting: TextView,
        level: TextView,
        stages: List<ProgressBar>,
        stageLabels: List<TextView>,
    ) {
        val xp = profile?.xp?.coerceAtLeast(0) ?: 0
        meetings.text = getString(R.string.journey_dex_meetings, profile?.meetingCount ?: 0)
        val lastDate = profile?.lastSessionAtMillis?.takeIf { it > 0 }?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        lastMeeting.text = when (lastDate) {
            null -> getString(R.string.journey_dex_no_meeting)
            LocalDate.now() -> getString(R.string.journey_dex_today)
            else -> DateTimeFormatter.ofPattern("M월 d일", resources.configuration.locales[0]).format(lastDate)
        }
        val levelName = travelerLevelLabel(xp)
        level.text = if (xp >= 60) getString(R.string.journey_stage_complete, levelName, xp)
        else getString(R.string.journey_stage_progress, levelName, xp, if (xp < 20) 20 else 60)
        val progress = listOf(xp.coerceAtMost(20) * 100 / 20, (xp - 20).coerceIn(0, 40) * 100 / 40,
            if (xp >= 60) 100 else 0)
        stages.forEachIndexed { index, bar -> bar.progress = progress[index] }
        stageLabels.forEachIndexed { index, label ->
            val unlocked = xp >= listOf(0, 20, 60)[index]
            label.setTextColor(ContextCompat.getColor(this, if (unlocked) R.color.hitch_accent else R.color.hitch_muted))
        }
    }

    private fun travelerLevelLabel(xp: Int): String = getString(when {
        xp >= 60 -> R.string.journey_stage_best
        xp >= 20 -> R.string.journey_stage_comfortable
        else -> R.string.journey_stage_first
    })

    private fun memoryText(memory: ConfirmedMemory?): String {
        if (memory == null) return getString(R.string.collection_memory_empty)
        return listOf(memory.userJob, memory.userLikes.joinToString(", "), memory.lastPromise)
            .filter(String::isNotBlank).joinToString("\n") { getString(R.string.journey_memory_item, it) }
            .ifBlank { getString(R.string.collection_memory_empty) }
    }

    private fun editMemory(traveler: Traveler, reviewReport: Boolean = false) {
        if (!state.canInteract || state.busy) return
        if (reviewReport && !canReviewReportMemory(state)) return
        val candidates = if (reviewReport) pendingReportMemories(state) else emptyList()
        val previousPromise = if (reviewReport) previousReportPromise(state) else null
        val onlyReviewingPromise = reviewReport && candidates.isEmpty()
        if (onlyReviewingPromise && previousPromise == null) return

        var memory = state.profiles[traveler.personaId]?.memory ?: ConfirmedMemory()
        candidates.forEach { candidate ->
            memory = when (candidate.kind) {
                "userJob" -> memory.copy(userJob = candidate.text)
                "userLike" -> memory.copy(userLikes = (memory.userLikes + candidate.text).distinct().take(5))
                "lastPromise" -> memory.copy(lastPromise = candidate.text)
                else -> memory
            }
        }
        val padding = resources.getDimensionPixelSize(R.dimen.hitch_gutter)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun field(@StringRes label: Int, value: String, limit: Int): EditText = AppCompatEditText(this).apply {
            val labelView = TextView(this@MainActivity).apply {
                setText(label)
                setTextAppearance(R.style.HitchLabel)
            }
            id = View.generateViewId()
            labelView.labelFor = id
            form.addView(labelView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding / 2 })
            styleSeedInput()
            setText(value)
            filters = arrayOf(InputFilter.LengthFilter(limit))
            form.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val fields = if (onlyReviewingPromise) null else Triple(
            field(R.string.memory_job, memory.userJob, 120),
            field(R.string.memory_likes, memory.userLikes.joinToString(", "), 504),
            field(if (reviewReport) R.string.report_next_promise else R.string.memory_promise, memory.lastPromise, 240),
        )
        val continued = previousPromise?.let { oldPromise ->
            val label = TextView(this).apply {
                text = getString(R.string.report_previous_promise_value, oldPromise)
                setTextAppearance(R.style.HitchLabel)
            }
            form.addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding / 2 })
            CheckBox(this).apply {
                setText(R.string.memory_confirm_promise)
                form.addView(this)
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(
                if (onlyReviewingPromise) getString(R.string.report_previous_promise_review)
                else getString(R.string.memory_title, traveler.displayName),
            )
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(R.string.memory_save, null)
            .apply {
                if (!onlyReviewingPromise) {
                    setNeutralButton(R.string.action_delete) { _, _ -> chooseMemoryDeletion(traveler) }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .apply { styleSeedActions(neutralCritical = true) }
        activeDialog = dialog
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        if (onlyReviewingPromise) {
            save.isEnabled = false
            continued?.setOnCheckedChangeListener { _, checked -> save.isEnabled = checked }
        }
        save.setOnClickListener {
            if (!state.canInteract || reviewReport && !canReviewReportMemory(state)) {
                dialog.dismiss()
                return@setOnClickListener
            }
            val updatedMemory = fields?.let { (job, likes, promise) ->
                val selectedLikes = likes.text.toString().split(",").map(String::trim).filter(String::isNotBlank)
                if (selectedLikes.size > 5 || selectedLikes.any { it.length > 100 }) {
                    likes.error = getString(R.string.memory_likes_limit)
                    return@setOnClickListener
                }
                ConfirmedMemory(job.text.toString(), selectedLikes, promise.text.toString())
            }
            val continuedPromise = continued?.isChecked == true
            if (updatedMemory != null || continuedPromise) {
                viewModel.saveMemory(traveler, updatedMemory, reviewReport, continuedPromise)
            }
            dialog.dismiss()
        }
    }

    private fun chooseMemoryDeletion(traveler: Traveler) {
        if (!state.canInteract || state.busy) return
        activeDialog = AlertDialog.Builder(this)
            .setTitle(R.string.memory_deletion_title)
            .setItems(arrayOf(getString(R.string.memory_delete_only), getString(R.string.memory_delete_with_records))) { _, choice ->
                if (choice == 0) confirmDeletion(R.string.memory_delete_only_confirm) { viewModel.deleteMemory(traveler) }
                else confirmDeletion(R.string.memory_delete_confirm) { viewModel.deleteTraveler(traveler) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .apply { styleSeedActions() }
    }

    private fun confirmDeletion(@StringRes message: Int, action: () -> Unit) {
        confirmAction(message, R.string.action_delete, action)
    }

    private fun confirmAction(@StringRes message: Int, @StringRes label: Int, action: () -> Unit) {
        if (!state.canInteract || state.busy) return
        activeDialog = AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(label) { _, _ ->
                if (state.canInteract) action()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .apply { styleSeedActions(positiveCritical = label == R.string.action_delete) }
    }

    private fun canTestMicrophone(state: HitchTalkState): Boolean =
        state.screen == Screen.SETTINGS && state.canInteract && !state.safety.requiresDrivingUi &&
            state.authenticated && state.accountLoad == AccountLoad.READY && !state.busy

    private fun openMicrophoneTest() {
        if (!canTestMicrophone(state)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        activeDialog?.dismiss()
        val dialog = MicrophoneTestDialog(this) {
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && canTestMicrophone(state) && state.microphoneGranted
        }
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        dialog.show()
        dialog.styleSeedActions()
    }

    private fun canEditOpenAiKey(state: HitchTalkState): Boolean =
        state.screen == Screen.SETTINGS && state.canInteract && state.authenticated &&
            !state.profileRequired && state.userProfile != null && state.accountLoad == AccountLoad.READY &&
            !state.busy && !state.openAiKeyBusy && state.openAiCheck != OpenAiCheck.CHECKING

    private fun editOpenAiKey() {
        if (!canEditOpenAiKey(state)) return
        val input = AppCompatEditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = object : PasswordTransformationMethod() {
                // Keep newly typed characters masked even when Android is set to briefly show them.
                override fun onTextChanged(text: CharSequence, start: Int, before: Int, count: Int) = Unit
            }
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
            }
            styleSeedInput()
            setHint(R.string.openai_key_title)
        }
        val form = LinearLayout(this).apply {
            val inset = resources.getDimensionPixelSize(R.dimen.hitch_gutter)
            setPadding(inset, inset / 2, inset, 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (state.openAiKeyConfigured) R.string.openai_key_replace else R.string.openai_key_add)
            .setMessage(R.string.openai_key_usage)
            .setView(form)
            .setPositiveButton(R.string.settings_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener {
            input.text?.clear()
            if (activeDialog === dialog) activeDialog = null
        }
        activeDialog = dialog
        dialog.show()
        dialog.styleSeedActions()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.setOnClickListener {
            if (!canEditOpenAiKey(state)) {
                dialog.dismiss()
                return@setOnClickListener
            }
            val entered = input.text
            if (entered.isNullOrBlank()) {
                input.error = getString(R.string.openai_key_input_required)
                return@setOnClickListener
            }
            val value = entered.toString()
            entered.clear()
            dialog.dismiss()
            viewModel.saveOpenAiKey(value)
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            save.performClick()
            true
        }
    }

    private fun deleteAccountWithPassword() {
        if (!state.canInteract || state.busy) return
        val password = AppCompatEditText(this).apply {
            styleSeedInput()
            setHint(R.string.auth_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
        }
        val form = LinearLayout(this).apply {
            val inset = resources.getDimensionPixelSize(R.dimen.hitch_gutter)
            setPadding(inset, 0, inset, 0)
            addView(password, LinearLayout.LayoutParams(-1, -2))
        }
        activeDialog = AlertDialog.Builder(this)
            .setMessage(R.string.settings_delete_confirm)
            .setView(form)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val entered = password.text
                if (state.canInteract && !entered.isNullOrEmpty()) {
                    viewModel.deleteAccount(entered.toString())
                }
                entered?.clear()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .apply { styleSeedActions(positiveCritical = true) }
    }

    private fun EditText.styleSeedInput() {
        val inset = resources.getDimensionPixelSize(R.dimen.hitch_gutter)
        setTextAppearance(R.style.HitchBody)
        setHintTextColor(ContextCompat.getColor(context, R.color.hitch_muted))
        setBackgroundResource(R.drawable.hitch_field)
        backgroundTintList = null
        setPadding(inset, inset / 2, inset, inset / 2)
        minimumHeight = resources.getDimensionPixelSize(R.dimen.hitch_button_height)
    }

    private fun AlertDialog.styleSeedActions(positiveCritical: Boolean = false, neutralCritical: Boolean = false) {
        fun style(which: Int, color: Int) {
            getButton(which)?.apply {
                setAllCaps(false)
                minimumHeight = resources.getDimensionPixelSize(R.dimen.hitch_header_button_height)
                setTextColor(ContextCompat.getColor(context, color))
            }
        }
        style(AlertDialog.BUTTON_POSITIVE, if (positiveCritical) R.color.hitch_danger else R.color.hitch_accent)
        style(AlertDialog.BUTTON_NEGATIVE, R.color.hitch_muted)
        style(AlertDialog.BUTTON_NEUTRAL, if (neutralCritical) R.color.hitch_danger else R.color.hitch_muted)
    }

    private fun show(screen: ViewBinding) {
        forEachAvatar(content?.root) { it.release() }
        historyKey = null
        content = screen
        binding.screenContainer.removeAllViews()
        binding.screenContainer.addView(screen.root)
    }

    private fun forEachAvatar(view: View?, action: (AvatarView) -> Unit) {
        when (view) {
            is AvatarView -> action(view)
            is ViewGroup -> for (index in 0 until view.childCount) {
                forEachAvatar(view.getChildAt(index), action)
            }
        }
    }

    private fun choices(labels: Int): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.spinner_choice, resources.getStringArray(labels)).apply {
            setDropDownViewResource(R.layout.spinner_choice)
        }

    private fun messageTone(@StringRes message: Int): SeedTone = when (message) {
        R.string.cloud_saved, R.string.cloud_preferences_saved, R.string.cloud_memory_saved,
        R.string.cloud_deleted, R.string.settings_history_cleared, R.string.cloud_reset_sent,
        R.string.openai_key_saved, R.string.openai_key_removed -> SeedTone.POSITIVE
        R.string.cloud_pending, R.string.voice_signals_unavailable, R.string.voice_safety_wait,
        R.string.voice_microphone_required, R.string.setup_consent_required -> SeedTone.WARNING
        R.string.cloud_ai_key_missing, R.string.cloud_provider_disabled, R.string.cloud_email_exists,
        R.string.cloud_weak_password, R.string.cloud_auth_failed, R.string.cloud_rules_denied,
        R.string.cloud_rules_public, R.string.cloud_connection_failed, R.string.voice_connection_required,
        R.string.auth_invalid_email, R.string.auth_required_fields, R.string.auth_consent_required,
        R.string.auth_password_mismatch, R.string.auth_invalid_birth_date, R.string.refine_unsaved_message,
        R.string.openai_key_invalid, R.string.openai_key_storage_failed -> SeedTone.CRITICAL
        R.string.general_working, R.string.cloud_saving, R.string.voice_connecting,
        R.string.cloud_complete_profile -> SeedTone.INFORMATIVE
        else -> SeedTone.NEUTRAL
    }

    private fun previewGearButtons(): Map<Gear, View> = mapOf(
        Gear.PARK to binding.gearP, Gear.DRIVE to binding.gearD,
        Gear.REVERSE to binding.gearR, Gear.NEUTRAL to binding.gearN,
    )

    @StringRes
    private fun gearStatusLabel(gear: Gear): Int = when (gear) {
        Gear.PARK -> R.string.hitch_parked
        Gear.DRIVE -> R.string.hitch_driving_lock
        Gear.REVERSE, Gear.NEUTRAL -> R.string.hitch_gear_lock
        Gear.UNKNOWN -> R.string.hitch_unavailable
    }

    @StringRes
    private fun voiceLabel(phase: VoicePhase): Int = when (phase) {
        VoicePhase.IDLE -> R.string.voice_ready
        VoicePhase.BOARDING -> R.string.voice_boarding
        VoicePhase.GREETING -> R.string.voice_greeting
        VoicePhase.LISTENING -> R.string.voice_listening
        VoicePhase.THINKING -> R.string.voice_thinking
        VoicePhase.SPEAKING -> R.string.voice_speaking
        VoicePhase.PAUSED -> R.string.voice_paused
        VoicePhase.SAFETY_MUTED -> R.string.voice_safety_muted
        VoicePhase.DROPPING_OFF -> R.string.voice_dropoff
        VoicePhase.REPORTING -> R.string.voice_reporting
        VoicePhase.ERROR -> R.string.voice_error
    }

    @StringRes
    private fun restrictedVoiceLabel(phase: VoicePhase): Int = when (phase) {
        VoicePhase.LISTENING, VoicePhase.THINKING, VoicePhase.SPEAKING, VoicePhase.GREETING ->
            R.string.restricted_driving_voice_active
        VoicePhase.PAUSED, VoicePhase.SAFETY_MUTED -> R.string.restricted_driving_voice_paused
        VoicePhase.DROPPING_OFF, VoicePhase.REPORTING -> R.string.restricted_driving_voice_finishing
        VoicePhase.ERROR -> R.string.restricted_driving_voice_unavailable
        VoicePhase.IDLE, VoicePhase.BOARDING -> R.string.restricted_driving_voice_preparing
    }

    private companion object {
        const val MICROPHONE_REQUESTED = "microphone_requested"
        val englishLevels = listOf("beginner", "intermediate", "advanced")
    }
}
