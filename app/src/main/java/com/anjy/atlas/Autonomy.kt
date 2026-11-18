package com.anjy.atlas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Atlas autonomy on the phone: the one place that decides whether an action just happens, happens with 10 seconds to
 * Undo, waits for the user's OK, or never happens. The rules are [AutonomyRules] (the same as the PC's autonomy.js);
 * this keeps the state and does the notifications. The UIs (chat, bots, Me) only call this.
 *
 * State: level, per-bot levels, grants, known recipients and "already offered" kinds live in the bot policy
 * (BotExtras.policy), so they sync with the PC. Approval counts and the "done without asking" list stay on this phone.
 *
 * Who may change it: the user's tap (Me → Permissions, a notification button) or their own message (Situation.consent).
 * Nothing a page, email, file or bot says can approve anything or make Atlas ask less.
 */
object Autonomy {
    private const val TAG = "AtlasAutonomy"
    private const val PREFS = "autonomy"
    const val CH = "atlas_autonomy"
    const val ACTION_UNDO = "com.anjy.atlas.autonomy.UNDO"
    const val ACTION_ALWAYS = "com.anjy.atlas.autonomy.ALWAYS"
    const val ACTION_KEEP_ASKING = "com.anjy.atlas.autonomy.KEEP_ASKING"
    const val ACTION_APPROVE_ALL = "com.anjy.atlas.autonomy.APPROVE_ALL"
    const val EXTRA_NOTIF = "notif"; const val EXTRA_KIND = "kind"
    private const val BATCH_ID = 5099
    private val undone = ConcurrentHashMap<Int, Boolean>()
    @Volatile private var channelReady = false
    private var nextId = 0

    // ---------------------------------------------------------------- the policy

    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() }
    private fun map(o: JSONObject?): Map<String, String> = o?.keys()?.asSequence()?.associateWith { o.optString(it) } ?: emptyMap()
    private fun nul(o: JSONObject, k: String): String? = if (!o.has(k) || o.isNull(k)) null else o.optString(k)
    private fun grantOf(g: JSONObject) = AutonomyRules.Grant(g.optString("kind"), g.optString("scope"), nul(g, "botId"), nul(g, "missionId"), nul(g, "day"), g.optLong("at"))
    private fun grantJson(g: AutonomyRules.Grant): JSONObject = JSONObject().put("kind", g.kind).put("scope", g.scope).put("at", g.at).also { o ->
        g.day?.let { o.put("day", it) }; g.botId?.let { o.put("botId", it) }; g.missionId?.let { o.put("missionId", it) }
    }
    fun policyOf(p: JSONObject): AutonomyRules.Policy = AutonomyRules.Policy(nul(p, "level"), map(p.optJSONObject("botLevels")),
        p.optJSONArray("grants")?.let { a -> List(a.length()) { a.optJSONObject(it) }.filterNotNull().map { grantOf(it) } } ?: emptyList(),
        map(p.optJSONObject("modes")), strs(p.optJSONArray("recipients")), strs(p.optJSONArray("suggested")))
    fun policy(ctx: Context): AutonomyRules.Policy { BotExtras.init(ctx); return policyOf(BotExtras.policySnapshot()) }

    fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    fun level(ctx: Context) = AutonomyRules.levelOf(policy(ctx))

    /** null when done; otherwise why not. [byTap]: from Me; otherwise the user's own latest message must say yes to ask less. */
    fun setLevel(ctx: Context, level: String, byTap: Boolean): String? {
        val (ok, why) = AutonomyRules.canChangeLevel(level(ctx), level, byTap, consentYes = !byTap && Situation.consent().yes)
        if (!ok) return why
        BotExtras.editPolicy { it.put("level", level) }
        return null
    }
    /** A bot's own level (null: same as Atlas). It can only make that bot more careful than Atlas. */
    fun setBotLevel(ctx: Context, botId: String, level: String?, byTap: Boolean): String? {
        val now = AutonomyRules.effectiveLevel(policy(ctx), botId)
        if (level != null && level !in AutonomyRules.LEVELS) return "unknown level"
        if (!byTap && (level == null || AutonomyRules.LEVELS.indexOf(level) > AutonomyRules.LEVELS.indexOf(now)) && !Situation.consent().yes) return "only the user's own words can make a bot ask less"
        BotExtras.editPolicy { p -> val m = p.optJSONObject("botLevels") ?: JSONObject().also { p.put("botLevels", it) }; if (level == null) m.remove(botId) else m.put(botId, level) }
        return null
    }

    fun grants(ctx: Context): List<AutonomyRules.Grant> = policy(ctx).grants
    /** Makes a grant (null when done, else why not). Callers have already checked it's the user's own tap or words. */
    fun grant(ctx: Context, kind: String, scope: String, botId: String? = null, missionId: String? = null): String? {
        val (g, err) = AutonomyRules.makeGrant(kind, scope, botId, missionId, today(), System.currentTimeMillis())
        if (g == null) return err
        BotExtras.editPolicy { p ->
            val now = policyOf(p).grants
            p.put("grants", JSONArray(AutonomyRules.addGrant(now, g, today()).map { grantJson(it) }))
        }
        return null
    }
    fun revoke(ctx: Context, index: Int) {
        BotExtras.init(ctx)
        BotExtras.editPolicy { p -> val a = p.optJSONArray("grants") ?: return@editPolicy; if (index in 0 until a.length()) a.remove(index) }
    }

    // ---------------------------------------------------------------- recipients ("new people" always ask)

    fun known(ctx: Context): List<String> = policy(ctx).recipients
    /** After the user OK'd sending to these people (or Atlas sent with their OK): they're no longer "new". */
    fun rememberRecipients(ctx: Context, to: String?) {
        val add = AutonomyRules.recipientsIn(to); if (add.isEmpty()) return
        BotExtras.init(ctx)
        BotExtras.editPolicy { p -> val list = (strs(p.optJSONArray("recipients")) + add).distinct().takeLast(500); p.put("recipients", JSONArray(list)) }
    }

    // ---------------------------------------------------------------- kinds

    /** The kind of an action (AutonomyRules.kindFor) with this phone's knowledge filled in. */
    fun kindFor(ctx: Context, tool: String?, args: JSONObject?, category: String? = null, what: String = "", money: Boolean = false, live: Boolean = false): String {
        val a = args ?: JSONObject()
        val m = a.keys().asSequence().associateWith { a.optString(it) }
        val to = if (tool == "email_send") try { Email.draftTo(a.optString("draft_id")) } catch (_: Exception) { null } else null
        val reply = tool == "email_send" && try { Email.draftIsReply(a.optString("draft_id")) } catch (_: Exception) { false }
        return AutonomyRules.kindFor(tool, m, category, AutonomyRules.Ctx(known(ctx), reply, to, live, money, what))
    }

    fun decide(ctx: Context, kind: String, mustAsk: Boolean = false, botId: String? = null, missionId: String? = null): AutonomyRules.Decision =
        AutonomyRules.decide(policy(ctx), kind, mustAsk, botId, missionId, today())

    // ---------------------------------------------------------------- after an approval

    /** The user approved this kind (a tap or their own yes): count it, and offer "Always allow this?" once after 3. */
    fun approved(ctx: Context, kind: String?) {
        val k = kind ?: return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val counts = try { JSONObject(sp.getString("counts", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val (n, offer) = AutonomyRules.afterApproval(counts.keys().asSequence().associateWith { counts.optInt(it) }, policy(ctx).suggested, k)
        sp.edit().putString("counts", counts.put(k, n).toString()).apply()
        if (offer) {
            BotExtras.editPolicy { p -> p.put("suggested", JSONArray((strs(p.optJSONArray("suggested")) + k).distinct())) }
            offerAlways(ctx, k, n)
        }
    }

    private fun offerAlways(ctx: Context, kind: String, n: Int) {
        if (!notificationsOn(ctx)) return
        val id = 5000 + (kind.hashCode() and 0x3F)
        val b = Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle("Stop asking about this?")
            .setContentText("You've said yes to “${AutonomyRules.kindLabel(kind)}” $n times. Always allow it?")
            .setStyle(Notification.BigTextStyle().bigText("You've said yes to “${AutonomyRules.kindLabel(kind)}” $n times. Always allow it? It'll still give you 10 seconds to Undo, and you can change this in Me → Permissions."))
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Always Allow", act(ctx, ACTION_ALWAYS, id, kind)).build())
            .addAction(Notification.Action.Builder(null, "Keep Asking", act(ctx, ACTION_KEEP_ASKING, id, kind)).build())
        post(ctx, id, b)
    }

    // ---------------------------------------------------------------- doing it, with Undo

    private fun notificationsOn(ctx: Context) = try { ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled() } catch (_: Exception) { false }
    private fun channel(ctx: Context) {
        if (channelReady) return; channelReady = true
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Atlas acting on its own", NotificationManager.IMPORTANCE_HIGH).apply { description = "A few seconds to Undo before Atlas does something, and offers to stop asking" })
    }
    private fun post(ctx: Context, id: Int, b: Notification.Builder) {
        channel(ctx)
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, b.build()) } catch (e: SecurityException) { Log.w(TAG, "notify", e) }
    }
    private fun act(ctx: Context, action: String, id: Int, kind: String? = null): PendingIntent =
        PendingIntent.getBroadcast(ctx, id * 4 + (action.hashCode() and 3), Intent(ctx, AutonomyReceiver::class.java).setAction(action).putExtra(EXTRA_NOTIF, id).putExtra(EXTRA_KIND, kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /**
     * Shows "Doing it in 10 seconds: [what] · Undo" and waits. true: go ahead; false: the user tapped Undo.
     * null: there's no way to show an Undo (notifications are off), so the caller asks instead.
     */
    suspend fun countdown(ctx: Context, what: String): Boolean? {
        if (!notificationsOn(ctx)) return null
        val id = 5200 + (nextId++ and 0x3F)
        undone.remove(id)
        val b = Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle("Atlas is about to do this")
            .setContentText(Plain.reply(what).take(200)).setStyle(Notification.BigTextStyle().bigText(Plain.reply(what).take(600) + "\n\nIn 10 seconds. Tap Undo to stop it."))
            .setUsesChronometer(true).setChronometerCountDown(true).setWhen(System.currentTimeMillis() + AutonomyRules.UNDO_MS).setTimeoutAfter(AutonomyRules.UNDO_MS + 500)
            .addAction(Notification.Action.Builder(null, "Undo", act(ctx, ACTION_UNDO, id)).build())
        post(ctx, id, b)
        var waited = 0L
        while (waited < AutonomyRules.UNDO_MS) { if (undone[id] == true) break; delay(250); waited += 250 }
        try { ctx.getSystemService(NotificationManager::class.java).cancel(id) } catch (_: Exception) {}
        return undone.remove(id) != true
    }
    fun undo(id: Int) { undone[id] = true }

    /** "Done without asking": the last few things Atlas did on its own (Me → Permissions shows them). */
    fun noteDone(ctx: Context, what: String, kind: String) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = try { JSONArray(sp.getString("recent", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        a.put(JSONObject().put("at", System.currentTimeMillis()).put("what", what.take(160)).put("kind", kind))
        while (a.length() > 40) a.remove(0)
        sp.edit().putString("recent", a.toString()).apply()
    }
    fun recent(ctx: Context): List<JSONObject> = try { JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("recent", "[]") ?: "[]").let { a -> List(a.length()) { a.getJSONObject(it) } } } catch (_: Exception) { emptyList() }

    /**
     * The chat's gate: decides, and does the countdown when it's "undo". Returns null when the action should go ahead
     * now, or the result to give the model instead ([ask]: how this caller asks, e.g. ChatAsk).
     */
    suspend fun chatGate(ctx: Context, kind: String, what: String, mustAsk: Boolean = false, ask: () -> Tools.Result): Tools.Result? {
        val d = decide(ctx, kind, mustAsk)
        return when (d.act) {
            "go" -> { if (d.tier != "free") noteDone(ctx, what, kind); null }
            "deny" -> Tools.Result("Error: the user's standing choice is never to allow this kind of action (${what.take(80)}). Tell them; they can change it in Me → Permissions.")
            "undo" -> when (countdown(ctx, what)) {
                true -> { noteDone(ctx, what, kind); null }
                false -> Tools.Result("The user tapped Undo, so it was NOT done: ${what.take(120)}. Don't try it again unless they ask.", "You tapped Undo")
                null -> ask()
            }
            else -> ask()
        }
    }

    // ---------------------------------------------------------------- several things waiting: one card

    /** Several approvals waiting: one notification with "Approve All" (never money, high kinds or questions). */
    fun batchNotify(ctx: Context, pending: List<AutonomyRules.Pending>) {
        if (pending.size < 2 || !notificationsOn(ctx)) { if (pending.size < 2) try { ctx.getSystemService(NotificationManager::class.java).cancel(BATCH_ID) } catch (_: Exception) {}; return }
        val b = AutonomyRules.batch(pending)
        val text = pending.take(6).joinToString("\n") { "• " + Plain.reply(it.what).take(90) + if (it in b.single) " (needs its own tap)" else "" }
        val n = Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle(b.title).setContentText(text.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
            .setContentIntent(Bots.openIntent(ctx, null, BATCH_ID))
        if (b.bulk.size >= 2) n.addAction(Notification.Action.Builder(null, "Approve All ${b.bulk.size}", act(ctx, ACTION_APPROVE_ALL, BATCH_ID)).build())
        post(ctx, BATCH_ID, n)
    }

    /** One line for the brief. */
    fun briefLine(ctx: Context): String = try { AutonomyRules.briefLine(policy(ctx)) } catch (_: Exception) { "" }
}

/** Taps on Autonomy's notifications: Undo, Always Allow / Keep Asking, Approve All. Each is the user's own tap. */
class AutonomyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(Autonomy.EXTRA_NOTIF, 0)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            Autonomy.ACTION_UNDO -> { Autonomy.undo(id); nm.cancel(id) }
            Autonomy.ACTION_ALWAYS -> { intent.getStringExtra(Autonomy.EXTRA_KIND)?.let { Autonomy.grant(ctx, it, "always") }; nm.cancel(id) }
            Autonomy.ACTION_KEEP_ASKING -> nm.cancel(id)
            Autonomy.ACTION_APPROVE_ALL -> { Bots.init(ctx); Bots.approveAll(); nm.cancel(id) }
        }
    }
}
