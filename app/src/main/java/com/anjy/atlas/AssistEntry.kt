package com.anjy.atlas

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Where the assist gesture (or the assistant button) lands when Atlas is the phone's assist app: the mini panel opens
 * over the app in front and listens, and this screen closes at once without showing anything. When Atlas isn't on
 * screen (its service isn't running) or may not draw over other apps, the small sheet ([AssistActivity]) opens
 * instead. The full app never opens from here.
 */
class AssistEntry : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sent = AvatarService.running && AvatarService.canShow(this) && try {
            startService(Intent(this, AvatarService::class.java).setAction(AvatarService.ACTION_WAKE)); true
        } catch (e: Exception) { Log.w("AtlasAssist", "couldn't reach Atlas on screen", e); false }
        if (!sent) try { startActivity(Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("listen", true)) } catch (e: Exception) { Log.w("AtlasAssist", "sheet", e) }
        finish()
        overridePendingTransition(0, 0)
    }
}
