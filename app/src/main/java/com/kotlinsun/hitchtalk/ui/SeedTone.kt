package com.kotlinsun.hitchtalk.ui

import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.kotlinsun.hitchtalk.R

/** Shared SEED badge and callout tones for app state. */
internal enum class SeedTone(
    @ColorRes val foreground: Int,
    @DrawableRes val badge: Int,
    @DrawableRes val callout: Int,
) {
    NEUTRAL(R.color.hitch_muted, R.drawable.hitch_badge_neutral, R.drawable.hitch_callout_neutral),
    BRAND(R.color.hitch_accent, R.drawable.hitch_badge_brand, R.drawable.hitch_callout_neutral),
    POSITIVE(R.color.hitch_green, R.drawable.hitch_badge_positive, R.drawable.hitch_callout_positive),
    INFORMATIVE(R.color.hitch_informative, R.drawable.hitch_badge_informative, R.drawable.hitch_callout_informative),
    WARNING(R.color.hitch_warning, R.drawable.hitch_badge_warning, R.drawable.hitch_callout_warning),
    CRITICAL(R.color.hitch_danger, R.drawable.hitch_badge_critical, R.drawable.hitch_callout_critical),
}

internal fun TextView.seedBadge(tone: SeedTone) = setSeedTone(tone, tone.badge)

internal fun TextView.seedCallout(tone: SeedTone) = setSeedTone(tone, tone.callout)

private fun TextView.setSeedTone(tone: SeedTone, @DrawableRes surface: Int) {
    setBackgroundResource(surface)
    setTextColor(ContextCompat.getColor(context, tone.foreground))
}
