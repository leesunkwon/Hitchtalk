package com.kotlinsun.hitchtalk.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import com.kotlinsun.hitchtalk.R

/** Measure against the current app panel width before placing the cards. */
class PleosColumns @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private var originals: List<LayoutParams>? = null
    private var stacked: Boolean? = null
    private val minimumWidth = context.obtainStyledAttributes(attrs, R.styleable.PleosColumns).let { attributes ->
        try {
            attributes.getDimensionPixelSize(
                R.styleable.PleosColumns_columnMinWidth,
                resources.getDimensionPixelSize(R.dimen.hitch_columns_min_width),
            )
        } finally {
            attributes.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val stack = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            MeasureSpec.getSize(widthMeasureSpec) < minimumWidth
        if (stacked != stack) {
            val columns = originals ?: (0 until childCount).map {
                LayoutParams(getChildAt(it).layoutParams as LayoutParams)
            }.also { originals = it }
            stacked = stack
            orientation = if (stack) VERTICAL else HORIZONTAL
            val gap = resources.getDimensionPixelSize(R.dimen.hitch_space)
            columns.forEachIndexed { index, original ->
                getChildAt(index).layoutParams = LayoutParams(original).apply {
                    if (stack) {
                        width = MATCH_PARENT
                        height = WRAP_CONTENT
                        weight = 0f
                        marginStart = 0
                        marginEnd = 0
                        topMargin = if (index == 0) 0 else gap
                        bottomMargin = 0
                    }
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
