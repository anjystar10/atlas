package com.anjy.atlas

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMessage

/**
 * Email's plain rules must answer exactly like the PC's (desktop/mailtext.js): the same cases are in
 * tools/mail-fixtures.json, which desktop/test/logic.test.mjs also checks. Then real MIME parsing with JavaMail.
 */
class MailTextTest {
    private val fx: JSONObject by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "tools/mail-fixtures.json").exists()) dir = dir.parentFile
        JSONObject(File(dir!!, "tools/mail-fixtures.json").readText())
    }
    private fun cases(k: String) = fx.getJSONArray(k).let { a -> List(a.length()) { a.getJSONObject(it) } }
    private fun JSONObject.str(k: String): String? = if (isNull(k) || !has(k)) null else getString(k)
    private fun JSONObject.boolOrNull(k: String): Boolean? = if (!has(k) || isNull(k)) null else getBoolean(k)

    @Test fun htmlToText() { for (c in cases("htmlToText")) assertEquals(c.getString("in"), c.getString("out"), MailText.htmlToText(c.getString("in"))) }

    @Test fun stripQuoted() {
        for (c in cases("stripQuoted")) {
            val r = MailText.stripQuoted(c.getString("in")); val o = c.getJSONObject("out")
            assertEquals(c.getString("in"), o.getString("text"), r.text); assertEquals(c.getString("in"), o.getBoolean("hidden"), r.hidden)
        }
    }

    @Test fun quoteForReply() {
        for (c in cases("quoteForReply")) {
            val o = c.getJSONObject("original")
            val ms = o.str("date")?.let { java.time.Instant.parse(it).toEpochMilli() }
            assertEquals(c.getString("out"), MailText.quoteForReply(c.getString("body"), ms, o.str("from"), o.str("text")))
        }
    }

    @Test fun replySubject() { for (c in cases("replySubject")) assertEquals(c.getString("out"), MailText.replySubject(c.getString("in"))) }

    @Test fun parseQuery() {
        for (c in cases("parseQuery")) {
            val q = MailText.parseQuery(c.getString("in")); val o = c.getJSONObject("out"); val w = c.getString("in")
            assertEquals(w, o.str("from"), q.from); assertEquals(w, o.str("to"), q.to); assertEquals(w, o.str("cc"), q.cc); assertEquals(w, o.str("subject"), q.subject)
            assertEquals(w, o.boolOrNull("unread"), q.unread); assertEquals(w, o.boolOrNull("starred"), q.starred)
            assertEquals(w, o.str("since"), q.since); assertEquals(w, o.str("before"), q.before); assertEquals(w, o.str("text"), q.text)
        }
    }

    @Test fun presetFor() {
        for (c in cases("presetFor")) {
            val p = MailText.presetFor(c.getString("in")); val o = c.getJSONObject("out"); val w = c.getString("in")
            assertEquals(w, o.getString("id"), p.id); assertEquals(w, o.getString("name"), p.name); assertEquals(w, o.getString("help"), p.help); assertEquals(w, o.getBoolean("known"), p.known)
            assertEquals(w, o.str("webmail"), p.webmail)
            for ((k, s) in listOf("imap" to p.imap, "smtp" to p.smtp)) {
                val e = o.optJSONObject(k)
                if (e == null) assertNull(w, s) else { assertEquals(w, e.getString("host"), s!!.host); assertEquals(w, e.getInt("port"), s.port); assertEquals(w, e.getBoolean("secure"), s.secure) }
            }
        }
    }

    @Test fun parseId() {
        for (c in cases("parseId")) {
            val r = MailText.parseId(c.getString("in"))
            if (c.isNull("out")) assertNull(c.getString("in"), r)
            else { val o = c.getJSONObject("out"); assertEquals(o.getString("folder"), r!!.folder); assertEquals(o.getLong("uid"), r.uid) }
        }
    }

    @Test fun splitAddresses() {
        for (c in cases("splitAddresses")) {
            val r = MailText.splitAddresses(c.getString("in"))
            if (c.isNull("out")) assertNull(c.getString("in"), r)
            else { val a = c.getJSONArray("out"); assertEquals(c.getString("in"), List(a.length()) { a.getString(it) }, r) }
        }
    }

    @Test fun addr() {
        for (c in cases("addr")) { val i = c.getJSONObject("in"); assertEquals(c.getString("out"), MailText.addr(i.getString("name"), i.getString("address"))) }
    }

    @Test fun friendlyErrors() {
        val gmail = MailText.presetFor("a@gmail.com")
        assertTrue(MailText.friendlyError(javax.mail.AuthenticationFailedException("[AUTHENTICATIONFAILED] Invalid credentials (Failure)"), gmail).let { it.startsWith("Gmail turned down the password.") && it.contains("apppasswords") })
        assertTrue(MailText.friendlyError(javax.mail.MessagingException("x", java.net.UnknownHostException("imap.nope.example")), null).startsWith("Couldn't find the mail server"))
        assertTrue(MailText.friendlyError(javax.mail.MessagingException("x", java.net.SocketTimeoutException("Read timed out")), null).startsWith("Couldn't reach the mail server"))
        assertTrue(MailText.friendlyError(Exception("535 5.7.8 Username and Password not accepted"), MailText.presetFor("x@yahoo.com")).startsWith("Yahoo Mail turned down"))
    }

    @Test fun ids() {
        assertEquals(2, MailText.parseIds("INBOX#1, INBOX#2").size)
        assertEquals("[Gmail]/All Mail", MailText.parseId("[Gmail]/All Mail#5")!!.folder)
        assertNull(MailText.parseId("INBOX#0"))
    }

    // ---- real MIME, parsed by JavaMail the way Email.kt reads a message

    private fun mime(raw: String) = MimeMessage(Session.getInstance(Properties()), raw.replace("\n", "\r\n").byteInputStream())

    @Test fun readsQuotedPrintableHtmlInAnotherCharset() {
        val m = mime("From: =?UTF-8?B?QmFuayDDgGxlcnRz?= <alerts@bank.example>\nTo: king@example.com\nSubject: =?UTF-8?B?WW91ciBzdGF0ZW1lbnQg4oCUIE5vdmVtYmVy?=\nMIME-Version: 1.0\nContent-Type: text/html; charset=iso-8859-1\nContent-Transfer-Encoding: quoted-printable\n\n<html><head><style>p{color:red}</style></head><body><p>Caf=E9 &amp; more: your balance is ready.</p><p><a href=3D\"https://bank.example/s\">View statement</a></p><script>alert(1)</script></body></html>\n")
        assertEquals("Your statement — November", m.subject)
        assertEquals("Bank Àlerts", (m.from[0] as javax.mail.internet.InternetAddress).personal)
        val (text, att) = Email.textAndAttachments(m)
        assertEquals("Café & more: your balance is ready.\n\nView statement (https://bank.example/s)", text)
        assertTrue(att.isEmpty())
    }

    @Test fun prefersThePlainPartAndListsAttachments() {
        val m = mime("From: Tolu <tolu@example.net>\nTo: king@example.com\nSubject: The report\nMIME-Version: 1.0\nContent-Type: multipart/mixed; boundary=\"XX\"\n\n--XX\nContent-Type: multipart/alternative; boundary=\"YY\"\n\n--YY\nContent-Type: text/plain; charset=utf-8\n\nReport attached.\n--YY\nContent-Type: text/html; charset=utf-8\n\n<p>Report <b>attached</b>.</p>\n--YY--\n--XX\nContent-Type: application/pdf; name=\"report.pdf\"\nContent-Disposition: attachment; filename=\"=?UTF-8?Q?r=C3=A9sum=C3=A9.pdf?=\"\nContent-Transfer-Encoding: base64\n\nJVBERi0xLjQKJcOkw7zDtsOfCg==\n--XX--\n")
        val (text, att) = Email.textAndAttachments(m)
        assertEquals("Report attached.", text.trim())
        assertEquals(listOf("résumé.pdf"), att)
    }

    @Test fun aReplyOnlyShowsTheNewPart() {
        val m = mime("From: Anna <a@x.org>\nSubject: Re: lunch\nContent-Type: text/plain; charset=utf-8\n\nFriday works!\n\nOn Sun, 8 Nov 2026 at 09:00, King <king@example.com> wrote:\n> Lunch?\n")
        val s = MailText.stripQuoted(Email.textAndAttachments(m).first)
        assertEquals("Friday works!", s.text); assertTrue(s.hidden)
        assertFalse(MailText.stripQuoted("> only a forward").hidden)
    }
}
