package dev.bubbleclaw

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** ScrollView that clamps its wrap-content height to a runtime maximum. */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    var maxHeight: Int = Int.MAX_VALUE
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeight != Int.MAX_VALUE && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            val limited = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, limited)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
