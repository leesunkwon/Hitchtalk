package com.kotlinsun.hitchtalk.avatar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.kotlinsun.hitchtalk.R
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

enum class AvatarKind(
    val asset: String,
    @get:StringRes val label: Int,
    @get:ColorRes val color: Int,
) {
    MAIN("main.json", R.string.hitch_brand, R.color.hitch_avatar_purple),
    ALEX("alex.json", R.string.hitch_alex, R.color.hitch_avatar_purple),
    SARA("sara.json", R.string.hitch_sara, R.color.hitch_avatar_sara),
}

enum class AvatarMood { IDLE, THINKING, HAPPY }

/** Hosts the bundled official renderer. Only the three packaged JSON definitions are accepted. */
@SuppressLint("SetJavaScriptEnabled")
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val browser = WebView(context)
    private var kind = AvatarKind.MAIN
    private var mood = AvatarMood.IDLE
    private var still = true
    private var hostActive = true
    private var pageReady = false
    private var released = false
    private var lastOptions: String? = null

    init {
        val attributes = context.obtainStyledAttributes(attrs, R.styleable.AvatarView, defStyleAttr, 0)
        kind = AvatarKind.entries.getOrElse(attributes.getInt(R.styleable.AvatarView_avatarKind, 0)) { AvatarKind.MAIN }
        attributes.recycle()
        contentDescription = context.getString(kind.label)
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        isFocusable = false
        browser.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            isFocusableInTouchMode = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                domStorageEnabled = false
                databaseEnabled = false
                blockNetworkLoads = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                setGeolocationEnabled(false)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    localResource(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                override fun onPageFinished(view: WebView, url: String) {
                    if (released || url != PAGE_URL) return
                    pageReady = true
                    lastOptions = null
                    render()
                }
            }
            // Still avatars never consume a driver's touches or a parked form's focus.
            setOnTouchListener { _, _ -> still || !hostActive }
        }
        addView(browser, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        browser.loadUrl(PAGE_URL)
    }

    fun update(kind: AvatarKind, mood: AvatarMood = AvatarMood.IDLE, still: Boolean = true) {
        if (released) return
        this.kind = kind
        this.mood = mood
        this.still = still
        contentDescription = context.getString(kind.label)
        render()
    }

    fun setActive(active: Boolean) {
        if (released || hostActive == active) return
        hostActive = active
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        render()
    }

    override fun onDetachedFromWindow() {
        if (!released) {
            render(forceStill = true)
        }
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // View's base constructor may dispatch this before the WebView has been constructed.
        if (isAttachedToWindow) render(forceStill = visibility != View.VISIBLE)
    }

    private fun render(forceStill: Boolean = false) {
        if (released || !pageReady) return
        val active = hostActive && isAttachedToWindow && windowVisibility == View.VISIBLE && !forceStill
        val staticFrame = still || !active
        val definition = context.assets.open("avatar/${kind.asset}").bufferedReader().use {
            JSONObject(it.readText())
        }
        val color = String.format(Locale.ROOT, "#%06X", ContextCompat.getColor(context, kind.color) and 0xFFFFFF)
        val options = JSONObject()
            .put("definition", definition)
            .put("color", color)
            .put("eyeColor", "#151515")
            .put("mood", mood.name.lowercase(Locale.ROOT))
            .put("still", staticFrame)
            .put("reducedMotion", if (staticFrame) true else "system")
            .put("followPointer", !staticFrame)
            .put("holdGaze", staticFrame)
            .put("depth", 6)
            .toString()
        if (lastOptions != options) {
            lastOptions = options
            browser.evaluateJavascript("window.HitchTalkAvatar.update($options);", null)
        }
        // The renderer commits its final SVG on the next frame and then releases its RAF loop.
    }

    /** Releases this instance permanently when its containing screen is replaced. */
    fun release() {
        if (released) return
        released = true
        if (pageReady) browser.evaluateJavascript("window.HitchTalkAvatar.destroy();", null)
        browser.stopLoading()
        removeView(browser)
        browser.removeAllViews()
        browser.destroy()
    }

    private fun localResource(uri: Uri): WebResourceResponse {
        val file = uri.path?.removePrefix("/")
        if (uri.scheme != "https" || uri.host != HOST || file !in allowedFiles) {
            return WebResourceResponse(
                "text/plain", "UTF-8", 403, "Blocked", emptyMap(),
                ByteArrayInputStream(ByteArray(0)),
            )
        }
        val mime = if (file == "index.html") "text/html" else "application/javascript"
        return WebResourceResponse(mime, "UTF-8", context.assets.open("avatar/$file"))
    }

    private companion object {
        const val HOST = "hitchtalk-avatar.invalid"
        const val PAGE_URL = "https://$HOST/index.html"
        val allowedFiles = setOf("index.html", "avatar-runtime.js", "avatar-host.js")
    }
}
