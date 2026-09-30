package com.tung.readloud.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.tung.readloud.R

/**
 * A row of equal segments on a rounded track, one selected. [soft] draws the selection as a raised
 * surface instead of the cream pill used for the main choice on a screen.
 */
class SegmentedControl @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    var onSelected: ((Int) -> Unit)? = null
    var selectedIndex: Int = -1
        private set

    init {
        orientation = HORIZONTAL
        setBackgroundResource(R.drawable.bg_segment_group)
        val pad = (4 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }

    fun setOptions(labels: List<String>, soft: Boolean = false) {
        removeAllViews()
        val height = (44 * resources.displayMetrics.density).toInt()
        labels.forEachIndexed { i, label ->
            addView(
                TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    maxLines = 1
                    textSize = 15f
                    typeface = ResourcesCompat.getFont(context, R.font.be_vietnam_pro_semibold)
                    setTextColor(ContextCompat.getColorStateList(context, if (soft) R.color.segment_text_soft else R.color.segment_text))
                    setBackgroundResource(if (soft) R.drawable.bg_segment_soft else R.drawable.bg_segment)
                    isClickable = true
                    isFocusable = true
                    contentDescription = label
                    setOnClickListener {
                        if (selectedIndex == i) return@setOnClickListener
                        select(i)
                        onSelected?.invoke(i)
                    }
                },
                LayoutParams(0, height, 1f),
            )
        }
    }

    /** Changes the selection without calling [onSelected]. */
    fun select(index: Int) {
        selectedIndex = index
        for (i in 0 until childCount) getChildAt(i).isSelected = i == index
    }
}
