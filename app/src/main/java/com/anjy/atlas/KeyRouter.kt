package com.anjy.atlas

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One model call with the key pool behind it, shared by the chat (Conversation) and anything else that talks to a
 * model. A key that runs out (or is turned down) is rested and the next ready key of the same service takes over at
 * once; when the service has none left, [SwitchService] tells the caller to carry on with the next service that does.
 * Busy servers, and a limit with no other key to turn to, are waited out (6, 15, 30 s).
 *
 * [onStatus] gets a short line to show while waiting or switching, and null when that is over.
 */
object KeyRouter {
    /** All of a service's keys are out: carry on with [to]. */
    class SwitchService(val to: Providers.Provider, why: String) : Exception(why)

    suspend fun call(ctx: Context, p: Providers.Provider, session: Providers.Session, onStatus: (String?) -> Unit = {}, call: () -> Gemini.Turn): Gemini.Turn {
        val waits = longArrayOf(6_000, 15_000, 30_000)
        var i = 0
        while (true) {
            try {
                val t = withContext(Dispatchers.IO) { call() }
                withContext(Dispatchers.IO) { Providers.worked(ctx, p.id, session.key) }
                return t
            } catch (f: Gemini.Failure) {
                if (Providers.keyExhausted(f)) {
                    withContext(Dispatchers.IO) { Providers.rest(ctx, p.id, session.key, f.code, f.message ?: "") }
                    val next = withContext(Dispatchers.IO) { Providers.readyKey(ctx, p.id, except = session.key) }
                    if (next != null) {
                        session.key = next
                        onStatus("Switching to another ${p.name} key")
                        delay(300); onStatus(null)
                        continue
                    }
                    val other = withContext(Dispatchers.IO) { Providers.fallbacks(ctx, p.id).firstOrNull() }
                    if (other != null) throw SwitchService(other, "All your ${p.name} keys are used up for now.")
                    if (f.code != 429 && f.code != 402) throw Gemini.Failure("${f.message} (No other key is ready: add more in Me → Brain.)", code = f.code)
                }
                val again = f.code == 429 || f.code == 500 || f.code == 503 || f.code == 504
                if (!again || i >= waits.size) throw f
                onStatus(if (f.code == 429) "Waiting for ${p.name}'s limit" else "${p.name} is busy, trying again")
                delay(waits[i++])
                onStatus(null)
            }
        }
    }
}
