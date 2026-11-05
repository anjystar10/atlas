package com.anjy.atlas

import android.content.Context

/** Stoa's display preferences, as its UI code expects them. */
object Prefs {
    fun haptics(c: Context) = !Store.getFlag(c, "noHaptics")
    fun reduceMotion(c: Context) = Store.getFlag(c, "reduceMotion")
}
