package com.tung.readloud.ui

import android.widget.TextView
import com.google.android.material.progressindicator.BaseProgressIndicator

/*
 * Ticking views are refreshed every half second; setting the same value again still relayouts the view,
 * which costs battery and keeps the window from ever going idle.
 */

fun TextView.setTextIfChanged(value: CharSequence?) {
    val next = value ?: ""
    if (text.toString() == next.toString() && next !is android.text.Spanned) return
    if (next is android.text.Spanned && text is android.text.Spanned && text.toString() == next.toString() && sameSpans(text as android.text.Spanned, next)) return
    text = next
}

private fun sameSpans(a: android.text.Spanned, b: android.text.Spanned): Boolean {
    val sa = a.getSpans(0, a.length, Any::class.java).map { a.getSpanStart(it) to a.getSpanEnd(it) }
    val sb = b.getSpans(0, b.length, Any::class.java).map { b.getSpanStart(it) to b.getSpanEnd(it) }
    return sa == sb
}

fun BaseProgressIndicator<*>.setProgressIfChanged(value: Int) {
    if (progress != value) setProgressCompat(value, false)
}
