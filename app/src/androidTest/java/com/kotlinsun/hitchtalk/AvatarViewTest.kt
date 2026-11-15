package com.kotlinsun.hitchtalk

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kotlinsun.hitchtalk.avatar.AvatarKind
import com.kotlinsun.hitchtalk.avatar.AvatarMood
import com.kotlinsun.hitchtalk.avatar.AvatarView
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Checks bundled rendering without reading account fields, requesting speech, or invoking live AI. */
@RunWith(AndroidJUnit4::class)
class AvatarViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Test(timeout = 45_000)
    fun suppliedAvatarsRenderOfflineUpdateAndRelease() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var avatar: AvatarView
            lateinit var browser: WebView
            lateinit var overlay: FrameLayout
            lateinit var activity: MainActivity
            scenario.onActivity { launchedActivity ->
                activity = launchedActivity
                // An opaque fixture hides existing UI and remains independent of account navigation.
                overlay = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE) }
                activity.addContentView(
                    overlay,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                avatar = AvatarView(activity)
                val side = (320 * activity.resources.displayMetrics.density).roundToInt()
                overlay.addView(avatar, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
                browser = avatar.getChildAt(0) as WebView
                assertTrue(browser.settings.javaScriptEnabled)
                assertTrue(browser.settings.blockNetworkLoads)
                assertFalse(browser.settings.allowFileAccess)
                assertFalse(browser.settings.allowContentAccess)
                assertFalse(browser.settings.domStorageEnabled)
                assertFalse(browser.settings.javaScriptCanOpenWindowsAutomatically)
                assertFalse(browser.settings.supportMultipleWindows())
                assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, browser.settings.mixedContentMode)
            }

            try {
                val definitions = listOf(
                    Triple(AvatarKind.MAIN, "steering-wheel", "Steering Wheel"),
                    Triple(AvatarKind.ALEX, "ghost", "Ghost"),
                    Triple(AvatarKind.SARA, "bunny", "Bunny"),
                )
                for ((kind, definitionId, definitionName) in definitions) {
                    onMain { avatar.update(kind, AvatarMood.IDLE, still = true) }
                    val frame = awaitFrame(browser) {
                        it.optString("avatarId") == definitionId &&
                            it.optString("svgLabel") == definitionName &&
                            it.optString("still") == "true" && it.optInt("pathCount") > 0
                    }
                    assertTrue(frame.getDouble("width") > 0)
                    assertTrue(frame.getDouble("height") > 0)
                    assertTrue(frame.getDouble("renderWidth") > 0)
                    assertTrue(frame.getDouble("renderHeight") > 0)
                    assertTrue("Static avatar must use its rendered SVG", frame.getBoolean("svg"))
                    assertFalse("Static avatar must stop the live canvas", frame.getBoolean("canvas"))
                    assertEquals("idle", frame.getString("mood"))
                    onMain {
                        assertEquals(avatar.context.getString(kind.label), avatar.contentDescription.toString())
                        assertEquals("https://hitchtalk-avatar.invalid/index.html", browser.url)
                    }
                    captureAvatar(kind, browser)
                }

                onMain { avatar.update(AvatarKind.ALEX, AvatarMood.THINKING, still = false) }
                val thinking = awaitFrame(browser) {
                    it.optString("avatarId") == "ghost" &&
                        it.optString("mood") == "thinking" &&
                        it.optString("still") == "false" && it.optInt("paintedSamples") > 0
                }
                assertTrue("Live thinking state must paint the canvas", thinking.getBoolean("canvas"))

                onMain { avatar.setActive(false) }
                awaitFrame(browser) {
                    it.optString("still") == "true" && it.optString("svgLabel") == "Ghost" &&
                        it.optBoolean("svg") && it.optInt("pathCount") > 0
                }
                onMain { avatar.update(AvatarKind.SARA, AvatarMood.HAPPY, still = false) }
                awaitFrame(browser) {
                    it.optString("avatarId") == "bunny" &&
                        it.optString("svgLabel") == "Bunny" &&
                        it.optString("mood") == "happy" && it.optString("still") == "true"
                }
                onMain { avatar.setActive(true) }
                awaitFrame(browser) {
                    it.optString("avatarId") == "bunny" &&
                        it.optString("mood") == "happy" &&
                        it.optString("still") == "false" && it.optInt("paintedSamples") > 0
                }
            } finally {
                onMain {
                    avatar.release()
                    avatar.release()
                    assertEquals("Release must remove the destroyed WebView", 0, avatar.childCount)
                    overlay.removeAllViews()
                    (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
                    // Finish before ActivityScenario.close() waits for a globally idle UI.
                    activity.finish()
                }
            }
        }
    }

    private fun awaitFrame(browser: WebView, matches: (JSONObject) -> Boolean): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        var latest = JSONObject()
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = readFrame(browser)
            assertFalse("Bundled avatar renderer reported an error", latest.optString("renderState") == "error")
            if (latest.optString("renderState") == "ready" && matches(latest)) return latest
            Thread.sleep(60)
        }
        throw AssertionError("Expected local avatar frame was not rendered. Last state $latest")
    }

    private fun captureAvatar(kind: AvatarKind, browser: WebView) {
        val drawn = CountDownLatch(1)
        onMain {
            browser.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    browser.postOnAnimation {
                        browser.postOnAnimation { drawn.countDown() }
                    }
                }
            })
        }
        assertTrue("Avatar compositor did not commit its visual state", drawn.await(3, TimeUnit.SECONDS))
        val filename = "hitchtalk-avatar-${kind.name.lowercase(Locale.ROOT)}.png"
        val output = instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/$filename")
        ParcelFileDescriptor.AutoCloseInputStream(output).use {
            it.readBytes()
        }
    }

    private fun readFrame(browser: WebView): JSONObject {
        val result = AtomicReference<String>()
        val completed = CountDownLatch(1)
        onMain {
            browser.evaluateJavascript(FRAME_STATE) {
                result.set(it)
                completed.countDown()
            }
        }
        assertTrue("Local page inspection timed out", completed.await(3, TimeUnit.SECONDS))
        val decoded = JSONTokener(result.get()).nextValue()
        return if (decoded is String) JSONObject(decoded)
        else JSONObject().put("renderState", "loading")
    }

    private fun onMain(action: () -> Unit) {
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val posted = mainHandler.post {
            try {
                action()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                completed.countDown()
            }
        }
        assertTrue("UI operation could not be posted", posted)
        assertTrue("UI operation timed out", completed.await(3, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
    }

    private companion object {
        val FRAME_STATE = """
            (() => {
              const host = document.getElementById("bot");
              if (!host) return JSON.stringify({ renderState: "loading" });
              const svg = host.querySelector("svg");
              const canvas = host.querySelector("canvas");
              const bounds = host.getBoundingClientRect();
              const renderedBounds = (svg || canvas)?.getBoundingClientRect();
              const paths = svg ? [...svg.querySelectorAll("path")].filter(path => path.getAttribute("d")) : [];
              let paintedSamples = 0;
              if (canvas && canvas.width > 0 && canvas.height > 0) {
                const pixels = canvas.getContext("2d").getImageData(0, 0, canvas.width, canvas.height).data;
                for (let alpha = 3; alpha < pixels.length; alpha += 64) {
                  if (pixels[alpha] > 0) paintedSamples++;
                }
              }
              return JSON.stringify({
                renderState: host.dataset.renderState || "loading",
                avatarId: host.dataset.avatarId || "",
                mood: host.dataset.mood || "",
                still: host.dataset.still || "",
                width: bounds.width,
                height: bounds.height,
                renderWidth: renderedBounds?.width || 0,
                renderHeight: renderedBounds?.height || 0,
                svgLabel: svg?.getAttribute("aria-label") || "",
                pathCount: paths.length,
                svg: Boolean(svg),
                canvas: Boolean(canvas),
                paintedSamples
              });
            })()
        """.trimIndent()
    }
}
