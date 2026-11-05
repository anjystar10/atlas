package com.anjy.atlas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Tab bar icons from Phosphor (MIT): the light outline for a tab you're not on, the filled shape for the one
 * you are, tinted to whatever colour the bar asks for.
 */
class NavIcon(context: Context, kind: Int) : View(context) {
    // Atlas's tabs: Atlas (the spark), Log, Me. Log has only Phosphor's light outline here, used for both states.
    private val ids = when (kind) {
        0 -> R.drawable.ic_spark_off to R.drawable.ic_spark_on
        1 -> R.drawable.ic_book to R.drawable.ic_book
        else -> R.drawable.nav_me_off to R.drawable.nav_me_on
    }
    private val off: Drawable = context.getDrawable(ids.first)!!.mutate()
    private val on: Drawable = context.getDrawable(ids.second)!!.mutate()
    var color = Color.WHITE; set(v) { field = v; invalidate() }
    /** Filled (the current tab) or outlined. */
    var filled = false; set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val d = if (filled) on else off
        val s = minOf(width, height)
        d.setBounds((width - s) / 2, (height - s) / 2, (width + s) / 2, (height + s) / 2)
        d.setTint(color)
        d.draw(c)
    }
}
