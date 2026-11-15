package com.kotlinsun.hitchtalk

import android.view.View
import android.widget.CompoundButton
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.auth.FirebaseAuth
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthRegistrationTest {
    @Test
    fun registrationFieldsAndValidationKeepIncompleteSignupLocal() {
        assumeTrue("Keep the existing signed-in account intact", FirebaseAuth.getInstance().currentUser == null)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val simulation = activity.findViewById<CompoundButton>(R.id.simulation)
                if (!simulation.isShown) activity.findViewById<View>(R.id.debug_toggle).performClick()
                if (!simulation.isChecked) simulation.performClick()
            }
            onView(withId(R.id.login)).perform(scrollTo(), click())
            onView(withId(R.id.modeTitle)).check(matches(withText(R.string.auth_login)))
            onView(withId(R.id.registrationFields))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))
            pressBack()
            onView(withId(R.id.start)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.start)).perform(click())
            onView(withId(R.id.registrationFields))
                .check(matches(withEffectiveVisibility(Visibility.VISIBLE)))

            for (field in listOf(R.id.email, R.id.name, R.id.birthDate, R.id.gender, R.id.password, R.id.passwordConfirm)) {
                onView(withId(field)).perform(scrollTo()).check(matches(isDisplayed()))
            }

            onView(withId(R.id.email)).perform(scrollTo(), replaceText("hitchtalk-probe@example.invalid"), closeSoftKeyboard())
            onView(withId(R.id.submit)).perform(scrollTo(), click())
            onView(withId(R.id.status)).check(matches(withText(R.string.auth_required_fields)))

            onView(withId(R.id.password)).perform(scrollTo(), replaceText("synthetic-password-a"), closeSoftKeyboard())
            onView(withId(R.id.passwordConfirm)).perform(scrollTo(), replaceText("synthetic-password-b"), closeSoftKeyboard())
            onView(withId(R.id.submit)).perform(scrollTo(), click())
            onView(withId(R.id.status)).check(matches(withText(R.string.auth_consent_required)))

            onView(withId(R.id.consent)).perform(scrollTo(), click())
            onView(withId(R.id.submit)).perform(scrollTo(), click())
            onView(withId(R.id.status)).check(matches(withText(R.string.auth_password_mismatch)))

            // Other required fields remain empty, so this check also cannot create an account.
            onView(withId(R.id.passwordConfirm)).perform(scrollTo(), replaceText("synthetic-password-a"), closeSoftKeyboard())
            onView(withId(R.id.submit)).perform(scrollTo(), click())
            onView(withId(R.id.status)).check(matches(withText(R.string.auth_required_fields)))
            onView(withId(R.id.progress)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            assertNull(FirebaseAuth.getInstance().currentUser)
        }
    }
}
