package com.kotlinsun.hitchtalk.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout
import com.kotlinsun.hitchtalk.R

/** Keeps the settings sidebar beside its content, with scrollable tabs in narrow panels. */
class SettingsPanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private var stacked: Boolean? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val narrow = MeasureSpec.getSize(widthMeasureSpec) < (720 * resources.displayMetrics.density).toInt()
        if (stacked != narrow) {
            stacked = narrow
            orientation = if (narrow) VERTICAL else HORIZONTAL
            val gap = resources.getDimensionPixelSize(R.dimen.hitch_gutter)
            getChildAt(0).layoutParams = LayoutParams(
                if (narrow) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                if (narrow) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                if (narrow) 0f else 1f,
            ).apply {
                if (narrow) bottomMargin = gap else marginEnd = gap
            }
            getChildAt(1).layoutParams = LayoutParams(
                if (narrow) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                if (narrow) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                if (narrow) 1f else 3f,
            )
            findViewById<LinearLayout>(R.id.settings_navigation).apply {
                orientation = if (narrow) HORIZONTAL else VERTICAL
                layoutParams = layoutParams.apply {
                    width = if (narrow) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
                }
                for (index in 0 until childCount) {
                    getChildAt(index).layoutParams = LayoutParams(
                        if (narrow) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (narrow) marginEnd = gap / 2 else bottomMargin = gap / 2
                    }
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
