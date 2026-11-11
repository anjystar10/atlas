package com.anjy.atlas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Sign-ins the user keeps for Atlas Bot (site, username, password, notes), on this phone only.
 *
 * - Encrypted with an AES-256 key that lives in Android's Keystore (the key never leaves the phone's secure hardware).
 * - Never synced, never in a backup or a phone-to-phone transfer (see data_extraction_rules.xml), never in a bot's
 *   journal. The model only ever sees an entry's name and site: the values go straight from here into the bot's
 *   browser page, and only after the user's OK (or "always allow on this site").
 * - Seeing a password in Me needs the phone's own unlock (fingerprint, face or PIN).
 */
object Vault {
    private const val TAG = "AtlasVault"
    private const val ALIAS = "atlas_vault_v1"
    private const val FILE = "vault.enc"

    class Entry(
        val id: String,
        var name: String,
        var site: String,
        var username: String,
        var password: String,
        var notes: String = "",
        /** Bots may sign in with it on its site without asking each time. */
        var trusted: Boolean = false,
        var updatedAt: Long = System.currentTimeMillis(),
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("site", site).put("username", username).put("password", password)
            .put("notes", notes).put("trusted", trusted).put("updated", updatedAt)
        companion object {
            fun from(o: JSONObject) = Entry(o.getString("id"), o.optString("name"), o.optString("site"), o.optString("username"), o.optString("password"),
                o.optString("notes"), o.optBoolean("trusted"), o.optLong("updated"))
        }
    }

    private val lock = Any()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    private fun file(ctx: Context) = AtomicFile(File(ctx.applicationContext.filesDir, FILE))

    /** Every entry (with its values): for this file and Me only. Blocking. */
    fun all(ctx: Context): List<Entry> = synchronized(lock) {
        val f = file(ctx)
        if (!f.baseFile.exists()) return emptyList()
        try {
            val raw = f.readFully()
            if (raw.size < 13) return emptyList()
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
            val a = JSONArray(String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8))
            List(a.length()) { Entry.from(a.getJSONObject(it)) }
        } catch (e: Exception) {
            // unreadable (e.g. the Keystore key was wiped): keep the file as it is, show nothing
            Log.w(TAG, "vault unreadable", e); emptyList()
        }
    }

    private fun write(ctx: Context, entries: List<Entry>) = synchronized(lock) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = c.doFinal(JSONArray().apply { entries.forEach { put(it.toJson()) } }.toString().toByteArray(Charsets.UTF_8))
        val f = file(ctx)
        val out = f.startWrite()
        try { out.write(c.iv); out.write(body); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
    }

    fun save(ctx: Context, e: Entry) = synchronized(lock) {
        val list = all(ctx).toMutableList()
        e.site = siteOf(e.site); e.updatedAt = System.currentTimeMillis()
        val i = list.indexOfFirst { it.id == e.id }
        if (i >= 0) list[i] = e else list.add(e)
        write(ctx, list)
    }

    fun delete(ctx: Context, id: String) = synchronized(lock) { write(ctx, all(ctx).filter { it.id != id }) }

    /** "https://www.accounts.google.com/login" → "accounts.google.com" (what's stored and compared). */
    fun siteOf(s: String): String {
        val t = s.trim().lowercase()
        val host = if ("://" in t) try { java.net.URI(t).host ?: t } catch (_: Exception) { t } else t.substringBefore('/')
        return host.removePrefix("www.").trim('.')
    }

    /** True when [host] (the page the bot is on) belongs to [site]: the same, or a subdomain of it. */
    fun siteMatches(site: String, host: String): Boolean {
        val s = siteOf(site); val h = siteOf(host)
        return s.isNotEmpty() && h.isNotEmpty() && (h == s || h.endsWith(".$s"))
    }

    /** The entry a bot means: by id, exact name, or site; null if none or more than one fits. */
    fun find(entries: List<Entry>, ref: String): Entry? {
        val r = ref.trim()
        if (r.isEmpty()) return null
        entries.firstOrNull { it.id == r }?.let { return it }
        entries.filter { it.name.equals(r, true) }.let { if (it.size == 1) return it[0] }
        return entries.filter { siteMatches(it.site, r) }.singleOrNull()
    }

    /** What the model may know: names and sites only, never usernames or passwords. */
    fun forModel(entries: List<Entry>): String =
        if (entries.isEmpty()) "The vault is empty: the user hasn't saved any sign-ins for Atlas (they can in Me → Vault)."
        else "Saved sign-ins (use browser_fill_login with the name, on that site's sign-in page):\n" + entries.joinToString("\n") { "- ${it.name} — ${it.site}" + (if (it.trusted) " (allowed without asking)" else "") }

    val listDeclaration: JSONObject by lazy {
        JSONObject().put("name", "vault_list").put("description", "List the sign-ins the user saved in Atlas's vault (names and sites only; you never see usernames or passwords).")
    }
    val fillDeclaration: JSONObject by lazy {
        JSONObject().put("name", "browser_fill_login").put("description",
            "On a sign-in page in your browser, fill in a saved sign-in from the vault (username and password) without seeing it. It only works on that entry's own site, and waits for the user's OK unless they allowed it for that site. Then click the sign-in button yourself.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("entry", JSONObject().put("type", "string").put("description", "The saved sign-in's name from vault_list")))
                .put("required", JSONArray().put("entry")))
    }

    /** The page script that puts [user] and [pass] into the sign-in form; it reports only whether it found the fields. */
    fun fillJs(user: String, pass: String): String = """
(function(u,p){
 try {
  function vis(e){ var r=e.getBoundingClientRect(); if(r.width<2||r.height<2) return false; var s=getComputedStyle(e); return s.visibility!='hidden'&&s.display!='none'; }
  function put(e,v){ e.focus(); var set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set; set.call(e,v);
    e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true})); e.blur(); }
  var pws=[].slice.call(document.querySelectorAll('input[type=password]')).filter(vis);
  var pw=pws[0]||null; var root=(pw&&pw.form)||document;
  var cands=[].slice.call(root.querySelectorAll('input[type=email],input[type=text],input[type=tel],input:not([type])')).filter(vis);
  var user=null;
  for(var i=0;i<cands.length;i++){ var a=(cands[i].autocomplete||'')+' '+(cands[i].name||'')+' '+(cands[i].id||''); if(/user|email|login|phone|account/i.test(a)){ user=cands[i]; break; } }
  if(!user && pw){ for(var j=cands.length-1;j>=0;j--){ if(cands[j].compareDocumentPosition(pw)&Node.DOCUMENT_POSITION_FOLLOWING){ user=cands[j]; break; } } }
  if(!user && !pw && cands.length) user=cands[0];
  if(user && u) put(user,u);
  if(pw && p) put(pw,p);
  return JSON.stringify({ok:!!(user||pw),user:!!(user&&u),pass:!!(pw&&p)});
 } catch(e){ return JSON.stringify({ok:false,why:String(e)}); }
})(${JSONObject.quote(user)},${JSONObject.quote(pass)})
""".trimIndent()
}
