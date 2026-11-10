package com.anjy.atlas

import android.content.Context

/**
 * Atlas's say over itself: the settings it may change when you ask ("speak your replies", "use a deeper voice",
 * "switch to light mode", "stop floating on my screen"). Each returns a sentence saying what changed, or why not.
 */
object Self {
    val names = listOf("speak_replies", "natural_voice", "voice", "theme", "on_screen", "hey_atlas", "reduce_motion", "alarm_sound", "your_name", "speaking_speed", "hearing", "dont_switch")

    /** For the model: what each setting is and its current value. */
    fun describe(ctx: Context): String {
        val s = Store.read(ctx) { it.settings }
        val eng = Voice.engine(ctx)
        return listOf(
            "speak_replies (on/off): read every reply aloud — now ${onOff(s.speakReplies)}",
            "natural_voice (on/off): human-sounding AI voice instead of the phone's — now ${onOff(Voice.natural(ctx))}",
            "voice (a voice name): who Atlas sounds like — now ${Voice.currentLabel(ctx)}; choices: ${Voice.choices(ctx).joinToString(", ")}" + if (eng == null) " (natural voices need a Gemini or OpenAI key)" else "",
            "theme (dark/light) — now ${Store.getString(ctx, "theme2") ?: s.theme}",
            "on_screen (on/off): Atlas floating over other apps — now ${onOff(s.avatarOn)}",
            "hey_atlas (on/off): listening for \"Hey Atlas\" — now ${onOff(s.wakeWord)}",
            "reduce_motion (on/off): keep the orb still — now ${onOff(Prefs.reduceMotion(ctx))}",
            "alarm_sound (on/off) — now ${onOff(s.sound)}",
            "your_name (text): what Atlas calls the user — now ${s.name.ifBlank { "not set" }}",
            "speaking_speed (slow/normal/fast): how fast Atlas talks — now ${Voice.speed(ctx)}",
            "hearing (auto/phone): auto = a speech model writes down what's said when there's a key for one (most accurate); phone = always the phone's own recognition — now ${Hear.mode(ctx)}, using ${Hear.describe(ctx)}",
            "dont_switch (on/off): stay on the chosen AI service instead of moving to another when it fails or runs out (other keys of the same service still take over) — now ${onOff(KeyRouter.dontSwitch(ctx))}"
        ).joinToString("\n- ", prefix = "- ")
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"
    private fun flag(v: String): Boolean? = when (v.trim().lowercase()) {
        "on", "true", "yes", "enable", "enabled", "1" -> true
        "off", "false", "no", "disable", "disabled", "0" -> false
        else -> null
    }

    fun change(ctx: Context, name: String, value: String): String {
        val v = value.trim()
        fun needFlag(): Boolean = flag(v) ?: throw AtlasError("Say on or off for $name.")
        return try {
            when (name) {
                "speak_replies" -> { val b = needFlag(); Store.edit(ctx) { it.settings.speakReplies = b }; "Replies will ${if (b) "" else "no longer "}be read aloud." }
                "natural_voice" -> { val b = needFlag(); Store.setFlag(ctx, "voice_natural", b); if (b && Voice.engine(ctx) == null) "Natural voice is on, but it needs a Gemini or OpenAI key to work." else "Natural voice ${onOff(b)}." }
                "voice" -> Voice.choose(ctx, v)
                "theme" -> {
                    val t = v.lowercase().let { if (it.contains("light")) "light" else if (it.contains("dark")) "dark" else throw AtlasError("Theme is dark or light.") }
                    Store.putString(ctx, "theme2", t); "Switched to $t mode."
                }
                "on_screen" -> {
                    val b = needFlag()
                    if (b && !android.provider.Settings.canDrawOverlays(ctx)) return "I need permission to float over other apps first: Atlas → Me → Atlas On Screen → Allow Display Over Apps."
                    Store.edit(ctx) { it.settings.avatarOn = b }
                    val wake = Store.read(ctx) { it.settings.wakeWord }
                    if (b || wake) AvatarService.refresh(ctx) else AvatarService.stop(ctx)
                    if (b) "I'm on your screen now." else "I'm off your screen."
                }
                "hey_atlas" -> {
                    val b = needFlag()
                    if (!b) { Store.edit(ctx) { it.settings.wakeWord = false }; AvatarService.refresh(ctx); "Stopped listening for Hey Atlas." }
                    else if (!HotwordFiles.ready(ctx) || ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        "Hey Atlas needs a one-time setup (microphone permission and a small download). Turn it on in Atlas → Me → Voice."
                    else { Store.edit(ctx) { it.settings.wakeWord = true }; AvatarService.refresh(ctx); "Listening for Hey Atlas. It starts once Atlas is open on screen." }
                }
                "reduce_motion" -> { val b = needFlag(); Store.setFlag(ctx, "reduceMotion", b); if (b) "The orb will hold still." else "The orb will move again." }
                "alarm_sound" -> { val b = needFlag(); Store.edit(ctx) { it.settings.sound = b }; "Alarm sound ${onOff(b)}." }
                "your_name" -> { if (v.isBlank()) throw AtlasError("Give the name."); Store.edit(ctx) { it.settings.name = v.take(40) }; "I'll call you ${v.take(40)}." }
                "dont_switch" -> { val b = needFlag(); KeyRouter.setDontSwitch(ctx, b); if (b) "I'll stay on the service you chose and won't switch to another one (your other keys for it can still take over)." else "I'll switch to another service again when one keeps failing or runs out." }
                "speaking_speed" -> "I'll speak at ${Voice.setSpeed(ctx, v)} speed."
                "hearing" -> { Hear.setMode(ctx, v); "Hearing: ${Hear.describe(ctx)}." }
                else -> "Error: I can't change \"$name\". Settings I can change: ${names.joinToString(", ")}."
            }
        } catch (e: AtlasError) { "Error: ${e.message}" }
    }

    private class AtlasError(msg: String) : Exception(msg)
}
