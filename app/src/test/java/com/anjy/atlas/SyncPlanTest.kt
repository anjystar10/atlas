package com.anjy.atlas

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The phone and the PC must agree exactly: the same cases run in desktop/test/logic.test.mjs (tools/sync-fixtures.json,
 * made by running the PC's own code, plus a ciphertext made by the JVM's AES-GCM the phone uses).
 */
class SyncPlanTest {
    private val fx: JSONObject by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "tools/sync-fixtures.json").exists()) dir = dir.parentFile
        JSONObject(File(dir!!, "tools/sync-fixtures.json").readText())
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test fun sameOwnerAsThePc() = assertEquals(fx.getString("owner"), Sync.owner(fx.getString("code")))

    @Test fun readsWhatThePcEncrypts() = assertEquals(fx.getString("plain"), Sync.decrypt(fx.getString("code"), fx.getString("fromPc")))

    @Test fun ownRoundTripAndWrongCodeFails() {
        val c = fx.getString("code"); val p = fx.getString("plain")
        assertEquals(p, Sync.decrypt(c, Sync.encrypt(c, p)))
        assertEquals(p, Sync.decrypt(c, fx.getString("fromPhoneJava")))
        assertTrue(runCatching { Sync.decrypt("ATLS-ABCD-EFGH-JKMN-PQRS-TV24", Sync.encrypt(c, p)) }.isFailure)
    }

    @Test fun sameFingerprintsAsThePc() {
        val cases = fx.getJSONArray("canonical")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val canon = Sync.canonical(JSONObject(c.getString("json")))
            assertEquals("case $i", c.getString("canonical"), canon)
            assertEquals("case $i", c.getString("sha1"), sha1(canon))
        }
    }

    @Test fun sameTieRule() {
        val cases = fx.getJSONArray("mineWins")
        for (i in 0 until cases.length()) {
            val m = cases.getJSONObject(i)
            assertEquals("case $i", m.getBoolean("wins"), SyncPlan.mineWins(m.getLong("myAt"), m.getString("me"), m.getLong("theirAt"), m.getString("theirDevice")))
        }
    }

    @Test fun sameStampRules() {
        val cases = fx.getJSONArray("stamp")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val sent = JSONObject(c.getJSONObject("sent").toString()); val dirty = JSONObject(c.getJSONObject("dirty").toString())
            val local = c.getJSONObject("local").let { o -> o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { SyncPlan.Local(it.getString("fp"), it.optLong("ownTime")) } } }
            val gone = c.getJSONObject("gone").let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }
            fun set(a: JSONArray) = (0 until a.length()).map { a.getString(it) }.toSet()
            SyncPlan.stamp(sent, dirty, local, gone, c.getLong("now"), c.getBoolean("first"), set(c.getJSONArray("skip")), set(c.getJSONArray("extraImplicit")))
            assertEquals(c.getString("name"), Sync.canonical(c.getJSONObject("expectSent")), Sync.canonical(sent))
            assertEquals(c.getString("name"), Sync.canonical(c.getJSONObject("expectDirty")), Sync.canonical(dirty))
        }
    }

    @Test fun freshDataRestoresInsteadOfDeleting() {
        assertTrue(Sync.freshStart("old-id", "new-id"))
        assertFalse(Sync.freshStart("", "new-id"))
        assertFalse(Sync.freshStart("same", "same"))
    }
}
