package com.kotlinsun.hitchtalk

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kotlinsun.hitchtalk.avatar.AvatarView
import com.kotlinsun.hitchtalk.databinding.ActivityMainBinding
import com.kotlinsun.hitchtalk.databinding.RowSessionBinding
import com.kotlinsun.hitchtalk.databinding.ScreenHistoryBinding
import com.kotlinsun.hitchtalk.databinding.ScreenHomeBinding
import com.kotlinsun.hitchtalk.databinding.ScreenOnboardingBinding
import com.kotlinsun.hitchtalk.databinding.ScreenReportBinding
import com.kotlinsun.hitchtalk.databinding.ScreenRideBinding
import com.kotlinsun.hitchtalk.databinding.ScreenSetupBinding
import com.kotlinsun.hitchtalk.ui.ContentColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PleosUiTest {
    @Test
    fun homeButtonsUseReferencePaletteWithLegibleStates() = onMainThread {
        val context = fixtureContext(Configuration.UI_MODE_NIGHT_NO)
        assertEquals("The supplied design uses a white surface", Color.WHITE, context.getColor(R.color.hitch_surface))
        assertEquals("The primary action uses the reference purple", Color.rgb(91, 67, 255), context.getColor(R.color.hitch_brand_solid))

        val home = ScreenHomeBinding.inflate(LayoutInflater.from(context))
        try {
            assertButtonColors(home.boardAlex, true, R.color.hitch_on_accent, R.color.hitch_brand_solid)
            assertButtonColors(home.boardSara, true, R.color.hitch_ink, R.color.hitch_control)
            assertButtonColors(home.boardAlex, true, R.color.hitch_on_accent, R.color.hitch_brand_pressed, pressed = true)
            assertButtonColors(home.boardSara, true, R.color.hitch_ink, R.color.hitch_control_pressed, pressed = true)
            for (button in listOf(home.boardAlex, home.boardSara)) {
                assertButtonColors(button, false, R.color.hitch_disabled_ink, R.color.hitch_disabled_surface)
            }
        } finally {
            releaseAvatars(home.root)
        }
    }

    @Test
    fun welcomeAvatarsAndActionsFitNarrowAndWidePanels() = onMainThread {
        val inflater = LayoutInflater.from(fixtureContext(Configuration.UI_MODE_NIGHT_NO))
        val panel = ActivityMainBinding.inflate(inflater)
        val welcome = ScreenOnboardingBinding.inflate(inflater, panel.screenContainer, true)
        try {
            for (width in listOf(320, 841, 1682)) {
                welcome.root.scrollTo(0, 0)
                layoutPanel(panel.root, width = width, height = 640)
                assertContainedHorizontally(welcome.root, welcome.alexAvatar)
                assertContainedHorizontally(welcome.root, welcome.saraAvatar)
                assertTrue("Welcome avatars must not overlap", welcome.alexAvatar.right <= welcome.saraAvatar.left)
                for (button in listOf(welcome.microphone, welcome.start, welcome.login)) {
                    assertReachable(welcome.root, button)
                }
            }
        } finally {
            releaseAvatars(welcome.root)
        }
    }

    @Test
    fun narrowPanelStacksTravelersAndKeepsActionsReachable() = onMainThread {
        val inflater = LayoutInflater.from(fixtureContext(Configuration.UI_MODE_NIGHT_NO))
        val panel = ActivityMainBinding.inflate(inflater)

        val home = ScreenHomeBinding.inflate(inflater, panel.screenContainer, true)
        try {
            layoutPanel(panel.root)
            assertStackedTravelers(home)
            assertEquals("Home navigation must move below its title", LinearLayout.VERTICAL, home.homeHeader.orientation)
            assertTrue("Home should scroll in a narrow panel", home.root.canScrollVertically(1))
            assertEquals("History is available through the settings and collection flows", View.GONE, home.history.visibility)
            for (button in listOf(home.collection, home.settings, home.boardAlex, home.boardSara, home.useRecommendation)) {
                assertReachable(home.root, button)
            }
        } finally {
            releaseAvatars(home.root)
            panel.screenContainer.removeAllViews()
        }

        val history = ScreenHistoryBinding.inflate(inflater, panel.screenContainer, true)
        history.historyEmpty.visibility = View.GONE
        val rows = List(6) { index ->
            RowSessionBinding.inflate(inflater, history.historyRows, true).apply {
                sessionTitle.text = "연습 세션 ${index + 1}"
                sessionDetails.text = "2026-11-07 · 4분"
            }
        }
        layoutPanel(panel.root)
        assertTrue("History should scroll through multiple sessions", history.root.canScrollVertically(1))
        assertReachable(history.root, rows.last().openReport)
        panel.screenContainer.removeAllViews()

        val report = ScreenReportBinding.inflate(inflater, panel.screenContainer, true)
        try {
            report.confirmMemory.visibility = View.VISIBLE
            layoutPanel(panel.root)
            assertEquals(LinearLayout.VERTICAL, report.reportColumns.orientation)
            assertReachable(report.root, report.confirmMemory)
            assertReachable(report.root, report.done)
        } finally {
            releaseAvatars(report.root)
        }
    }

    @Test
    fun homeRestoresCardHierarchyAfterWidening() = onMainThread {
        val inflater = LayoutInflater.from(fixtureContext(Configuration.UI_MODE_NIGHT_NO))
        val panel = ActivityMainBinding.inflate(inflater)
        val home = ScreenHomeBinding.inflate(inflater, panel.screenContainer, true)
        try {
            layoutPanel(panel.root)
            assertStackedTravelers(home)

            for (width in listOf(841, 1682)) {
                layoutPanel(panel.root, width = width, height = 640)
                val column = home.root.getChildAt(0)
                assertEquals("Wide home content must be centered", (home.root.width - column.width) / 2, column.left)
                assertEquals(minOf(home.root.width, 1600), column.width)
                assertEquals(LinearLayout.HORIZONTAL, home.homeHeader.orientation)
                assertEquals(LinearLayout.HORIZONTAL, home.travelerCards.orientation)
                val alex = home.alexCard
                val sara = home.saraCard
                assertEquals("Wide cards must start on the same row", alex.top, sara.top)
                assertTrue("Wide cards must have a gap", alex.right < sara.left || sara.right < alex.left)
                assertTrue("The featured Alex card must be wider than Sara's card", alex.width > sara.width && sara.width > 0)
                assertReachable(home.root, home.boardAlex)
                assertReachable(home.root, home.boardSara)
            }

            layoutPanel(panel.root)
            assertStackedTravelers(home)
            assertReachable(home.root, home.boardAlex)
            assertReachable(home.root, home.boardSara)
        } finally {
            releaseAvatars(home.root)
        }
    }

    @Test
    fun rideKeepsVoiceStateBelowTravelerAndParkedActionsReachable() = onMainThread {
        val inflater = LayoutInflater.from(fixtureContext(Configuration.UI_MODE_NIGHT_NO))
        val panel = ActivityMainBinding.inflate(inflater)
        val ride = ScreenRideBinding.inflate(inflater, panel.screenContainer, true)
        try {
            ride.title.setText(R.string.ride_focus_title)
            ride.gearStatus.setText(R.string.hitch_parked)
            ride.traveler.setText(R.string.hitch_alex)
            ride.voiceStatus.setText(R.string.voice_listening)
            ride.pause.setText(R.string.hitch_ride_paused)
            ride.parkedControls.visibility = View.VISIBLE
            ride.recoveryActions.visibility = View.VISIBLE
            ride.retry.visibility = View.VISIBLE
            ride.cancelRide.visibility = View.VISIBLE
            layoutPanel(panel.root)
            assertTrue("The voice pill must follow the traveler avatar", ride.voiceIndicator.top >= ride.travelerAvatar.bottom)
            assertEquals(ride.travelerAvatar.width, ride.travelerAvatar.height)
            assertContainedHorizontally(ride.root, ride.voiceIndicator)
            assertReachable(ride.root, ride.retry)
            assertReachable(ride.root, ride.cancelRide)
            assertReachable(ride.root, ride.dropOff)
        } finally {
            releaseAvatars(ride.root)
        }
    }

    @Test
    fun setupLimitsWideContentAndFillsNarrowPanel() = onMainThread {
        val inflater = LayoutInflater.from(fixtureContext(Configuration.UI_MODE_NIGHT_NO))
        val panel = ActivityMainBinding.inflate(inflater)
        val setup = ScreenSetupBinding.inflate(inflater, panel.screenContainer, true)
        val column = setup.root.getChildAt(0) as ContentColumn
        try {
            layoutPanel(panel.root, width = 1682, height = 640)
            assertEquals("Wide setup form should be limited to 640 dp", 640, column.width)
            assertEquals("Wide setup form should be centered", (setup.root.width - column.width) / 2, column.left)
            assertReachable(setup.root, setup.completeSetup)

            layoutPanel(panel.root)
            assertEquals("Narrow setup form should fill the viewport", setup.root.width, column.width)
            assertEquals("The setup form should align with the viewport edge", 0, column.left)
            setup.root.scrollTo(0, 0)
            assertTrue("Setup should scroll in a narrow panel", setup.root.canScrollVertically(1))
            assertReachable(setup.root, setup.completeSetup)
        } finally {
            releaseAvatars(setup.root)
        }
    }

    private fun assertStackedTravelers(home: ScreenHomeBinding) {
        assertEquals(LinearLayout.VERTICAL, home.travelerCards.orientation)
        assertTrue("Traveler cards must not overlap", home.saraCard.top >= home.alexCard.bottom)
        assertEquals("Alex card must fill the row", home.travelerCards.width, home.alexCard.width)
        assertEquals("Sara card must fill the row", home.travelerCards.width, home.saraCard.width)
    }

    private fun assertButtonColors(button: Button, enabled: Boolean, textColor: Int, fillColor: Int, pressed: Boolean = false) {
        button.isEnabled = enabled
        button.isPressed = pressed
        button.jumpDrawablesToCurrentState()
        val background = checkNotNull(button.background)
        val previousBounds = Rect(background.bounds)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val fill = try {
            background.setBounds(0, 0, bitmap.width, bitmap.height)
            background.draw(Canvas(bitmap))
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        } finally {
            background.bounds = previousBounds
            bitmap.recycle()
        }

        val state = "${button.resources.getResourceEntryName(button.id)}, enabled=$enabled, pressed=$pressed"
        assertEquals("Button fill for $state", button.context.getColor(fillColor), fill)
        assertEquals("Button text for $state", button.context.getColor(textColor), button.currentTextColor)
        val contrast = ColorUtils.calculateContrast(button.currentTextColor, fill)
        assertTrue("Expected at least 4.5:1 text contrast for $state, found $contrast", contrast >= 4.5)
    }

    private fun layoutPanel(panel: View, width: Int = 320, height: Int = 360) {
        panel.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
    }

    private fun assertReachable(scroll: ScrollView, button: Button) {
        val label = button.resources.getResourceEntryName(button.id)
        assertEquals("Visibility of $label", View.VISIBLE, button.visibility)
        val minTouchSize = 48 * button.resources.displayMetrics.density
        assertTrue("Expected a 48 dp touch target for $label", button.width >= minTouchSize && button.height >= minTouchSize)

        scroll.scrollTo(0, 0)
        val bounds = Rect(0, 0, button.width, button.height)
        scroll.offsetDescendantRectToMyCoords(button, bounds)
        scroll.scrollTo(0, bounds.top)
        assertTrue("$label must fit across the panel", bounds.left >= 0 && bounds.right <= scroll.width)
        assertTrue(
            "$label must fit in the scrolled viewport",
            bounds.top >= scroll.scrollY && bounds.bottom <= scroll.scrollY + scroll.height,
        )
    }

    private fun assertContainedHorizontally(parent: ViewGroup, child: View) {
        val bounds = Rect(0, 0, child.width, child.height)
        parent.offsetDescendantRectToMyCoords(child, bounds)
        assertTrue("Expected positive width for ${child.resources.getResourceEntryName(child.id)}", child.width > 0)
        assertTrue(
            "${child.resources.getResourceEntryName(child.id)} must fit across the panel",
            bounds.left >= 0 && bounds.right <= parent.width,
        )
    }

    private fun fixtureContext(night: Int): ContextThemeWrapper {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            densityDpi = DisplayMetrics.DENSITY_DEFAULT
            screenWidthDp = 320
            screenHeightDp = 360
            smallestScreenWidthDp = 320
            fontScale = 1.2f
        }
        return ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.Theme_HitchTalk)
    }

    private fun releaseAvatars(view: View) {
        when (view) {
            is AvatarView -> view.release()
            is ViewGroup -> for (index in 0 until view.childCount) releaseAvatars(view.getChildAt(index))
        }
    }

    private fun onMainThread(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }
}
