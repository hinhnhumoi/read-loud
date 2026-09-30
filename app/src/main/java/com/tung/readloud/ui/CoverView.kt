package com.tung.readloud.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.tung.readloud.R

/**
 * A text cover for a novel: initials on a colour picked from the title, or with [showTitle] the whole
 * title over an accent rule, as on the "continue" card.
 */
class CoverView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var title: String = ""
        set(value) {
            if (field == value) return
            field = value
            layout = null
            invalidate()
        }

    var showTitle: Boolean = false
        set(value) {
            field = value
            layout = null
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val rect = RectF()
    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.rl_accent) }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.literata_semibold)
    }
    private var layout: StaticLayout? = null

    override fun onDraw(canvas: Canvas) {
        val (bg, fg) = palette[Math.floorMod(title.hashCode(), palette.size)]
        background.color = bg
        text.color = fg
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val radius = if (showTitle) 16 * density else 12 * density
        canvas.drawRoundRect(rect, radius, radius, background)
        if (showTitle) drawTitle(canvas) else drawInitials(canvas)
    }

    private fun drawInitials(canvas: Canvas) {
        text.textSize = minOf(width, height) * 0.36f
        text.textAlign = Paint.Align.CENTER
        val y = height / 2f - (text.descent() + text.ascent()) / 2
        canvas.drawText(initials(title), width / 2f, y, text)
    }

    private fun drawTitle(canvas: Canvas) {
        val pad = 12 * density
        text.textSize = 16 * resources.displayMetrics.scaledDensity
        text.textAlign = Paint.Align.LEFT
        val inner = (width - 2 * pad).toInt().coerceAtLeast(1)
        val staticLayout = layout ?: StaticLayout.Builder.obtain(title, 0, title.length, text, inner)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(4)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 0.95f)
            .build()
            .also { layout = it }
        canvas.save()
        canvas.translate(pad, pad)
        staticLayout.draw(canvas)
        canvas.restore()
        val ruleY = height - pad - 2 * density
        canvas.drawRoundRect(pad, ruleY, pad + 22 * density, ruleY + 3 * density, density, density, accent)
    }

    companion object {
        /** Muted backgrounds with a light ink of the same hue, readable on the dark theme. */
        private val palette = listOf(
            0xFF3A2A1C.toInt() to 0xFFF0C080.toInt(),
            0xFF1F2B36.toInt() to 0xFFA9C8E8.toInt(),
            0xFF283122.toInt() to 0xFFCFE3B0.toInt(),
            0xFF2E2439.toInt() to 0xFFD9C6F2.toInt(),
            0xFF36221F.toInt() to 0xFFF2B8A8.toInt(),
            0xFF1E302D.toInt() to 0xFFA8DDD2.toInt(),
        )

        /** "Toàn Chức Cao Thủ" → "TC"; one word gives its first two letters. */
        fun initials(title: String): String {
            val words = title.split(Regex("[\\s\\p{Punct}]+")).filter { w -> w.any(Char::isLetterOrDigit) }
            return when {
                words.isEmpty() -> "?"
                words.size == 1 -> words[0].filter(Char::isLetterOrDigit).take(2)
                else -> "${words[0].first { it.isLetterOrDigit() }}${words[1].first { it.isLetterOrDigit() }}"
            }.uppercase()
        }
    }
}
