package com.anjy.atlas

import android.app.Application

/**
 * Runs once as any Atlas process starts, before any screen or service. It only reads this device's colour theme,
 * so screens that can open on their own in a fresh process (the alarm, focus, the assist panel) are already in the
 * chosen theme. Keep it this small: everything else starts where it always has.
 */
class AtlasApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try { Themes.load(this) } catch (_: Throwable) { }
    }
}
