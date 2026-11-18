package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneOffset

/**
 * Bot triggers: the phone's TriggerRules give exactly the PC's answers (desktop/renderer/trigrules.js) for every case in
 * tools/trigger-fixtures.json (made by desktop/test/make-trigger-fixtures.mjs).
 */
class TriggerRulesTest {
    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "tools/trigger-fixtures.json").exists()) dir = dir.parentFile
        return dir ?: error("tools/trigger-fixtures.json not found")
    }
    private val fx by lazy { JSONObject(File(root(), "tools/trigger-fixtures.json").readText()) }
    private fun objs(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
    private fun isNull(x: Any?) = x == null || x == JSONObject.NULL

    /** JSON equality with numbers compared by value and null == missing. */
    private fun same(a: Any?, b: Any?): Boolean {
        if (isNull(a) && isNull(b)) return true
        if (a is Number && b is Number) return Math.abs(a.toDouble() - b.toDouble()) < 1e-9
        if (a is JSONObject && b is JSONObject) {
            val keys = (a.keys().asSequence() + b.keys().asSequence()).toSet()
            return keys.all { same(a.opt(it), b.opt(it)) }
        }
        if (a is JSONArray && b is JSONArray) return a.length() == b.length() && (0 until a.length()).all { same(a.opt(it), b.opt(it)) }
        return a?.toString() == b?.toString()
    }
    private fun check(what: String, want: Any?, got: Any?) = assertTrue("$what\n want: $want\n  got: $got", same(want, got))
    private fun events(list: List<JSONObject>) = JSONArray().also { a -> list.forEach { a.put(it) } }

    @Test fun cleaningAndSummaries() {
        for (c in objs(fx.getJSONArray("clean"))) {
            val got = TriggerRules.cleanTrigger(c.getJSONObject("input"), c.getInt("i"))
            check("clean ${c.getJSONObject("input")}", c.getJSONObject("out"), got)
            assertEquals(c.getString("summary"), TriggerRules.summary(got))
        }
    }

    @Test fun goalsKeysWhereAndAwake() {
        for (c in objs(fx.getJSONArray("goal"))) {
            assertEquals(c.getString("out"), TriggerRules.goalFor(c.getJSONObject("bot"), c.getJSONObject("trigger"), c.getJSONObject("event")))
            assertEquals(c.getString("key"), TriggerRules.eventKey(c.getJSONObject("bot").getString("id"), c.getJSONObject("trigger").getString("id"), c.getJSONObject("event")))
        }
        for (c in objs(fx.getJSONArray("where"))) {
            val bot = JSONObject(); if (!c.isNull("home")) bot.put("home", c.getString("home"))
            assertEquals(c.getString("out"), TriggerRules.where(bot, c.getString("device")))
        }
        for (c in objs(fx.getJSONArray("awake"))) assertEquals(c.getString("out"), TriggerRules.awakeLabel(c.getJSONObject("bot")))
        for (c in objs(fx.getJSONArray("watchable"))) assertEquals("${c.getString("type")} on ${c.getString("device")}", c.getBoolean("out"), TriggerRules.watchable(c.getString("type"), c.getString("device")))
    }

    @Test fun dedupeAndRate() {
        var s: JSONObject? = JSONObject()
        for (c in objs(fx.getJSONArray("dedupe"))) { val r = TriggerRules.dedupe(s, c.getString("key"), c.getLong("at")); s = r.state; assertEquals(c.getBoolean("fresh"), r.value) }
        s = JSONObject()
        for (c in objs(fx.getJSONArray("rate"))) { val r = TriggerRules.rateAllow(s, "b|t", c.getInt("perHour"), c.getLong("at")); s = r.state; assertEquals("rate at ${c.getLong("at")}", c.getBoolean("ok"), r.value) }
    }

    @Test fun schedule() {
        val spec = fx.getJSONObject("scheduleSpec")
        var s: JSONObject? = JSONObject()
        for (c in objs(fx.getJSONArray("schedule"))) {
            val r = TriggerRules.scheduleDue(s, spec, c.getLong("at"), ZoneOffset.UTC); s = r.state
            check("fire at ${c.getLong("at")}", c.opt("fire"), r.value)
            check("next at ${c.getLong("at")}", c.opt("nextAt"), s.opt("nextAt"))
        }
    }

    private fun mails(a: JSONArray) = objs(a).map { TriggerRules.Mail(it.getLong("uid"), it.optString("from"), it.optString("subject"), it.optBoolean("unread"), it.opt("id")?.takeIf { x -> !isNull(x) }?.toString()) }

    @Test fun email() {
        val e = fx.getJSONObject("email"); val cfg = e.getJSONObject("cfg")
        var s: JSONObject? = null
        for ((i, st) in objs(e.getJSONArray("steps")).withIndex()) {
            val r = TriggerRules.emailEvents(s, mails(st.getJSONArray("messages")), cfg); s = r.state
            check("email step $i events", st.getJSONArray("events"), events(r.value)); check("email step $i state", st.getJSONObject("state"), r.state)
        }
        val f = fx.getJSONObject("emailFolderChange")
        val r = TriggerRules.emailEvents(f.getJSONObject("before"), mails(f.getJSONArray("messages")), f.getJSONObject("cfg"))
        check("folder change events", f.getJSONArray("events"), events(r.value)); check("folder change state", f.getJSONObject("state"), r.state)
    }

    @Test fun price() {
        for (name in listOf("price", "priceStartAbove")) {
            val p = fx.getJSONObject(name); val cfg = p.getJSONObject("cfg")
            var s: JSONObject? = null
            for ((i, st) in objs(p.getJSONArray("steps")).withIndex()) {
                val r = TriggerRules.priceEvents(s, st.opt("price"), st.getLong("at"), cfg); s = r.state
                check("$name step $i events", st.getJSONArray("events"), events(r.value)); check("$name step $i state", st.getJSONObject("state"), r.state)
            }
        }
    }

    @Test fun notificationsAndApps() {
        for (c in objs(fx.getJSONArray("notification"))) {
            val n = c.getJSONObject("n").let { TriggerRules.Note(it.optString("pkg"), it.optString("app"), it.optString("title"), it.optString("text")) }
            assertEquals(c.getBoolean("out"), TriggerRules.notificationMatches(c.getJSONObject("cfg"), n))
            check("notification event", c.getJSONObject("event"), TriggerRules.notificationEvent(n))
        }
        for (c in objs(fx.getJSONArray("app"))) {
            val o = c.getJSONObject("o").let { TriggerRules.Opened(it.optString("pkg"), it.optString("app")) }
            assertEquals("app ${c.getJSONObject("cfg")}", c.getBoolean("out"), TriggerRules.appMatches(c.getJSONObject("cfg"), o))
            check("app event", c.getJSONObject("event"), TriggerRules.appEvent(o, c.getLong("at")))
        }
    }

    @Test fun filesAndGlobs() {
        for (c in objs(fx.getJSONArray("glob"))) assertEquals("${c.getString("pattern")} ~ ${c.getString("name")}", c.getBoolean("out"), TriggerRules.globMatch(c.getString("pattern"), c.getString("name")))
        val f = fx.getJSONObject("file"); val cfg = f.getJSONObject("cfg")
        var s: JSONObject? = null
        for ((i, st) in objs(f.getJSONArray("steps")).withIndex()) {
            val listing = objs(st.getJSONArray("listing")).map { TriggerRules.FileInfo(it.getString("name"), it.getLong("size"), it.getLong("mtime"), it.opt("path")?.takeIf { x -> !isNull(x) }?.toString()) }
            val r = TriggerRules.fileEvents(s, listing, st.getLong("at"), cfg); s = r.state
            check("file step $i events", st.getJSONArray("events"), events(r.value)); check("file step $i state", st.getJSONObject("state"), r.state)
        }
    }
}
