package com.anjy.atlas

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One model call with the key pool behind it, shared by the chat (Conversation) and anything else that talks to a
 * model (Atlas Bot).
 *
 * - Persistent first: a failure that can pass (a 429 limit, a busy server 500/502/503/504, a timeout or dropped
 *   connection) is retried on the SAME key up to [RETRIES] times before anything switches. A limit waits as long as
 *   the service asked (Gemini's retryDelay, a "try again in 20s", a Retry-After header) when that's a minute or
 *   less; with no hint, 5, 15 and 40 s, which covers a per-minute window. Busy servers and the network: 2, 5, 10 s.
 * - A failure that retrying can't fix switches at once: the key was turned down (401/403, or a 400 about the key) or
 *   the account has no credit (402). Waiting won't change those answers.
 * - After that, the key is rested (a limit, no credit) or set aside (turned down) and the next ready key of the same
 *   service takes over. When the service has none left, [SwitchService] tells the caller to carry on with the next
 *   service that does, unless "Don't Switch Services" is on: then Atlas stays on this service, waits out a used-up
 *   limit (30 s, 60 s) and, if it still can't, says so plainly.
 * - No internet after the retries: the failure goes back to the caller (which may answer with the offline model).
 *
 * [onStatus] gets a short line to show while waiting or switching, and null when that is over.
 */
object KeyRouter {
    /** All of a service's keys are out: carry on with [to]. */
    class SwitchService(val to: Providers.Provider, why: String) : Exception(why)

    const val RETRIES = 3
    val RETRY_WAITS = longArrayOf(2_000, 5_000, 10_000)
    /** A used-up limit (429) with no hint from the service: together they cover a per-minute window. */
    val LIMIT_WAITS = longArrayOf(5_000, 15_000, 40_000)
    /** The longest wait a service may ask for that Atlas honours; a longer one moves on to the next key. */
    const val MAX_HINT_MS = 60_000L

    /**
     * How long the service asked us to wait, in ms (0 if it didn't say): a Retry-After header in seconds, Gemini's
     * google.rpc.RetryInfo "retryDelay": "37s", or words like "try again in 20s", "Please retry in 1.5 seconds",
     * "retry after 20 seconds", "try again in 1m30s" or "in 500ms".
     */
    fun retryHint(body: String?, header: String?): Long {
        header?.trim()?.toDoubleOrNull()?.let { if (it > 0) return (it * 1000).toLong() }
        val b = body ?: return 0
        Regex("\"retryDelay\"\\s*:\\s*\"([0-9.]+)s\"").find(b)?.let { m -> m.groupValues[1].toDoubleOrNull()?.let { return (it * 1000).toLong() } }
        for (m in HINT.findAll(b)) {
            val mins = m.groupValues[1].toLongOrNull() ?: 0
            val n = m.groupValues[2].toDoubleOrNull()
            val unit = m.groupValues[3].lowercase()
            val partMs = when {
                n == null -> 0.0
                unit.startsWith("ms") || unit.startsWith("milli") -> n
                unit.isNotEmpty() || mins > 0 -> n * 1000   // "20s", or "1m30" -> 30 s
                else -> continue                            // a bare number: not sure it's a time
            }
            val ms = mins * 60_000 + partMs.toLong()
            if (ms > 0) return ms
        }
        return 0
    }
    private val HINT = Regex("(?:try again|retry)(?: after| in)?\\s+(?:(\\d+)\\s*m(?!s)(?:in(?:ute)?s?)?\\s*)?([0-9]+(?:\\.[0-9]+)?)?\\s*(ms|milliseconds?|s|secs?|seconds?)?\\b", RegexOption.IGNORE_CASE)

    /** How long to wait before retry number [n] (1-based), and whether that's the service's own ask. */
    fun waitFor(f: Gemini.Failure, n: Int): Pair<Long, Boolean> {
        val hint = f.retryAfterMs
        if (hint in 1..MAX_HINT_MS && (f.code == 429 || f.code == 503)) return maxOf(hint, 500L) to true
        return (if (f.code == 429) LIMIT_WAITS else RETRY_WAITS)[n - 1] to false
    }
    /** A used-up limit with nowhere else to go (no other key, and no other service or Don't Switch is on). */
    val WAIT_OUT = longArrayOf(30_000, 60_000)
    const val DONT_SWITCH = "dont_switch"

    fun dontSwitch(ctx: Context) = Store.getFlag(ctx, DONT_SWITCH)
    fun setDontSwitch(ctx: Context, on: Boolean) = Store.setFlag(ctx, DONT_SWITCH, on)

    enum class Cause { PASSING, KEY, OTHER }

    /** Whether retrying might help (PASSING), the key itself can't go on (KEY), or neither (OTHER: give up). */
    fun cause(code: Int, offline: Boolean, message: String): Cause = when {
        code == 401 || code == 403 || code == 402 || (code == 400 && message.contains("key", ignoreCase = true)) -> Cause.KEY
        code == 429 || code == 500 || code == 502 || code == 503 || code == 504 || offline -> Cause.PASSING
        else -> Cause.OTHER
    }

    /** What the router needs from the key pool (the real one is [Providers]; tests use a fake). */
    interface Pool {
        fun readyKey(id: String, except: String): String?
        fun rest(id: String, key: String, code: Int, problem: String)
        fun worked(id: String, key: String)
        fun fallback(except: String): Providers.Provider?
        val dontSwitch: Boolean
    }

    private class RealPool(val ctx: Context) : Pool {
        override fun readyKey(id: String, except: String) = Providers.readyKey(ctx, id, except = except)
        override fun rest(id: String, key: String, code: Int, problem: String) = Providers.rest(ctx, id, key, code, problem)
        override fun worked(id: String, key: String) = Providers.worked(ctx, id, key)
        override fun fallback(except: String) = Providers.fallbacks(ctx, except).firstOrNull()
        override val dontSwitch get() = KeyRouter.dontSwitch(ctx)
    }

    suspend fun call(ctx: Context, p: Providers.Provider, session: Providers.Session, onStatus: (String?) -> Unit = {}, call: () -> Gemini.Turn): Gemini.Turn =
        run(RealPool(ctx), p, { session.key }, { session.key = it }, onStatus, { delay(it) }) { withContext(Dispatchers.IO) { call() } }

    /** The rules themselves, free of Android so they can be tested. */
    suspend fun <T> run(pool: Pool, p: Providers.Provider, key: () -> String, setKey: (String) -> Unit, onStatus: (String?) -> Unit,
                        sleep: suspend (Long) -> Unit, call: suspend () -> T): T {
        var retries = 0
        var waitedOut = 0
        val rested = mutableSetOf<String>()
        while (true) {
            try {
                val t = call()
                withContext(Dispatchers.IO) { pool.worked(p.id, key()) }
                return t
            } catch (f: Gemini.Failure) {
                val msg = f.message ?: ""
                val c = cause(f.code, f.offline, msg)
                if (c == Cause.OTHER) throw f
                // a hint longer than a minute: not worth sitting through, move on to the next key
                val tooLong = f.retryAfterMs > MAX_HINT_MS
                if (c == Cause.PASSING && retries < RETRIES && !tooLong) {
                    retries++
                    val (ms, asked) = waitFor(f, retries)
                    val secs = (ms + 999) / 1000
                    onStatus(when {
                        asked -> "${p.name} asked me to wait $secs s ($retries of $RETRIES)"
                        f.code == 429 -> "Waiting for ${p.name}'s limit, $secs s ($retries of $RETRIES)"
                        f.offline -> "Can't reach ${p.name}, trying again ($retries of $RETRIES)"
                        else -> "${p.name} is busy, trying again ($retries of $RETRIES)"
                    })
                    sleep(ms)
                    onStatus(null)
                    continue
                }
                // no internet even after the retries: the caller decides (the phone may answer offline)
                if (f.offline) throw f
                // this key can't go on: rest it (limit, credit) or set it aside (turned down), and try the next key
                if (c == Cause.KEY || f.code == 429) {
                    val k = key()
                    if (rested.add(k)) withContext(Dispatchers.IO) { pool.rest(p.id, k, f.code, msg) }
                    val next = withContext(Dispatchers.IO) { pool.readyKey(p.id, except = k) }
                    if (next != null && next !in rested) {
                        setKey(next); retries = 0
                        onStatus("Switching to another ${p.name} key")
                        sleep(300); onStatus(null)
                        continue
                    }
                }
                val other = withContext(Dispatchers.IO) { pool.fallback(p.id) }
                val stay = pool.dontSwitch
                if (other != null && !stay) throw SwitchService(other, when {
                    c == Cause.KEY || f.code == 429 -> "All your ${p.name} keys are used up for now."
                    else -> "${p.name} kept failing (tried $RETRIES times)."
                })
                if (f.code == 429 && waitedOut < WAIT_OUT.size) {
                    onStatus("Waiting for ${p.name}'s limit to reset")
                    sleep(WAIT_OUT[waitedOut++])
                    onStatus(null)
                    continue
                }
                throw Gemini.Failure(giveUp(p, f, c, stay && other != null), code = f.code)
            }
        }
    }

    private fun giveUp(p: Providers.Provider, f: Gemini.Failure, c: Cause, stayed: Boolean): String {
        val base = f.message ?: "${p.name} failed."
        return when {
            stayed && f.code == 429 -> "${p.name}'s limit is used up for now. Don't Switch Services is on, so I stayed on ${p.name}: try again in a few minutes, or add another ${p.name} key in Me → Brain."
            stayed && c == Cause.KEY -> "$base Don't Switch Services is on, so I didn't move to another service. Add another ${p.name} key in Me → Brain."
            stayed -> "$base I tried $RETRIES times. Don't Switch Services is on, so I didn't move to another service."
            c == Cause.KEY || f.code == 429 -> "$base (No other key is ready: add more in Me → Brain.)"
            else -> "$base I tried $RETRIES times."
        }
    }
}
