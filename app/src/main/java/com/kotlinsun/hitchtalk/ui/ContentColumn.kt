package com.kotlinsun.hitchtalk.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import com.kotlinsun.hitchtalk.R

/** A readable content column that grows to the app panel's available width, up to its limit. */
class ContentColumn @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val maxWidth = context.obtainStyledAttributes(attrs, R.styleable.ContentColumn).let { attributes ->
        try {
            attributes.getDimensionPixelSize(R.styleable.ContentColumn_contentMaxWidth, 0)
        } finally {
            attributes.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val width = if (maxWidth > 0 && mode == MeasureSpec.AT_MOST) {
            MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(widthMeasureSpec), maxWidth), MeasureSpec.EXACTLY)
        } else widthMeasureSpec
        super.onMeasure(width, heightMeasureSpec)
    }
}
