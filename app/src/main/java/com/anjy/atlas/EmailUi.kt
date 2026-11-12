package com.anjy.atlas

import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import kotlinx.coroutines.launch

/**
 * Me → Email on the phone: connect an inbox with an app password (Gmail, Yahoo, iCloud, AOL, Zoho, Fastmail, most
 * others), see it's working, or remove it. Outlook.com and Proton are read in Atlas Bot's own browser instead.
 */
internal fun MainActivity.emailCard(): View {
    val c = card()
    val a = Email.account(this)
    when {
        a == null -> {
            c.addView(text("Not connected", 18f, FG, HEAD))
            c.add(text("Connect your inbox so you can just ask: “what does my email say?”, “reply to Anna that Friday works”. Atlas reads freely; sending and deleting always ask you first. The password stays on this phone, encrypted, and never syncs.", 14f, MUTED), 6)
            c.add(primary("Connect Email") { connectEmail(null) }, 14)
        }
        a.webmail != null -> {
            c.addView(text(a.email, 18f, FG, HEAD))
            c.add(text("${a.providerName} doesn't let apps sign in with a password any more, so Atlas reads it in Atlas Bot's own browser: ask “check my email” and sign in there the first time.", 14f, MUTED), 6)
        }
        else -> {
            c.addView(text(a.email, 18f, FG, HEAD))
            c.add(text("${a.providerName} · connected. Ask Atlas about it any time; sending and deleting ask you first.", 14f, MUTED), 6)
        }
    }
    if (a != null) {
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(secondary("Change") { connectEmail(a) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(secondary("Remove") {
            sheet(null, "Remove this email account?", "Atlas stops reading it. The password is deleted from this phone.", primaryLabel = "Remove", primaryColor = MISS, onPrimary = {
                scope.launch { Email.forget(applicationContext); showBanner("Email removed"); render(keepScroll = true) }
            }, secondaryLabel = "Cancel")
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        c.add(buttons, 14)
    }
    return c
}

private fun MainActivity.connectEmail(old: Email.Account?) {
    var email = old?.email ?: ""; var pass = ""; var name = old?.name ?: ""
    var ih = old?.imap?.host ?: ""; var ip = old?.imap?.port?.toString() ?: ""; var sh = old?.smtp?.host ?: ""; var sp = old?.smtp?.port?.toString() ?: ""; var user = old?.user?.takeIf { it != old.email } ?: ""
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(field("you@gmail.com", email) { email = it }.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS })
    col.add(field("App password", pass) { pass = it }.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }, 8)
    col.add(field("Your name on sent emails (optional)", name) { name = it }, 8)
    col.add(text("Other provider? Server settings (leave empty for Gmail, Yahoo, iCloud and the like):", 13f, MUTED), 12)
    col.add(field("IMAP server (imap.…)", ih) { ih = it }.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }, 6)
    col.add(field("IMAP port (993)", ip, numeric = true) { ip = it }, 6)
    col.add(field("SMTP server (smtp.…)", sh) { sh = it }.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }, 6)
    col.add(field("SMTP port (465)", sp, numeric = true) { sp = it }, 6)
    col.add(field("Username, if not the email address", user) { user = it }, 6)
    sheet(null, "Connect Email", "Use an app password, not your normal password. Gmail: turn on 2-Step Verification, then make one at myaccount.google.com/apppasswords. Yahoo, iCloud, AOL, Zoho and Fastmail have the same thing in their security settings.",
        content = col, primaryLabel = "Connect", onPrimary = {
            showBanner("Connecting…")
            scope.launch {
                val r = Email.setup(applicationContext, email, pass, name, ih, ip.toIntOrNull() ?: 0, sh, sp.toIntOrNull() ?: 0, user)
                showBanner(if (r.ok && r.webmail != null) "${Email.account(applicationContext)?.providerName}: Atlas will read it in its own browser." else r.message, if (r.ok) DONE else MISS)
                render(keepScroll = true)
            }
        }, secondaryLabel = "Cancel")
}
