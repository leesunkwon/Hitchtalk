package com.kotlinsun.hitchtalk.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView
import com.kotlinsun.hitchtalk.R
import kotlin.math.roundToInt

/** Sizes the traveler from the visible panel rather than the scrollable content. */
class RideViewport @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avatar = findViewById<View>(R.id.travelerAvatar)
        if (avatar != null && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
        ) {
            val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val height = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
            val maximum = minOf(resources.getDimensionPixelSize(R.dimen.hitch_ride_avatar_max),
                (300 * resources.displayMetrics.density).roundToInt())
            val pickingUp = findViewById<View>(R.id.pickup_cue)?.visibility == View.VISIBLE
            val heightFraction = if (pickingUp) .42f else .38f
            val side = minOf((width * .78f).roundToInt(), (height * heightFraction).roundToInt(), maximum)
                .coerceAtLeast(1)
            val params = avatar.layoutParams
            if (params.width != side || params.height != side) {
                params.width = side
                params.height = side
                avatar.layoutParams = params
            }
            findViewById<View>(R.id.parked_controls)?.let { controls ->
                val controlsWidth = minOf(width, resources.getDimensionPixelSize(R.dimen.hitch_detail_max_width))
                val controlsParams = controls.layoutParams
                if (controlsParams.width != controlsWidth) {
                    controlsParams.width = controlsWidth
                    controls.layoutParams = controlsParams
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
